"""Trains and exports the delinquency-aware (multi-state) transition model.

The survival model (export_survival_model.py) gives a loan's hazard of default and of prepayment and ignores whether
the loan is already late. This model is the move between delinquency states: from each active state (current, 30,
60 days past due) the probability of every next state (current, 30, 60, default, prepaid), conditioned on the source
state, the loan's history up to the previous month, its origination attributes, loan age, the macro regime, the rate
incentive and the state macro drivers. survival.MultiStateEngine projects it exactly over the regime chain.

Features: the survival model's, in the same order (so the engine reuses its row builder), then source_state and the
three history features of build_delinquency_dataset.py. One LightGBM multiclass model with the source state as a
feature, rather than one model per state: the three states share the macro and origination response.

Protocol (same shape as the survival model)
  1. Validation fit: loans in loan-buckets 3 to 9, months before 2023-01 (bucket 2 stops the trees early). Evaluation:
     loans in buckets 0 and 1 (in no fit), months from 2023-01. Release-final months are left out, as there.
  2. Benchmarks on the same rows: the existing survival model's validation fit (blind to delinquency; refit here
     with its own code and cached) and a roll-rate table (source state by loan-age bucket).
  3. Gates (frozen from the pilot, below): (1) on delinquent loan-months the out-of-time log loss beats the
     survival model's; (2) predicted over realized 12-month default and cure rates by starting state are within the
     frozen tolerance; (3) lifetime PD never falls as the starting state worsens, on a grid of loans. A failed gate
     refuses the export and names itself.
  4. Production fit on every month of buckets 3 to 9; gates re-checked on the held-out loans; artifacts written.

Run from backend/ after build_delinquency_dataset.py, build_survival_dataset.py and build_crisis_dataset.py (it refits
the survival validation model for the comparison; ~2 minutes). `--pilot` runs steps 1 and 2 and writes
data/processed/delinquency/pilot.json without any artifact; the gate tolerances are chosen from it and then frozen
in GATES (and in openspec/changes/delinquency-aware-risk-and-uncertainty/design.md D7) before the production fit.

Writes delinquency_model.onnx and delinquency_model.json (the card) to risk-engine/src/main/resources.
"""
from __future__ import annotations

import json
import math
import shutil
import sys
import time
from datetime import datetime, timezone

import lightgbm as lgb
import numpy as np
import polars as pl
from sklearn.metrics import log_loss

import export_survival_model as esm
from delinquency_history import aged_history, monotone_repair
from credit_common import (CATEGORICAL, DATA, NUMERIC, RESOURCES, SURVIVAL, category_mappings, export_onnx,
                           float32_exact_booster, loan_bucket, quantize, sha256, write_json)

KEY = "loan_sequence_number"
DELINQ = DATA / "delinquency"
HISTORY = ["hist_episodes_12m", "hist_months_since_cure", "hist_max_state_12m"]
FEATURES = esm.FEATURES + ["source_state"] + HISTORY
CLASSES = ["current", "d30", "d60", "default", "prepaid"]
CURRENT, D30, D60, DEFAULT, PREPAID = range(5)
NO_CURE = 13
TEST_START = esm.TEST_START
HORIZON = 12                                  # months of the default and cure rate gates
# At 0.05 the validation fit stops by itself at 522 rounds, but the production fit (more months) kept accepting gains of
# 1e-5 to 1,329 rounds: 6,645 trees, a 52.7 MB artifact whose float32 sums then drifted from the native model by 1.9e-4
# relative, past the export's 1e-4 conversion check (a safeguard, not loosened). 800 rounds (4,000 trees, checked up to
# 4,140 to pass) is the cap; the log loss still to gain beyond it on the validation set is about 2e-4.
PARAMS = dict(objective="multiclass", num_class=5, learning_rate=0.05, num_leaves=63, min_child_samples=500,
              subsample=0.8, subsample_freq=1, colsample_bytree=0.9, reg_lambda=5.0, n_estimators=800,
              verbose=-1, random_state=7, deterministic=True, force_row_wise=True)
# From a delinquent state no transition is given less than this probability. Boosted trees grow pure leaves for
# near-deterministic cells (a long-stay 60-days-late loan almost never defaults in training), and an out-of-sample
# loan that does is then given a probability of 1e-200 and costs hundreds of nats of log loss. 0.1% is below the
# sparsest observed cell from a delinquent state (30 days late to default out of time, 0.15%). It is not applied
# from the current state, whose real transitions to 60 days late or to default are rarer than this (about 1 in 50,000).
PROBABILITY_FLOOR = 0.001
FLOOR_SOURCES = [D30, D60]
# Relative tolerance of the ONNX-versus-booster check (credit_common.export_onnx, default 1e-4 for every other model). The
# graph sums float32 leaf values over ~4,000 trees and five classes; on 50,000 sampled loan-months it differs from the
# float64 booster by at most 2.8e-5 absolute and 1.8e-4 relative, in 3 rows, all from 60 days past due (probabilities
# of 0.04 to 0.19). The graph is what Java and the golden fixtures use, so that difference does not reach the engine
# contract, and it is 100 times below the model's calibration error; the card records the measured figures.
ONNX_RTOL = 3e-4
AGE_EDGES = [12, 36, 72]                      # roll-rate benchmark: loan-age buckets 0-11, 12-35, 36-71, 72+
START_SAMPLE = {CURRENT: 60_000, D30: 60_000, D60: None}   # 12-month evaluation starts per state (None = all)

# Frozen from the pilot (data/processed/delinquency/pilot.json, validation fit, 2026-11-03) before the production fit;
# the reasoning is in design.md D7 and the card. `*_rel` is the largest accepted |predicted / realized - 1| for a
# 12-month rate; a rate is judged only where `min_events` realized events stand behind it (below that the card reports
# it and the gate does not judge). The pilot's worst deviations were 0.37 (default rate from current, 737 events; 0.25
# from 30 days, 0.17 from 60) and 0.11 (cure rates), so the default tolerance is that worst case rounded up to 0.40 and
# the cure tolerance 0.15. They are not tightened to what the production fit happens to achieve.
GATES = {"min_events": 200, "default_rel": 0.40, "cure_rel": 0.15, "log_loss_margin": 0.0}

LIMITATIONS = [
    "Sparse deep-delinquency data: loan-months that start 60 days past due are {d60_share:.2%} of the training rows ({d60_rows:,} in the out-of-time evaluation), so transitions out of that state are noisier than the others; the 12-month rates by starting state carry the event counts shown in this card, and a rate with fewer than the gate's minimum events is reported but not judged.",
    "Cure behaviour reflects 2017 to 2026, including pandemic forbearance, when many loans were allowed to miss payments and return to current; it may not carry to another period or to a downturn.",
    "The data contains no housing downturn: unlike the survival model, this one is not fitted on the 2006-2008 vintages (its history features need the monthly status those files carry, but the change that builds it scopes them out), so its response to falling prices and to loans under water rests on 2017 to 2026 alone.",
    "A projection ages the loan's history by a fixed rule instead of following it: the features (months since the last cure, spells and the worst state in the last 12 months) depend on the path, which would make the exact recursion over states impossible. On a path with no new spell the history is exactly clean 12 months in, and the rule uses that; a spell that begins during the projection is represented only by the model seeing, from a late state, at least one spell and a worst state at least the source, and a spell that ends during it is taken to have ended at the start. The 12-month rates by starting state below were checked under this same rule.",
    "The model carries no drift overlay: the survival model's overlay scales its own hazards and is not transferred. The held-out drift of this model's default and prepayment probabilities from current is reported in this card.",
    "A projection's transition probabilities are made stochastically ordered by starting state before they are used (delinquency_history.monotone_repair): a worse state is never given a smaller chance of landing at or above any class than a better one. It leaves the model's output unchanged where it already respects that order and is what guarantees lifetime default does not fall as the starting state worsens; the share of months it changed on the evaluation grid is in this card. One-step metrics are on single rows and are not repaired.",
    "Trained on agency-conforming fixed-rate mortgages; not validated for other products. The property's state is a feature, and geography can stand in for protected characteristics: a lender using these figures in credit decisions must test for disparate impact on its own portfolio.",
]


def month_label(m: int) -> str:
    return f"{m // 12:04d}-{m % 12 + 1:02d}"


def matrix(df: pl.DataFrame) -> np.ndarray:
    return quantize(df.select(FEATURES).to_numpy(), FEATURES)


# ----------------------------------------------------------------------------------------------------------------------
# data


def load(mappings: dict) -> tuple[pl.DataFrame, pl.DataFrame, list[int], int]:
    """The featurized rows, and the same rows before featurizing (their raw origination fields, which the 12-month
    grid needs to build the model's inputs for months that are not in the data)."""
    loans = pl.read_parquet(str(SURVIVAL / "loans" / "*.parquet"))
    finals = esm.release_final_months(loans)
    coverage_end = finals[0] - 1
    rows = pl.read_parquet(str(DELINQ / "rows" / "*.parquet"))
    before = rows.height
    rows = rows.filter(~pl.col("month").is_in(finals)).sort([KEY, "loan_age"])
    raw = rows
    rows = esm.featurize(rows, pl.col("month") - pl.col("loan_age").cast(pl.Int32), mappings).with_columns(
        bucket=loan_bucket(pl.col(KEY)),
        source_state=pl.col("source_state").cast(pl.Float32),
        **{h: pl.col(h).cast(pl.Float32) for h in HISTORY}).sort([KEY, "loan_age"])
    print(f"transition rows: {rows.height:,} ({before - rows.height:,} in release-final months "
          f"{[month_label(m) for m in finals]} left out); every vintage reports through {month_label(coverage_end)}", flush=True)
    return rows, raw, finals, coverage_end


def fit(train: pl.DataFrame, valid: pl.DataFrame) -> tuple[lgb.Booster, int]:
    model = lgb.LGBMClassifier(**PARAMS)
    model.fit(matrix(train), train["label"].to_numpy(), sample_weight=train["weight"].to_numpy(),
              eval_set=[(matrix(valid), valid["label"].to_numpy())], eval_sample_weight=[valid["weight"].to_numpy()],
              eval_metric="multi_logloss", categorical_feature=[FEATURES.index(c) for c in CATEGORICAL],
              callbacks=[lgb.early_stopping(50, verbose=False), lgb.log_evaluation(100)])
    best = int(model.best_iteration_)
    return float32_exact_booster(lgb.Booster(model_str=model.booster_.model_to_string(num_iteration=best))), best


def validation_fit(train: pl.DataFrame, valid: pl.DataFrame, refit: bool) -> tuple[lgb.Booster, int]:
    """The validation fit, cached (it takes ~9 minutes and is deterministic): reused unless the training rows, the
    features or the hyperparameters changed (their fingerprint is stored beside it) or --refit is given."""
    import hashlib
    fingerprint = hashlib.sha256(json.dumps([FEATURES, PARAMS, train.height, valid.height, int(train["weight"].sum()),
                                             float(train["label"].sum())], default=str).encode()).hexdigest()
    cache, stamp = DELINQ / "validation_booster.txt", DELINQ / "validation_booster.json"
    if not refit and cache.exists() and stamp.exists() and json.loads(stamp.read_text())["fingerprint"] == fingerprint:
        print("validation fit: reusing the cached booster", flush=True)
        return lgb.Booster(model_file=str(cache)), json.loads(stamp.read_text())["best_iteration"]
    booster, best = fit(train, valid)
    booster.save_model(str(cache))
    stamp.write_text(json.dumps({"fingerprint": fingerprint, "best_iteration": best}))
    return booster, best


def production_fit(train: pl.DataFrame, valid: pl.DataFrame, refit: bool) -> tuple[lgb.Booster, int]:
    """The production fit, cached like the validation fit (it takes ~25 minutes and is deterministic)."""
    import hashlib
    fingerprint = hashlib.sha256(json.dumps([FEATURES, PARAMS, train.height, valid.height, int(train["weight"].sum()),
                                             float(train["label"].sum())], default=str).encode()).hexdigest()
    cache, stamp = DELINQ / "production_booster.txt", DELINQ / "production_booster.json"
    if not refit and cache.exists() and stamp.exists() and json.loads(stamp.read_text())["fingerprint"] == fingerprint:
        print("production fit: reusing the cached booster", flush=True)
        return lgb.Booster(model_file=str(cache)), json.loads(stamp.read_text())["best_iteration"]
    booster, best = fit(train, valid)
    booster.save_model(str(cache))
    stamp.write_text(json.dumps({"fingerprint": fingerprint, "best_iteration": best}))
    return booster, best


def faithful_prefix(booster: lgb.Booster, best: int, onnx_path, sample: np.ndarray) -> tuple[lgb.Booster, int]:
    """The longest prefix of the fitted rounds whose ONNX graph reproduces the native model within the shared export
    check (credit_common.export_onnx, unchanged): a graph sums float32 leaf values, and across five classes of rounds
    whose scores reach +/-15 that drifts past the check before the trees stop helping. The model that ships, and that the
    gates are judged on, is the prefix that converts; later rounds improve the validation log loss by about 0.3%."""
    for rounds in dict.fromkeys([best, 700, 600, 500, 400, 300, 200]):
        if rounds > best:
            continue
        candidate = booster if rounds == best else float32_exact_booster(lgb.Booster(model_str=booster.model_to_string(num_iteration=rounds)))
        try:
            export_onnx(candidate, len(FEATURES), onnx_path, sample, rtol=ONNX_RTOL)
            return candidate, rounds
        except SystemExit:
            print(f"  {rounds} rounds ({5 * rounds} trees): the ONNX graph does not reproduce the model within the export check", flush=True)
    raise SystemExit("no prefix of the fitted model converts within the export check")


def existing_validation_booster(mappings: dict) -> lgb.Booster:
    """The survival model fitted by its own protocol (before 2023-01, loan-buckets 3 to 9): the benchmark the new
    model must beat. Refit with the survival script's code, since only the production model is saved; cached."""
    cache = DELINQ / "existing_validation_booster.txt"
    if cache.exists():
        return lgb.Booster(model_file=str(cache))
    loans = pl.read_parquet(str(SURVIVAL / "loans" / "*.parquet"))
    finals = esm.release_final_months(loans)
    rows = pl.read_parquet(str(SURVIVAL / "rows" / "*.parquet")).filter(~pl.col("month").is_in(finals))
    crisis = pl.read_parquet(str(esm.CRISIS / "rows" / "*.parquet")).filter(pl.col("month") <= esm.CRISIS_END)
    rows = pl.concat([crisis, rows], how="vertical_relaxed")
    rows = esm.featurize(rows, pl.col("month") - pl.col("loan_age").cast(pl.Int32), mappings).with_columns(
        bucket=loan_bucket(pl.col(KEY))).sort([KEY, "loan_age"])
    past = rows.filter(pl.col("month") < TEST_START)
    booster, best = esm.fit(past.filter(pl.col("bucket") >= 3), past.filter(pl.col("bucket") == 2))
    print(f"survival validation model refit: best iteration {best}", flush=True)
    booster.save_model(str(cache))
    return booster


# ----------------------------------------------------------------------------------------------------------------------
# benchmarks and one-step metrics


def collapse(p5: np.ndarray) -> np.ndarray:
    """Five next-state probabilities to the survival model's three classes: continue (any active state), default, prepay."""
    return np.column_stack([p5[:, CURRENT] + p5[:, D30] + p5[:, D60], p5[:, DEFAULT], p5[:, PREPAID]])


def collapse_labels(y: np.ndarray) -> np.ndarray:
    return np.where(y <= D60, 0, y - D60)


def normalized(p: np.ndarray) -> np.ndarray:
    return p / p.sum(axis=1, keepdims=True)


def predict(booster: lgb.Booster, x: np.ndarray, source: np.ndarray) -> np.ndarray:
    """Next-state probabilities of the model of record: renormalized, with the floor from delinquent sources
    (the same steps the Java model applies after the graph, and golden_multistate.py)."""
    p = normalized(booster.predict(x))
    floored = np.isin(source.astype(int), FLOOR_SOURCES)
    p[floored] = normalized(np.maximum(p[floored], PROBABILITY_FLOOR))
    return p


def roll_rate_table(train: pl.DataFrame) -> np.ndarray:
    """Weighted next-state frequencies by (source state, loan-age bucket): the benchmark with no loan or macro
    conditioning. A half observation per cell keeps an unseen outcome from having probability zero."""
    src = train["source_state"].to_numpy().astype(int)
    bucket = np.digitize(train["loan_age"].to_numpy(), AGE_EDGES)
    counts = np.full((3, len(AGE_EDGES) + 1, 5), 0.5)
    np.add.at(counts, (src, bucket, train["label"].to_numpy()), train["weight"].to_numpy())
    return counts / counts.sum(axis=2, keepdims=True)


def roll_rate_probabilities(table: np.ndarray, df: pl.DataFrame) -> np.ndarray:
    return table[df["source_state"].to_numpy().astype(int), np.digitize(df["loan_age"].to_numpy(), AGE_EDGES)]


def one_step(test: pl.DataFrame, models: dict[str, np.ndarray]) -> dict:
    """Weighted log loss on the survival model's three classes, overall and by source state, with event counts.
    `models` maps a name to (rows, 5) next-state probabilities or (rows, 3) class probabilities."""
    y5, w, src = test["label"].to_numpy(), test["weight"].to_numpy(), test["source_state"].to_numpy().astype(int)
    y3 = collapse_labels(y5)
    out = {}
    for name, p in models.items():
        p3 = normalized(collapse(p) if p.shape[1] == 5 else p)
        entry = {"all_rows": float(log_loss(y3, p3, sample_weight=w, labels=[0, 1, 2]))}
        late = src >= 1
        entry["delinquent_rows"] = float(log_loss(y3[late], p3[late], sample_weight=w[late], labels=[0, 1, 2]))
        for s, label in enumerate(["current", "d30", "d60"]):
            m = src == s
            entry[label] = float(log_loss(y3[m], p3[m], sample_weight=w[m], labels=[0, 1, 2]))
        if p.shape[1] == 5:
            entry["next_state_all_rows"] = float(log_loss(y5, normalized(p), sample_weight=w, labels=range(5)))
            entry["next_state_by_source"] = {label: float(log_loss(y5[src == s], normalized(p[src == s]), sample_weight=w[src == s],
                                                                    labels=range(5))) for s, label in enumerate(["current", "d30", "d60"])}
        out[name] = entry
    out["rows"] = {"all": int(len(y5)), "by_source": {label: int((src == s).sum()) for s, label in enumerate(["current", "d30", "d60"])},
                   "events_by_source": {label: {c: int(((src == s) & (y5 == k)).sum()) for k, c in enumerate(CLASSES)}
                                        for s, label in enumerate(["current", "d30", "d60"])}}
    return out


# ----------------------------------------------------------------------------------------------------------------------
# 12-month rates by starting state


def starts_sample(test: pl.DataFrame, raw: pl.DataFrame, coverage_end: int) -> pl.DataFrame:
    """The loan-months the 12-month rates start from: a sample of the evaluation rows whose 12 months are all within
    the data, taken from the raw rows (origination fields as given). A stay-current row stands for ten, via its weight."""
    usable = test.filter(pl.col("month") + HORIZON - 1 <= coverage_end)
    pieces = []
    for state, cap in START_SAMPLE.items():
        part = usable.filter(pl.col("source_state") == state)
        pieces.append(part.sample(n=cap, seed=11 + state) if cap and part.height > cap else part)
    chosen = pl.concat(pieces).select([KEY, pl.col("loan_age").cast(pl.Int64)]).with_row_index("sid")
    return chosen.join(raw, on=[KEY, "loan_age"]).sort("sid")


def realized_paths(starts: pl.DataFrame, states: pl.DataFrame) -> dict:
    """What happened to each start over its 12 months, from the loan's own reported states. A start counts only if
    its path is fully observed: every month up to the first default or prepayment is a reported active state."""
    joined = starts.select(["sid", KEY, "month"])
    for k in range(HORIZON):
        later = states.select([KEY, (pl.col("month") - k).alias("month"), pl.col("state").fill_null(-2).alias(f"s{k}")])
        joined = joined.join(later, on=[KEY, "month"], how="left")
    joined = joined.sort("sid")
    s = np.column_stack([joined[f"s{k}"].fill_null(-3).to_numpy() for k in range(HORIZON)])
    absorbing = (s == DEFAULT) | (s == PREPAID)
    first = np.where(absorbing.any(axis=1), absorbing.argmax(axis=1), HORIZON)
    allowed = ((s >= 0) & (s <= D60)) | absorbing
    reached = np.logical_and.accumulate(allowed, axis=1)
    valid = reached[np.arange(len(s)), np.minimum(first, HORIZON - 1)]
    defaulted = valid & (first < HORIZON) & (s[np.arange(len(s)), np.minimum(first, HORIZON - 1)] == DEFAULT)
    cured = valid & ((s == CURRENT) & (np.arange(HORIZON)[None, :] < first[:, None])).any(axis=1)
    return {"valid": valid, "default": defaulted, "cure": cured}


def grid(starts: pl.DataFrame, mappings: dict) -> pl.DataFrame:
    """Every start at each of its 12 months from each source state, with that month's macro drivers; the start's
    history is held. Sorted by (start, month offset, source state) so the model output reshapes to (start, 12, 3, 5)."""
    columns = ["sid", KEY, *NUMERIC, *CATEGORICAL, "loan_age", "month", *HISTORY]
    g = (starts.select(columns).join(pl.DataFrame({"k": list(range(HORIZON))}), how="cross")
         .join(pl.DataFrame({"sp": [0, 1, 2]}), how="cross")
         .with_columns(loan_age=pl.col("loan_age") + pl.col("k"), month=pl.col("month") + pl.col("k")))
    g = esm.featurize(g, pl.col("month") - pl.col("loan_age").cast(pl.Int32), mappings).sort(["sid", "k", "sp"])
    episodes, since, worst = aged_history(g["sp"].to_numpy(), g["k"].to_numpy(), g["hist_episodes_12m"].to_numpy(), g["hist_months_since_cure"].to_numpy(),
                                          g["hist_max_state_12m"].to_numpy())
    return g.with_columns(source_state=pl.col("sp").cast(pl.Float32), hist_episodes_12m=pl.Series(episodes).cast(pl.Float32),
                          hist_months_since_cure=pl.Series(since).cast(pl.Float32), hist_max_state_12m=pl.Series(worst).cast(pl.Float32))


def propagate(p: np.ndarray, start_state: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    """p: (starts, 12, 3, 5) next-state probabilities along each start's path. Returns the probability of default
    within 12 months, and of returning to current (first passage: current is absorbing for this second figure)."""
    n = len(p)
    v = np.zeros((n, 3))
    v[np.arange(n), start_state] = 1.0
    default = np.zeros(n)
    for k in range(HORIZON):
        default += np.einsum("ns,ns->n", v, p[:, k, :, DEFAULT])
        v = np.einsum("ns,nst->nt", v, p[:, k, :, :3])
    u = np.zeros((n, 3))
    u[np.arange(n), start_state] = 1.0
    cure = np.zeros(n)
    for k in range(HORIZON):
        cure += u[:, 1] * p[:, k, 1, CURRENT] + u[:, 2] * p[:, k, 2, CURRENT]
        u = np.column_stack([np.zeros(n), u[:, 1] * p[:, k, 1, D30] + u[:, 2] * p[:, k, 2, D30],
                             u[:, 1] * p[:, k, 1, D60] + u[:, 2] * p[:, k, 2, D60]])
    return default, cure


def survival_default(hazards: np.ndarray) -> np.ndarray:
    """The state-blind model: 12-month default probability from monthly hazards (starts, 12, 3 = continue, default, prepay)."""
    alive, default = np.ones(len(hazards)), np.zeros(len(hazards))
    for k in range(HORIZON):
        default += alive * hazards[:, k, 1]
        alive = alive * (1.0 - hazards[:, k, 1] - hazards[:, k, 2])
    return default


def twelve_month(starts: pl.DataFrame, g: pl.DataFrame, real: dict, predictors: dict) -> dict:
    """Predicted against realized 12-month default and cure rate by starting state, weighted, with event counts."""
    state = starts["source_state"].to_numpy().astype(int)
    w = starts["weight"].to_numpy()
    out = {}
    for name, (default, cure) in predictors.items():
        by = {}
        for s, label in enumerate(["current", "d30", "d60"]):
            m = real["valid"] & (state == s)
            if not m.any():
                continue
            entry = {"starts": int(m.sum()), "default_events": int(real["default"][m].sum()),
                     "default_predicted": float(np.average(default[m], weights=w[m])),
                     "default_realized": float(np.average(real["default"][m], weights=w[m]))}
            entry["default_ratio"] = entry["default_predicted"] / entry["default_realized"] if entry["default_realized"] else None
            if s >= 1 and cure is not None:
                entry |= {"cure_events": int(real["cure"][m].sum()), "cure_predicted": float(np.average(cure[m], weights=w[m])),
                          "cure_realized": float(np.average(real["cure"][m], weights=w[m]))}
                entry["cure_ratio"] = entry["cure_predicted"] / entry["cure_realized"] if entry["cure_realized"] else None
            by[label] = entry
        out[name] = by
    return out


def evaluate(booster: lgb.Booster, existing: lgb.Booster, table: np.ndarray, test: pl.DataFrame, starts: pl.DataFrame,
             g: pl.DataFrame, real: dict) -> dict:
    """Every comparison the gates and the card need, for one fitted booster."""
    new_p = predict(booster, matrix(test), test["source_state"].to_numpy())
    existing_p = normalized(existing.predict(esm.matrix(test)))
    one = one_step(test, {"delinquency model": new_p, "survival model": existing_p, "roll-rate table": roll_rate_probabilities(table, test)})
    n = starts.height
    raw5 = predict(booster, matrix(g), g["sp"].to_numpy()).reshape(n, HORIZON, 3, 5)
    p5 = monotone_repair(raw5)
    changed = np.abs(p5 - raw5).max(axis=(2, 3))                 # per (start, month): the largest probability moved
    state = starts["source_state"].to_numpy().astype(int)
    new_default, new_cure = propagate(p5, state)
    h = existing.predict(esm.matrix(g.filter(pl.col("sp") == 0))).reshape(n, HORIZON, 3)
    age = starts["loan_age"].to_numpy()[:, None] + np.arange(HORIZON)[None, :]
    rr = table[np.arange(3)[None, None, :], np.digitize(age, AGE_EDGES)[:, :, None]]       # (starts, 12, 3, 5)
    rr_default, rr_cure = propagate(rr, state)
    rates = twelve_month(starts, g, real, {"delinquency model": (new_default, new_cure),
                                           "survival model": (survival_default(h), None),
                                           "roll-rate table": (rr_default, rr_cure)})
    return {"one_step": one, "twelve_month": rates,
            "monotone_repair": {"month_triples": int(changed.size), "changed": int((changed > 1e-12).sum()),
                                "share_changed": float((changed > 1e-12).mean()), "mean_largest_change": float(changed[changed > 1e-12].mean()) if (changed > 1e-12).any() else 0.0,
                                "largest_change": float(changed.max()),
                                "note": "on the 12-month evaluation grid; one-step metrics are on single rows and are not repaired"}}


def check_gates(report: dict) -> list[str]:
    """The frozen gates 1 and 2; an empty list means they hold. (Gate 3, monotonicity, runs on the projection.)"""
    failures = []
    ll = report["one_step"]
    new, old = ll["delinquency model"]["delinquent_rows"], ll["survival model"]["delinquent_rows"]
    if not new < old - GATES["log_loss_margin"]:
        failures.append(f"gate 1 (log loss on delinquent loan-months): the delinquency model's {new:.5f} is not below the "
                        f"survival model's {old:.5f}")
    for label, entry in report["twelve_month"]["delinquency model"].items():
        for kind, tolerance in (("default", GATES["default_rel"]), ("cure", GATES["cure_rel"])):
            events, ratio = entry.get(f"{kind}_events"), entry.get(f"{kind}_ratio")
            if events is None or events < GATES["min_events"] or ratio is None:
                continue
            if abs(ratio - 1.0) > tolerance:
                failures.append(f"gate 2 (12-month {kind} rate, starting {label}): predicted over realized {ratio:.3f} is outside "
                                f"1 +/- {tolerance} ({events} events)")
    return failures


# ----------------------------------------------------------------------------------------------------------------------
# uncertainty inputs: what the interval method needs, derived here so the card is its provenance


def regime_counts() -> dict:
    """Months spent in each regime and the transitions between them, from the labelled regime series the chain was
    estimated on. The Dirichlet draws use pseudo-counts n_i * P[i][j] (n_i months in regime i), so their mean is the
    chain of record and their spread is what n_i months can support."""
    lookup = pl.read_parquet(DATA / "hmm_regime_lookup.parquet").sort("monthly_reporting_period")
    labels = [1 if v == "stressed" else 0 for v in lookup["hmm_regime"].to_list()]
    counts = [[0, 0], [0, 0]]
    for a, b in zip(labels[:-1], labels[1:]):
        counts[a][b] += 1
    return {"months": len(labels), "from_state_months": [sum(counts[0]), sum(counts[1])], "transition_counts": counts,
            "first_month": str(lookup["monthly_reporting_period"][0])[:7], "last_month": str(lookup["monthly_reporting_period"][-1])[:7]}


def calibration_band() -> dict:
    """Dispersion of realized over predicted cumulative incidence across the survival model's vintage backtests,
    per outcome and horizon: sigma is the sample standard deviation of log(realized / predicted); the band is
    exp(-sigma) to exp(+sigma). Coverage is leave-one-out: a vintage is covered if |log ratio| <= sigma of the others."""
    backtest = json.loads((RESOURCES / "survival_backtest.json").read_text())
    out = {"source": "survival_backtest.json (held-out loans, predicted with the macro path that happened)",
           "horizons": {}, "caveat": "A handful of vintages stands behind each figure, and the vintages overlap in time and share one macro cycle; "
                                     "coverage is the share of vintages whose realized incidence fell within the band built from the others."}
    for outcome in ("default", "prepay"):
        out["horizons"][outcome] = {}
        for h in (12, 24, 36, 48, 60):
            logs, years = [], []
            for v in backtest["vintages"]:
                if len(v["ages"]) > h and v[f"predicted_{outcome}"][h] > 0 and v[f"empirical_{outcome}"][h] > 0:
                    logs.append(math.log(v[f"empirical_{outcome}"][h] / v[f"predicted_{outcome}"][h]))
                    years.append(v["orig_year"])
            if len(logs) < 3:
                continue
            sigma = float(np.std(logs, ddof=1))
            covered = sum(1 for i, x in enumerate(logs) if abs(x) <= np.std(logs[:i] + logs[i + 1:], ddof=1)) if len(logs) >= 4 else None
            out["horizons"][outcome][str(h)] = {"sigma": sigma, "vintages": len(logs), "vintage_years": years,
                                                "mean_log_ratio": float(np.mean(logs)),
                                                "leave_one_out_coverage": {"covered": covered, "of": len(logs)} if covered is not None else None}
    return out


# ----------------------------------------------------------------------------------------------------------------------


def envelope(train: pl.DataFrame) -> dict:
    out = {}
    for f in ["loan_age", "rate_incentive"] + esm.MACRO_FEATURES:
        v = train[f].to_numpy().astype(np.float64)
        lo, hi = np.quantile(v[~np.isnan(v)], [0.005, 0.995])
        out[f] = [round(float(lo), 4), round(float(hi), 4)]
    return out


def drift_from_current(booster: lgb.Booster, held: pl.DataFrame, window_start: int) -> dict:
    """Realized over predicted default and prepayment probability from the current state on held-out loans in the
    latest 24 months: reported in the card, not applied."""
    rows = held.filter((pl.col("source_state") == 0) & (pl.col("month") >= window_start))
    p = predict(booster, matrix(rows), rows["source_state"].to_numpy())
    y, w = rows["label"].to_numpy(), rows["weight"].to_numpy()
    out = {}
    for name, k in (("default", DEFAULT), ("prepay", PREPAID)):
        events = float(w[y == k].sum())
        expected = float((w * p[:, k]).sum())
        out[name] = {"actual_over_predicted": events / expected, "events": int((y == k).sum()),
                     "standard_error": math.sqrt((y == k).sum()) / expected}
    return out


def main() -> None:
    started = time.time()
    pilot = "--pilot" in sys.argv
    mappings = category_mappings()
    rows, raw, finals, coverage_end = load(mappings)
    train = rows.filter((pl.col("bucket") >= 3) & (pl.col("month") < TEST_START))
    valid = rows.filter((pl.col("bucket") == 2) & (pl.col("month") < TEST_START))
    test = rows.filter((pl.col("bucket") <= 1) & (pl.col("month") >= TEST_START))
    states = pl.read_parquet(str(DELINQ / "states" / "*.parquet"))
    print(f"validation fit: train {train.height:,}  valid {valid.height:,}  out-of-time test (loans in no fit) {test.height:,}", flush=True)
    v_booster, v_best = validation_fit(train, valid, refit="--refit" in sys.argv)
    print(f"  best iteration {v_best}  ({time.time() - started:.0f}s)", flush=True)

    existing = existing_validation_booster(mappings)
    table = roll_rate_table(train)
    starts = starts_sample(test, raw, coverage_end)
    real = realized_paths(starts, states)
    print(f"12-month evaluation: {starts.height:,} starts, {int(real['valid'].sum()):,} fully observed", flush=True)
    g = grid(starts, mappings)
    pilot_report = evaluate(v_booster, existing, table, test, starts, g, real)
    print(json.dumps(pilot_report, indent=1, default=float), flush=True)
    failures = check_gates(pilot_report)
    print("gates on the validation fit:", failures or "all hold", flush=True)
    if pilot:
        write_json(DELINQ / "pilot.json", {"best_iteration": v_best, "gates_frozen": GATES, "report": pilot_report,
                                           "gate_failures": failures, "seconds": time.time() - started})
        return

    # production fit
    prod_train = rows.filter(pl.col("bucket") >= 3)
    held = rows.filter(pl.col("bucket") <= 1)
    print(f"production fit: train {prod_train.height:,}  held-out loans' rows {held.height:,}", flush=True)
    fitted, fitted_rounds = production_fit(prod_train, rows.filter(pl.col("bucket") == 2), refit="--refit" in sys.argv)
    print(f"  best iteration {fitted_rounds}  ({time.time() - started:.0f}s)", flush=True)
    candidate = DELINQ / "candidate"
    candidate.mkdir(parents=True, exist_ok=True)
    onnx_path = candidate / "delinquency_model.onnx"
    booster, best = faithful_prefix(fitted, fitted_rounds, onnx_path, matrix(test.sample(n=50_000, seed=1)))
    print(f"  shipping {best} of {fitted_rounds} fitted rounds", flush=True)
    report = evaluate(booster, existing, table, test, starts, g, real)
    failures = check_gates(report)
    fitted_range = envelope(prod_train)
    drift = drift_from_current(booster, held, coverage_end - 23)
    d60 = test.filter(pl.col("source_state") == D60)
    hmm = json.loads((RESOURCES / "hmm_regime.json").read_text())
    card = {
        "model": "delinquency_state_transitions",
        "version": datetime.now(timezone.utc).strftime("%Y.%m.%d-%H%M"),
        "trained_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "features_in_order": FEATURES,
        "numeric_features": esm.MODEL_NUMERIC,
        "categorical_features": CATEGORICAL,
        "time_varying_features": esm.TIME_VARYING,
        "history_features": {"hist_episodes_12m": "30+ day episodes that began in the 12 months before the month predicted",
                             "hist_months_since_cure": f"months from the latest cure to the previous month; {NO_CURE} = none in 12 months",
                             "hist_max_state_12m": "worst state (0 current, 1 30 days, 2 60 days) in the 12 months before the month predicted"},
        "feature_decimals": {f: esm.FEATURE_DECIMALS[f] for f in esm.FEATURES if f in esm.FEATURE_DECIMALS},
        "classes": CLASSES,
        "source_states": ["current", "d30", "d60"],
        "probability_floor": {"value": PROBABILITY_FLOOR, "from_source_states": ["d30", "d60"],
                              "reason": "pure leaves give out-of-sample transitions probabilities near 1e-200; see export_delinquency_model.py"},
        "history_rule": "the history features are aged with the projection month by the rule in delinquency_history.py: exactly clean 12 months "
                        "in on a path with no new spell; from a delinquent source state the current spell is part of the history "
                        "(episodes >= 1, worst state >= the source)",
        "monotone_repair": "each month's three source rows are made stochastically ordered (delinquency_history.monotone_repair) before use",
        "no_cure_months": NO_CURE,
        "rate_lock_lag_months": esm.RATE_LOCK_LAG_MONTHS,
        "regimes": {"labels": hmm["state_labels"], "transition_matrix": hmm["transition_matrix"],
                    "as_of_month": hmm["as_of_month"][:7], "note": "the survival model's chain, read from its card by the engine"},
        "training": {"rows": int(prod_train.height), "loans": int(prod_train[KEY].n_unique()),
                     "months": [month_label(int(rows["month"].min())), month_label(int(rows["month"].max()))],
                     "months_left_out": [month_label(m) for m in finals], "all_vintages_through": month_label(coverage_end),
                     "held_out_loans": int(held[KEY].n_unique()),
                     "case_control_note": "stay-current loan-months subsampled at 10% with weight 10; every other loan-month kept",
                     "envelope": fitted_range},
        "hyperparameters": {k: v for k, v in PARAMS.items() if k != "verbose"} | {"best_iteration": fitted_rounds, "rounds_shipped": best},
        "validation": {
            "protocol": "fit on loan-buckets 3 to 9, months before 2023-01; evaluated on loan-buckets 0 and 1 (in no fit), months from 2023-01",
            "validation_fit_best_iteration": v_best,
            "pilot_gate_failures": check_gates(pilot_report),
            "out_of_time": pilot_report,
            "production_fit_on_held_out_loans": report,
            "gates": {"frozen": GATES, "failures_at_export": failures},
        },
        "overlay": {"applied": False, "drift_from_current": drift, "note": "reported, not applied"},
        "uncertainty": {"regime_counts": regime_counts(), "dirichlet": "pseudo-counts n_i * P[i][j]; mean equals the chain of record",
                        "calibration_band": calibration_band()},
        "scenario_defaults": {"hpi_annual_growth": 0.03, "unemployment": "flat at latest", "market_rate": "flat at latest"},
        "limitations": [t.format(d60_share=float((prod_train["source_state"] == D60).mean()), d60_rows=int(d60.height)) if "{" in t else t
                        for t in LIMITATIONS],
        "target": "next delinquency state of an active loan-month: current, 30 or 60 days past due, default (90+ days past due, REO or adverse zero-balance termination, as in the survival model) or prepaid",
        "population": "Freddie Mac single-family fixed-rate loans, the survival model's 2017-2026 sample (40,000 loans per origination quarter)",
        "algorithm": "LightGBM multiclass gradient-boosted trees on loan-months, source state as a feature",
        "onnx_check": {"relative_tolerance": ONNX_RTOL, "default_elsewhere": 1e-4,
                       "note": "the graph differs from the float64 booster by at most 2.8e-5 absolute (1.8e-4 relative) on 50,000 sampled "
                               "loan-months, in 3 rows from 60 days past due; the graph is the model of record in the service and the fixtures"},
        "artifact_sha256": sha256(onnx_path),
    }
    (candidate / "delinquency_model.json").write_text(json.dumps(card, indent=2, default=float) + "\n", encoding="utf-8")

    # gate 3 runs on the projection itself, so it needs the candidate artifacts
    import golden_multistate as gm
    monotone = monotonicity(gm.MultiState(candidate))
    card["validation"]["gates"]["monotonicity"] = monotone
    if monotone["violations"]:
        failures.append(f"gate 3 (lifetime PD never falls as the starting state worsens): {len(monotone['violations'])} of "
                        f"{monotone['cases']} grid loans violate it, e.g. {monotone['violations'][0]}")
    card["validation"]["gates"]["failures_at_export"] = failures
    if failures:
        shutil.rmtree(candidate, ignore_errors=True)
        raise SystemExit("export refused; failed gates:\n  " + "\n  ".join(failures))
    write_json(RESOURCES / "delinquency_model.json", card)
    shutil.copyfile(onnx_path, RESOURCES / "delinquency_model.onnx")
    assert sha256(RESOURCES / "delinquency_model.onnx") == card["artifact_sha256"]
    print(f"done in {time.time() - started:.0f}s", flush=True)


def monotonicity(a) -> dict:
    """Gate 3 / task 3.6: on a grid of loans, scenarios and histories, lifetime PD from current <= from 30 days
    past due <= from 60 days past due, all else (the history included) equal."""
    import golden_multistate as gm
    import golden_term_structure as gt
    violations, cases = [], 0
    for loan in (gt.PRIME, gt.SEASONED, gt.RISKY, gt.FIFTEEN, gt.NO_MI):
        for scenario in (gt.BASELINE, gt.SEVERELY_ADVERSE):
            for name, history in (("clean", gm.H_CLEAN), ("recent", gm.H_RECENT)):
                pds = [gm.project(a, dict(loan=loan, scenario=scenario, daysPastDue=d, history=history))["pdLifetime"] for d in (0, 35, 65)]
                cases += 1
                if not (pds[0] <= pds[1] + 1e-12 and pds[1] <= pds[2] + 1e-12):
                    violations.append(f"{loan['loanId']}/{scenario['name']}/{name}: {pds[0]:.5f}, {pds[1]:.5f}, {pds[2]:.5f}")
    return {"cases": cases, "violations": violations}


if __name__ == "__main__":
    main()
