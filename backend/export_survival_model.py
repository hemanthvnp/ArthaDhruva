"""Trains and exports the competing-risks survival model behind PD term structures and lifetime ECL.

Discrete-time multinomial hazard: for each loan-month at risk the model gives
  P(default this month), P(prepay this month), P(continue)
from the origination features and that month's loan age, macro regime (calm / stressed, from the HMM),
rate incentive (note rate minus the market 30-year rate), state unemployment and house prices, and the
mark-to-market LTV. Default and prepayment compete: a loan that prepays can no longer default, which is
why lifetime PD must come from both hazards together.

The Java side (survival.TermStructureEngine) turns hazards into, per future month t,
  S(t)     = prod_{s<=t} (1 - h_d(s) - h_p(s))       probability the loan is still active
  CIF_d(t) = sum_{s<=t} S(s-1) h_d(s)                 cumulative probability of default
and takes the exact expectation over future regime paths by forward recursion over the regime
Markov chain.

Protocol
  1. Validation fit on loan-months before 2023-01; out-of-time metrics on 2023-01 onwards.
  2. Drift overlay, validated out-of-time: the ratio of realized to predicted hazard over the first 18
     out-of-time months is applied to the months after them. A cause is eligible for the overlay only if
     that moved its later months closer to reality (it does for default, whose drift persists; it does
     not for prepayment, whose drift follows the rate cycle and reverses).
  3. Production fit on every month (loans held out by id for early stopping and for step 4). The overlay
     is re-estimated on held-out loans over the latest 24 months and applied only where the cause is
     eligible and the ratio differs from 1 by more than two standard errors.
  4. Backtests on the held-out loans: monthly predicted vs realized hazards, calibration by risk decile,
     and per origination year the predicted cumulative incidence (using the macro path that actually
     happened) against the empirical Aalen-Johansen estimate.

Writes survival_model.onnx, survival_model.json, survival_backtest.json and macro_state.json to the
resources directory. Run from backend/ after build_survival_dataset.py and fetch_macro.py.
"""
from __future__ import annotations

import json
import math
import time
from datetime import datetime, timezone

import lightgbm as lgb
import numpy as np
import polars as pl
from sklearn.metrics import log_loss, roc_auc_score

from credit_common import (CATEGORICAL, FEATURE_DECIMALS, MACRO_FEATURES, MODEL_NUMERIC, MODEL_ORIGINATION,
                           ORIGINATION, RATE_LOCK_LAG_MONTHS, RESOURCES, SURVIVAL, category_mappings,
                           decile_table, encode, export_onnx, float32_exact_booster, loan_bucket, quantize, sha256,
                           with_macro, with_spread, with_state_macro, write_json)

# Two further path features were tried and rejected on out-of-time evidence (experiments/survival_path_features.py):
# a calendar-month feature absorbed the June-July 2020 forbearance spike as a permanent "June effect", and a
# months-in-the-money (burnout) count did not improve the fit and learned a ramp, not burnout.
TIME_VARYING = ["loan_age", "regime_stressed", "rate_incentive"] + MACRO_FEATURES
FEATURES = MODEL_ORIGINATION + TIME_VARYING
CLASSES = ["continue", "default", "prepay"]
CAUSES = {"default": 1, "prepay": 2}
TEST_START = 2023 * 12            # month index of 2023-01
PROTOCOL_OVERLAY_MONTHS = 18      # out-of-time months the validation run estimates the overlay on
OVERLAY_MONTHS = 24               # window of recent experience behind the production overlay
# deterministic + force_row_wise make an export reproducible bit for bit (see export_model.py).
PARAMS = dict(objective="multiclass", num_class=3, learning_rate=0.05, num_leaves=63, min_child_samples=500,
              subsample=0.8, subsample_freq=1, colsample_bytree=0.9, reg_lambda=1.0, n_estimators=3000,
              verbose=-1, random_state=7, deterministic=True, force_row_wise=True)


LIMITATIONS = [
    "Default means the first month 90 or more days past due (or REO, or an adverse termination). Most such loans later cure or are repaid from a sale; that is why loss given default is small, and why COVID-19 forbearance shows up as a wave of defaults.",
    "The data (2017-2026) contains no housing downturn: over the training rows the 12-month change in state house prices was never below about -1%. The hazards' response to falling prices is therefore held at the edge of that range; in a house-price scenario the extra loss comes from re-marking the collateral, not from the hazard model. The engine reports every month in which a driver leaves the training range.",
    "Loans are observed for at most about nine years. Beyond that the hazard's dependence on loan age is held at its last fitted level, so lifetime figures for long-dated loans rest on that assumption.",
    "One stress episode (2020-06 to 2022-06) defines the stressed regime, and the regime chain is estimated from 99 months: an expected stress spell of about 37 months and a long-run stressed share of about 35% are uncertain, and the baseline lifetime PD inherits that uncertainty.",
    "The model conditions on origination attributes and macro drivers, not on a loan's current delinquency status, so it understates the risk of a loan that is already past due.",
    "The property's state is a feature. Geography can stand in for protected characteristics, so a lender using these figures in credit decisions must test for disparate impact on its own portfolio.",
    "Prepayment drift is not corrected: it follows the rate cycle and reverses, and correcting it made later months worse in the out-of-time protocol.",
    "Trained on agency-conforming fixed-rate mortgages; not validated for other products.",
]


def month_label(m: int) -> str:
    return f"{m // 12:04d}-{m % 12 + 1:02d}"


def matrix(df: pl.DataFrame) -> np.ndarray:
    return quantize(df.select(FEATURES).to_numpy(), FEATURES)


def featurize(df: pl.DataFrame, orig_month: pl.Expr, mappings: dict) -> pl.DataFrame:
    """Raw loan-months (origination fields, loan_age, month) to model features. The joins do not preserve
    row order; callers that need one sort afterwards."""
    df = with_state_macro(with_spread(df, orig_month), orig_month)
    return encode(with_macro(df), mappings).with_columns(loan_age=pl.col("loan_age").cast(pl.Float32))


def release_final_months(loans: pl.DataFrame) -> list[int]:
    """The last reporting month of each data release in the sample (panels of different vintages end at
    different releases). Zero-balance events are not reliably dated in a release's final month -- the
    2017-19 panels show no prepayments at all in 2026-03 and twice the usual number in 2025-09, the final
    month of the release before -- so those calendar months are left out of fitting and evaluation."""
    ends = loans.group_by(["orig_year", "orig_quarter"]).agg(end=(pl.col("orig_month") + pl.col("last_age")).max())
    return sorted(int(m) for m in ends["end"].unique().to_list())


def fit(train: pl.DataFrame, valid: pl.DataFrame) -> tuple[lgb.Booster, int]:
    """Returns the float32-exact booster (the model of record -- see credit_common) and its size."""
    model = lgb.LGBMClassifier(**PARAMS)
    model.fit(matrix(train), train["label"].to_numpy(), sample_weight=train["weight"].to_numpy(),
              eval_set=[(matrix(valid), valid["label"].to_numpy())], eval_sample_weight=[valid["weight"].to_numpy()],
              eval_metric="multi_logloss", categorical_feature=[FEATURES.index(c) for c in CATEGORICAL],
              callbacks=[lgb.early_stopping(50, verbose=False), lgb.log_evaluation(100)])
    best = int(model.best_iteration_)
    booster = float32_exact_booster(lgb.Booster(model_str=model.booster_.model_to_string(num_iteration=best)))
    return booster, best


def stressed_periods(macro: pl.DataFrame, through: int) -> list[list[str]]:
    """Contiguous runs of the stressed regime up to month `through`, as [first, last] month labels. The
    Java engine uses them to rebuild the regime a seasoned loan was originated in."""
    months = macro.filter((pl.col("stressed") == 1) & (pl.col("month") <= through)).sort("month")["month"].to_list()
    runs, start, prev = [], None, None
    for m in months:
        if prev is None or m != prev + 1:
            if start is not None:
                runs.append([month_label(start), month_label(prev)])
            start = m
        prev = m
    if start is not None:
        runs.append([month_label(start), month_label(prev)])
    return runs


def envelope(train: pl.DataFrame) -> dict[str, list[float]]:
    """The range each time-varying driver spans in the training rows (0.5th to 99.5th percentile). A tree
    model holds its response constant outside the range it was fitted on, so the engine reports when a
    scenario path leaves this envelope instead of implying the model has an opinion there."""
    out = {}
    for f in ["loan_age", "rate_incentive"] + MACRO_FEATURES:
        v = train[f].to_numpy().astype(np.float64)
        lo, hi = np.quantile(v[~np.isnan(v)], [0.005, 0.995])
        out[f] = [round(float(lo), 4), round(float(hi), 4)]
    return out


def drift(probs: np.ndarray, y: np.ndarray, w: np.ndarray) -> dict:
    """Realized over predicted hazard per cause, with its standard error. Events carry weight 1 (only
    no-event rows are subsampled), so the realized count is Poisson and se = sqrt(events) / expected."""
    out = {}
    for cause, k in CAUSES.items():
        events = float((y == k).sum())
        expected = float((w * probs[:, k]).sum())
        out[cause] = {"events": int(events), "expected": expected, "ratio": events / expected,
                      "standard_error": math.sqrt(events) / expected}
    return out


def scaled(probs: np.ndarray, scalars: dict) -> np.ndarray:
    out = probs.astype(np.float64, copy=True)
    for cause, k in CAUSES.items():
        out[:, k] *= scalars[cause]
    out[:, 0] = np.clip(1.0 - out[:, 1] - out[:, 2], 1e-9, 1.0)
    return out


def calibration_report(probs: np.ndarray, y: np.ndarray, w: np.ndarray) -> dict:
    report = {"weighted_log_loss": float(log_loss(y, probs / probs.sum(axis=1, keepdims=True), sample_weight=w, labels=[0, 1, 2]))}
    for cause, k in CAUSES.items():
        event = (y == k).astype(np.float64)
        predicted, actual = float(np.average(probs[:, k], weights=w)), float(np.average(event, weights=w))
        report[cause] = {"predicted": predicted, "actual": actual, "actual_over_predicted": actual / predicted,
                         "deciles": decile_table(probs[:, k], event, w),
                         "top_percentile": decile_table(probs[:, k], event, w, groups=100)[-1]}
    return report


def aalen_johansen(first_age: np.ndarray, exit_age: np.ndarray, event: np.ndarray, horizon: int):
    """Empirical cumulative incidence of default and prepayment with right-censoring (discrete time)."""
    ages = np.arange(horizon + 1)
    entries = np.bincount(np.clip(first_age, 0, horizon), minlength=horizon + 2)[: horizon + 1]
    exits = np.bincount(np.clip(exit_age, 0, horizon + 1), minlength=horizon + 2)
    in_window = exit_age <= horizon
    d = np.bincount(exit_age[in_window & (event == "default")], minlength=horizon + 1)[: horizon + 1]
    p = np.bincount(exit_age[in_window & (event == "prepay")], minlength=horizon + 1)[: horizon + 1]
    at_risk = np.cumsum(entries) - np.concatenate(([0], np.cumsum(exits)[:horizon]))
    surv, cif_d, cif_p = 1.0, [], []
    acc_d = acc_p = 0.0
    for a in ages:
        n = at_risk[a]
        if n > 0:
            acc_d += surv * d[a] / n
            acc_p += surv * p[a] / n
            surv *= 1 - (d[a] + p[a]) / n
        cif_d.append(acc_d)
        cif_p.append(acc_p)
    return cif_d, cif_p


def main() -> None:
    started = time.time()
    mappings = category_mappings()
    loans = pl.read_parquet(str(SURVIVAL / "loans" / "*.parquet")).with_columns(
        bucket=loan_bucket(pl.col("loan_sequence_number"))).sort("loan_sequence_number")
    finals = release_final_months(loans)
    coverage_end = finals[0] - 1  # last month in which every vintage reports and events are reliably dated
    rows = pl.read_parquet(str(SURVIVAL / "rows" / "*.parquet"))
    before = rows.height
    rows = rows.filter(~pl.col("month").is_in(finals))
    # A fixed row order: joins do not guarantee one, and bagging picks rows by position, so without the
    # sort the same data could train a different model on each run.
    rows = featurize(rows, pl.col("month") - pl.col("loan_age").cast(pl.Int32), mappings).with_columns(
        bucket=loan_bucket(pl.col("loan_sequence_number"))).sort(["loan_sequence_number", "loan_age"])
    print(f"loan-month rows: {rows.height:,} ({before - rows.height:,} in release-final months "
          f"{[month_label(m) for m in finals]} left out); every vintage reports through {month_label(coverage_end)}", flush=True)

    # 1) Validation fit: nothing from 2023 onwards is seen.
    past = rows.filter(pl.col("month") < TEST_START)
    test = rows.filter(pl.col("month") >= TEST_START)
    v_train = past.filter(pl.col("bucket") >= 3)
    print(f"validation fit: train {v_train.height:,}  out-of-time test {test.height:,}", flush=True)
    v_booster, v_best = fit(v_train, past.filter(pl.col("bucket") == 2))
    print(f"  best iteration {v_best}  ({time.time() - started:.0f}s)", flush=True)

    y, w = test["label"].to_numpy(), test["weight"].to_numpy()
    probs = v_booster.predict(matrix(test))
    # Naive benchmark: a hazard table by loan age only.
    age_table = (v_train.group_by("loan_age").agg(
        [(pl.col("weight") * (pl.col("label") == k)).sum().alias(f"c{k}") for k in range(3)])
        .with_columns(total=pl.col("c0") + pl.col("c1") + pl.col("c2")))
    overall = v_train.select([(pl.col("weight") * (pl.col("label") == k)).sum().alias(f"c{k}") for k in range(3)]).to_numpy()[0]
    overall = overall / overall.sum()
    lookup = {int(a): np.array([c0, c1, c2]) / t for a, c0, c1, c2, t in age_table.iter_rows()}
    base = np.array([lookup.get(int(a), overall) for a in test["loan_age"].to_list()])
    metrics = {
        "rows": int(test.height),
        "weighted_log_loss": float(log_loss(y, probs, sample_weight=w, labels=[0, 1, 2])),
        "age_only_baseline_log_loss": float(log_loss(y, base, sample_weight=w, labels=[0, 1, 2])),
        "default_auc": float(roc_auc_score(y == 1, probs[:, 1], sample_weight=w)),
        "prepay_auc": float(roc_auc_score(y == 2, probs[:, 2], sample_weight=w)),
        "test_months": [month_label(TEST_START), month_label(int(test["month"].max()))],
        "train_months": [month_label(int(rows["month"].min())), month_label(TEST_START - 1)],
        "best_iteration": v_best,
    }
    print(json.dumps(metrics, indent=2), flush=True)

    # 2) Does an overlay estimated on recent experience carry forward? Estimate it on the first
    #    out-of-time months and judge it on the later ones, which neither the trees nor it have seen.
    month = test["month"].to_numpy()
    fit_on = month < TEST_START + PROTOCOL_OVERLAY_MONTHS
    judge = (month >= TEST_START + PROTOCOL_OVERLAY_MONTHS) & (month <= coverage_end)
    v_drift = drift(probs[fit_on], y[fit_on], w[fit_on])
    v_scalars = {cause: v_drift[cause]["ratio"] for cause in CAUSES}
    judged = {"before": calibration_report(probs[judge], y[judge], w[judge]),
              "after": calibration_report(scaled(probs[judge], v_scalars), y[judge], w[judge])}
    eligible = {cause: abs(math.log(judged["after"][cause]["actual_over_predicted"]))
                < abs(math.log(judged["before"][cause]["actual_over_predicted"])) for cause in CAUSES}
    protocol = {
        "estimated_on": [month_label(TEST_START), month_label(TEST_START + PROTOCOL_OVERLAY_MONTHS - 1)],
        "judged_on": [month_label(TEST_START + PROTOCOL_OVERLAY_MONTHS), month_label(coverage_end)],
        "weighted_log_loss": {s: judged[s]["weighted_log_loss"] for s in judged},
    }
    for cause in CAUSES:
        protocol[cause] = {"overlay": v_scalars[cause],
                           "actual_over_predicted_before": judged["before"][cause]["actual_over_predicted"],
                           "actual_over_predicted_after": judged["after"][cause]["actual_over_predicted"],
                           "eligible": eligible[cause]}
        print(f"  overlay protocol, {cause}: x{v_scalars[cause]:.3f} takes later months from "
              f"{protocol[cause]['actual_over_predicted_before']:.3f} to {protocol[cause]['actual_over_predicted_after']:.3f}"
              f" -> {'eligible' if eligible[cause] else 'not eligible'}", flush=True)
    validation_deciles = {cause: judged["before"][cause]["deciles"] for cause in CAUSES}
    del v_booster, probs, base, past, v_train

    # 3) Production fit on every month; loans in buckets 0-1 stay out of it entirely.
    train = rows.filter(pl.col("bucket") >= 3)
    held = rows.filter(pl.col("bucket") <= 1)
    print(f"production fit: train {train.height:,}  held-out loans' rows {held.height:,}", flush=True)
    booster, best_iteration = fit(train, rows.filter(pl.col("bucket") == 2))
    print(f"  best iteration {best_iteration}  ({time.time() - started:.0f}s)", flush=True)
    booster.save_model(str(SURVIVAL / "survival_booster.txt"))

    held_probs = booster.predict(matrix(held))
    held_y, held_w, held_month = held["label"].to_numpy(), held["weight"].to_numpy(), held["month"].to_numpy()
    window = (held_month > coverage_end - OVERLAY_MONTHS) & (held_month <= coverage_end)
    recent = drift(held_probs[window], held_y[window], held_w[window])
    overlay = {}
    for cause in CAUSES:
        r = recent[cause]
        significant = abs(r["ratio"] - 1.0) > 2.0 * r["standard_error"]
        applied = eligible[cause] and significant
        overlay[cause] = {"scalar": round(r["ratio"], 4) if applied else 1.0, "applied": applied,
                          "actual_over_predicted": r["ratio"], "standard_error": r["standard_error"],
                          "events": r["events"], "eligible": eligible[cause]}
        print(f"  production overlay, {cause}: realized/predicted {r['ratio']:.3f} +/- {r['standard_error']:.3f} "
              f"({r['events']} events) -> {'x' + format(overlay[cause]['scalar'], '.4f') if applied else 'not applied'}", flush=True)
    held_report = calibration_report(held_probs[window], held_y[window], held_w[window])

    # 4a) Monthly hazards on the held-out loans.
    monthly = (
        held.select(["month", "label", "weight"])
        .with_columns(pd_=pl.Series(held_probs[:, 1]), pp_=pl.Series(held_probs[:, 2]))
        .group_by("month").agg(
            exposure=pl.col("weight").sum(),
            predicted_default=(pl.col("weight") * pl.col("pd_")).sum() / pl.col("weight").sum(),
            actual_default=(pl.col("weight") * (pl.col("label") == 1)).sum() / pl.col("weight").sum(),
            predicted_prepay=(pl.col("weight") * pl.col("pp_")).sum() / pl.col("weight").sum(),
            actual_prepay=(pl.col("weight") * (pl.col("label") == 2)).sum() / pl.col("weight").sum(),
        ).filter(pl.col("exposure") > 1_000).sort("month")
    )
    monthly_series = [{"month": month_label(int(r["month"])),
                       # after this month only the vintages of the latest release report
                       "all_vintages": int(r["month"]) <= coverage_end,
                       **{k: float(r[k]) for k in monthly.columns if k != "month"}}
                      for r in monthly.iter_rows(named=True)]

    # 4b) Vintage backtest on the held-out loans: predicted vs empirical cumulative incidence.
    data_end = loans.group_by(["orig_year", "orig_quarter"]).agg(
        (pl.col("orig_month") + pl.col("last_age")).max().alias("data_end"))
    holdout = loans.filter(pl.col("bucket") <= 1).join(data_end, on=["orig_year", "orig_quarter"]).with_columns(
        observable=(pl.col("data_end") - pl.col("orig_month"))).sort("loan_sequence_number")
    vintages = []
    for year in range(2017, 2025):
        cohort = holdout.filter(pl.col("orig_year") == year)
        if cohort.height < 1000:
            continue
        horizon = int(np.quantile(cohort["observable"].to_numpy(), 0.05))
        emp_d, emp_p = aalen_johansen(cohort["first_age"].to_numpy(), cohort["exit_age"].to_numpy(),
                                      cohort["event"].to_numpy(), horizon)
        sample = cohort.sample(n=min(4000, cohort.height), seed=year)
        grid = (sample.select(["loan_sequence_number", "orig_month", *ORIGINATION])
                .join(pl.DataFrame({"loan_age": np.arange(horizon + 1, dtype=np.int32)}), how="cross")
                .with_columns(month=(pl.col("orig_month") + pl.col("loan_age")).cast(pl.Int32)))
        # The reshape below needs each loan's ages contiguous and in order.
        grid = featurize(grid, pl.col("orig_month"), mappings).sort(["loan_sequence_number", "loan_age"])
        h = booster.predict(matrix(grid)).reshape(sample.height, horizon + 1, 3)
        alive = np.cumprod(1 - h[:, :, 1] - h[:, :, 2], axis=1)
        alive_before = np.concatenate([np.ones((sample.height, 1)), alive[:, :-1]], axis=1)
        pred_d = np.cumsum(alive_before * h[:, :, 1], axis=1).mean(axis=0)
        pred_p = np.cumsum(alive_before * h[:, :, 2], axis=1).mean(axis=0)
        vintages.append({"orig_year": year, "n_loans": int(cohort.height), "ages": list(range(horizon + 1)),
                         "predicted_default": pred_d.tolist(), "empirical_default": emp_d,
                         "predicted_prepay": pred_p.tolist(), "empirical_prepay": emp_p})
        print(f"vintage {year}: horizon {horizon}m  lifetime default predicted {pred_d[-1]:.4f} "
              f"vs empirical {emp_d[-1]:.4f}  prepay {pred_p[-1]:.4f} vs {emp_p[-1]:.4f}", flush=True)

    onnx_path = RESOURCES / "survival_model.onnx"
    export_onnx(booster, len(FEATURES), onnx_path, matrix(test.sample(n=50_000, seed=1)))

    hmm = json.loads((RESOURCES / "hmm_regime.json").read_text())
    macro = pl.read_parquet(SURVIVAL / "macro.parquet")
    regime_as_of = int(hmm["as_of_month"][:4]) * 12 + int(hmm["as_of_month"][5:7]) - 1
    overlay_window = [month_label(coverage_end - OVERLAY_MONTHS + 1), month_label(coverage_end)]
    write_json(RESOURCES / "survival_model.json", {
        "model": "competing_risks_survival",
        "version": datetime.now(timezone.utc).strftime("%Y.%m.%d-%H%M"),
        "trained_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "features_in_order": FEATURES,
        "numeric_features": MODEL_NUMERIC,
        "categorical_features": CATEGORICAL,
        "time_varying_features": TIME_VARYING,
        "feature_decimals": {f: FEATURE_DECIMALS[f] for f in FEATURES if f in FEATURE_DECIMALS},
        "classes": CLASSES,
        "rate_lock_lag_months": RATE_LOCK_LAG_MONTHS,
        "regimes": {"labels": hmm["state_labels"], "transition_matrix": hmm["transition_matrix"],
                    "current_state_index": hmm["current_state_index"], "as_of_month": hmm["as_of_month"][:7],
                    "stressed_periods": stressed_periods(macro, regime_as_of)},
        "training": {"rows": int(train.height), "loans": int(train["loan_sequence_number"].n_unique()),
                     "months": [month_label(int(rows["month"].min())), month_label(int(rows["month"].max()))],
                     "months_left_out": [month_label(m) for m in finals],
                     "all_vintages_through": month_label(coverage_end),
                     "held_out_loans": int(held["loan_sequence_number"].n_unique()),
                     "case_control_note": "no-event loan-months subsampled at 10% with weight 10",
                     "envelope": envelope(train)},
        "hyperparameters": {k: v for k, v in PARAMS.items() if k != "verbose"} | {"best_iteration": best_iteration},
        "validation": {"out_of_time": metrics, "overlay_protocol": protocol},
        "overlay": {
            "hazard_scalars": {cause: overlay[cause]["scalar"] for cause in CAUSES},
            "window": overlay_window,
            "default": overlay["default"], "prepay": overlay["prepay"],
            "rule": "realized / predicted hazard on held-out loans over the window; applied to a cause only if the "
                    "out-of-time protocol showed the overlay carries forward for it and the ratio is more than two "
                    "standard errors from 1",
        },
        "scenario_defaults": {"hpi_annual_growth": 0.03, "unemployment": "flat at latest", "market_rate": "flat at latest"},
        "limitations": LIMITATIONS,
        "target": "first month 90+ days past due, REO acquisition or adverse zero-balance termination (default); zero-balance payoff (prepayment)",
        "population": "Freddie Mac single-family fixed-rate loans, stratified random sample of 40,000 loans per origination quarter, 2017-2026",
        "algorithm": "LightGBM multiclass gradient-boosted trees on loan-months (discrete-time competing-risks hazard)",
        "artifact_sha256": sha256(onnx_path),
    })
    write_json(RESOURCES / "survival_backtest.json", {
        "monthly": monthly_series, "vintages": vintages,
        "calibration": {
            "held_out_window": overlay_window,
            "held_out": {cause: {"deciles": held_report[cause]["deciles"], "top_percentile": held_report[cause]["top_percentile"],
                                 "actual_over_predicted": held_report[cause]["actual_over_predicted"]} for cause in CAUSES},
            "out_of_time_window": protocol["judged_on"],
            "out_of_time": validation_deciles,
        },
    })

    # Macro snapshot the Java scenario engine starts from: each state's monthly HPI (for re-marking a
    # loan's value from its origination month) and unemployment (for levels and 12-month changes).
    macro_state = pl.read_parquet(SURVIVAL / "macro_state.parquet").sort(["state", "month"])
    first, last = int(macro_state["month"].min()), int(macro_state["month"].max())
    states = {}
    for st in macro_state["state"].unique().sort().to_list():
        s = macro_state.filter(pl.col("state") == st)
        states[st] = {"hpi": [round(float(v), 3) for v in s["hpi"].to_list()],
                      "unemployment": [round(float(v), 2) for v in s["unemployment"].to_list()],
                      "hpi_source": s["hpi_source"][0], "unemployment_source": s["unemployment_source"][0]}
    write_json(RESOURCES / "macro_state.json", {
        "sources": {"hpi": "FHFA all-transactions house price index by state (FRED <ST>STHPI), quarterly, log-linear monthly interpolation",
                    "unemployment": "Unemployment rate by state, seasonally adjusted (FRED <ST>UR)"},
        "first_month": month_label(first), "last_month": month_label(last), "states": states,
    })
    print(f"done in {time.time() - started:.0f}s", flush=True)


if __name__ == "__main__":
    main()
