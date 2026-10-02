"""Trains and exports the origination PD model: probability of default within 24 months.

Target and sample (see build_survival_dataset.py for the event definitions):
  * default = first 90+ DPD month, REO, or adverse zero-balance termination, within 24 months of
    origination. A fixed horizon gives the probability an unambiguous meaning; the earlier "ever
    defaulted, among loans observed for 24+ months" label silently dropped every loan that defaulted
    and was liquidated early -- exactly the early-payment defaults a scorecard most needs to learn.
  * a loan is included only if its 24-month outcome is known: it defaulted or paid off within 24
    months, or it was observed for at least 24 months. Loans still censored before 24 months are
    excluded (their outcome is unknown, not "good").

Validation is out-of-time: a model trained on 2017-2020 originations is scored on 2021-2023
originations it never saw. The production model is then refit on every eligible vintage.

Monotone constraints (credit score down, DTI/LTV/CLTV up, more borrowers down) make the model's
behavior defensible to a model-risk reviewer and keep adverse-action reason codes coherent.

Writes to risk-engine/src/main/resources:
  model.onnx, calibration.json, category_mappings.json, feature_order.json   (read by ModelService)
  feature_domain.json, explanation_baseline.json, drift_reference.json, pd_model_card.json
Run from backend/ after build_survival_dataset.py.
"""
from __future__ import annotations

import time
from datetime import datetime, timezone

import lightgbm as lgb
import numpy as np
import polars as pl
from sklearn.metrics import average_precision_score, brier_score_loss, roc_auc_score

from credit_common import (CATEGORICAL, FEATURE_DECIMALS, HARD_BOUNDS, MODEL_NUMERIC, MODEL_ORIGINATION, NUMERIC,
                           RATE_LOCK_LAG_MONTHS, RESOURCES, SURVIVAL, apply_calibration, category_mappings, encode,
                           export_onnx, float32_exact_booster, ks_statistic, loan_bucket, pav_calibration,
                           psi_reference_numeric, quantize, sha256, with_spread, write_json)

HORIZON = 24
MONOTONE = {"credit_score": -1, "original_dti": 1, "original_cltv": 1, "original_ltv": 1, "number_of_borrowers": -1,
            "rate_spread": 1}
# deterministic + force_row_wise: LightGBM otherwise picks its histogram strategy from a timing test at
# start-up, and the two strategies add floats in different orders -- so the same data and seed could give
# a different model file on each run. With these, an export is reproducible bit for bit.
PARAMS = dict(objective="binary", learning_rate=0.05, num_leaves=31, min_child_samples=300, subsample=0.8,
              subsample_freq=1, colsample_bytree=0.9, reg_lambda=1.0, n_estimators=3000, verbose=-1, random_state=42,
              deterministic=True, force_row_wise=True)


def load_eligible() -> pl.DataFrame:
    """Loans whose 24-month outcome is known. The vintage-level observability condition comes first: in a
    vintage that cannot yet have been observed for 24 months, the only loans with a "known" outcome are the
    ones that already defaulted or paid off, so admitting them would select on the outcome itself."""
    loans = pl.read_parquet(str(SURVIVAL / "loans" / "*.parquet"))
    data_end = loans.group_by(["orig_year", "orig_quarter"]).agg(
        (pl.col("orig_month") + pl.col("last_age")).max().alias("data_end"))
    loans = loans.join(data_end, on=["orig_year", "orig_quarter"])
    h = HORIZON
    observable = (pl.col("data_end") - pl.col("orig_month")) >= h
    determined = (
        ((pl.col("event") == "default") & (pl.col("default_age") <= h))
        | ((pl.col("event") == "prepay") & (pl.col("prepay_age") <= h))
        | (pl.col("last_age") >= h)
    )
    eligible = loans.filter(observable & determined).with_columns(
        target=((pl.col("event") == "default") & (pl.col("default_age") <= h)).cast(pl.Int8),
        bucket=loan_bucket(pl.col("loan_sequence_number")),
    )
    return with_spread(eligible, pl.col("orig_month"))


def matrix(df: pl.DataFrame) -> np.ndarray:
    return quantize(df.select(MODEL_ORIGINATION).to_numpy(), MODEL_ORIGINATION)


def fit(train: pl.DataFrame, valid: pl.DataFrame) -> tuple[lgb.Booster, int]:
    """Returns the float32-exact booster (the model of record -- see credit_common) and its size."""
    model = lgb.LGBMClassifier(**PARAMS, monotone_constraints=[MONOTONE.get(f, 0) for f in MODEL_ORIGINATION],
                               monotone_constraints_method="advanced")
    cat_idx = [MODEL_ORIGINATION.index(c) for c in CATEGORICAL]
    model.fit(matrix(train), train["target"].to_numpy(), eval_set=[(matrix(valid), valid["target"].to_numpy())],
              eval_metric="binary_logloss", categorical_feature=cat_idx,
              callbacks=[lgb.early_stopping(100, verbose=False)])
    print(f"  best iteration {model.best_iteration_}", flush=True)
    booster = model.booster_
    booster.free_dataset()
    trimmed = lgb.Booster(model_str=booster.model_to_string(num_iteration=model.best_iteration_))
    return float32_exact_booster(trimmed), int(model.best_iteration_)


def evaluate(y: np.ndarray, p: np.ndarray) -> dict:
    deciles = np.quantile(p, np.linspace(0, 1, 11))
    table = []
    for lo, hi in zip(deciles[:-1], deciles[1:]):
        m = (p >= lo) & (p <= hi)
        if m.any():
            table.append({"pd_from": float(lo), "pd_to": float(hi), "n": int(m.sum()),
                          "predicted": float(p[m].mean()), "actual": float(y[m].mean())})
    return {
        "n": int(len(y)), "default_rate": float(y.mean()), "mean_predicted_pd": float(p.mean()),
        "auc": float(roc_auc_score(y, p)), "gini": float(2 * roc_auc_score(y, p) - 1),
        "pr_auc": float(average_precision_score(y, p)), "ks": ks_statistic(y, p),
        "brier": float(brier_score_loss(y, p)), "deciles": table,
    }


def main() -> None:
    started = time.time()
    mappings = category_mappings()
    # A fixed row order: joins do not guarantee one, and bagging picks rows by position, so without the
    # sort the same data could train a different model on each run.
    data = encode(load_eligible(), mappings).sort("loan_sequence_number")
    print(f"eligible loans: {data.height:,}, 24m default rate {data['target'].mean():.4f}", flush=True)

    # 1) Out-of-time validation: fit on 2017-2020 originations, score 2021-2023.
    past = data.filter(pl.col("orig_year") <= 2020)
    future = data.filter(pl.col("orig_year") >= 2021)
    print("out-of-time validation fit...", flush=True)
    v_model, _ = fit(past.filter(pl.col("bucket") >= 3), past.filter(pl.col("bucket") == 2))
    v_calib = past.filter(pl.col("bucket") <= 1)
    v_xs, v_ys = pav_calibration(v_model.predict(matrix(v_calib)), v_calib["target"].to_numpy())
    oot_p = apply_calibration(v_model.predict(matrix(future)), v_xs, v_ys)
    oot = evaluate(future["target"].to_numpy(), oot_p)
    oot_by_vintage = []
    for year in sorted(future["orig_year"].unique().to_list()):
        mask = (future["orig_year"] == year).to_numpy()
        e = evaluate(future["target"].to_numpy()[mask], oot_p[mask])
        oot_by_vintage.append({"orig_year": year, **{k: e[k] for k in ("n", "default_rate", "mean_predicted_pd", "auc", "ks")}})
    print(f"  OOT AUC {oot['auc']:.4f}  KS {oot['ks']:.4f}  predicted {oot['mean_predicted_pd']:.4f} vs actual {oot['default_rate']:.4f}", flush=True)

    # 2) Production fit on every eligible vintage.
    print("production fit...", flush=True)
    model, best_iteration = fit(data.filter(pl.col("bucket") >= 4), data.filter(pl.col("bucket") == 2))
    calib = data.filter(pl.col("bucket") <= 1)
    xs, ys = pav_calibration(model.predict(matrix(calib)), calib["target"].to_numpy())
    test = data.filter(pl.col("bucket") == 3)
    test_raw = model.predict(matrix(test))
    test_p = apply_calibration(test_raw, xs, ys)
    in_time = evaluate(test["target"].to_numpy(), test_p)
    print(f"  in-time AUC {in_time['auc']:.4f}  KS {in_time['ks']:.4f}", flush=True)

    RESOURCES.mkdir(parents=True, exist_ok=True)
    onnx_path = RESOURCES / "model.onnx"
    export_onnx(model, len(MODEL_ORIGINATION), onnx_path, matrix(test)[:5000])

    write_json(RESOURCES / "calibration.json", {"x_breakpoints": xs, "y_breakpoints": ys})
    write_json(RESOURCES / "category_mappings.json", mappings)
    write_json(RESOURCES / "feature_order.json", {
        "numeric_features": MODEL_NUMERIC, "categorical_features": CATEGORICAL,
        "all_features_in_order": MODEL_ORIGINATION,
        "derived": {"rate_spread": f"original_interest_rate minus the PMMS 30-year rate {RATE_LOCK_LAG_MONTHS} months "
                                   "before the origination month (approximate rate-lock month)"},
        "rate_lock_lag_months": RATE_LOCK_LAG_MONTHS,
        "feature_decimals": {f: FEATURE_DECIMALS[f] for f in MODEL_NUMERIC},
    })
    macro = pl.read_parquet(SURVIVAL / "macro.parquet").filter(pl.col("month") >= 1995 * 12).sort("month")
    rates = pl.read_csv("../data/external/MORTGAGE30US.csv", try_parse_dates=True)
    last = rates["observation_date"].max()
    last_month = last.year * 12 + last.month - 1
    macro = macro.filter(pl.col("month") <= last_month)
    write_json(RESOURCES / "market_rates.json", {
        "series": "Freddie Mac PMMS 30-year fixed rate (MORTGAGE30US), monthly mean of weekly observations",
        "as_of_month": f"{last_month // 12:04d}-{last_month % 12 + 1:02d}",
        "monthly": {f"{m // 12:04d}-{m % 12 + 1:02d}": round(float(r), 4)
                    for m, r in zip(macro["month"].to_list(), macro["rate"].to_list())},
    })

    raw = load_eligible().sort("loan_sequence_number")  # un-encoded values for domain / baseline / drift reference
    domain_numeric = {}
    for f in NUMERIC:
        v = raw[f].drop_nulls().cast(pl.Float64).to_numpy()
        lo, hi = HARD_BOUNDS[f]
        domain_numeric[f] = {"hard_min": lo, "hard_max": hi, "soft_min": float(np.quantile(v, 0.005)),
                             "soft_max": float(np.quantile(v, 0.995)), "median": float(np.median(v))}
    write_json(RESOURCES / "feature_domain.json", {
        "numeric": domain_numeric,
        "categorical": {c: sorted(mappings[c]) for c in CATEGORICAL},
    })
    # Explanation baseline lives in model-feature space (rate_spread, not the raw rate).
    baseline = {f: float(raw[f].drop_nulls().cast(pl.Float64).median()) for f in MODEL_NUMERIC}
    for c in CATEGORICAL:
        baseline[c] = raw[c].drop_nulls().mode().sort()[0]
    write_json(RESOURCES / "explanation_baseline.json", baseline)

    # Drift is monitored on what the model sees: the rate spread, not the raw note rate, which moves
    # with the rate cycle whether or not the borrowers have changed.
    drift = {"numeric": {}, "categorical": {}}
    for f in MODEL_NUMERIC:
        drift["numeric"][f] = psi_reference_numeric(raw[f].cast(pl.Float64).to_numpy())
    for c in CATEGORICAL:
        counts = raw[c].drop_nulls().value_counts()
        total = counts["count"].sum()
        drift["categorical"][c] = {v: n / total for v, n in zip(counts[c].to_list(), counts["count"].to_list())}
    drift["score"] = psi_reference_numeric(test_p)
    write_json(RESOURCES / "drift_reference.json", drift)

    gain = model.feature_importance(importance_type="gain")
    write_json(RESOURCES / "pd_model_card.json", {
        "model": "pd_24m",
        "version": datetime.now(timezone.utc).strftime("%Y.%m.%d-%H%M"),
        "trained_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "horizon_months": HORIZON,
        "target": "90+ days past due, REO acquisition, or adverse zero-balance termination within 24 months of origination",
        "population": "Freddie Mac single-family 30/15-year fixed-rate loans, stratified random sample of 40,000 loans per origination quarter",
        "training_window": {"first_vintage": int(data["orig_year"].min()), "last_vintage": int(data["orig_year"].max())},
        "n_eligible_loans": data.height,
        "default_rate": float(data["target"].mean()),
        "algorithm": "LightGBM gradient-boosted trees with monotone constraints, binned PAV calibration",
        "hyperparameters": {k: v for k, v in PARAMS.items() if k != "verbose"} | {"best_iteration": best_iteration},
        "monotone_constraints": MONOTONE,
        "validation": {"out_of_time": oot | {"train_vintages": "2017-2020", "test_vintages": "2021-2023", "by_vintage": oot_by_vintage},
                       "in_time_holdout": in_time},
        "feature_importance_gain": dict(sorted(((f, float(g / gain.sum())) for f, g in zip(MODEL_ORIGINATION, gain)),
                                                key=lambda kv: -kv[1])),
        "artifact_sha256": sha256(onnx_path),
        "limitations": [
            "Origination-time model: it cannot see the macro path after origination, so realized default rates move with the credit cycle. Use the regime-aware survival model for forward-looking term structures.",
            "Calibrated to the average experience of the 2017-2023 vintages, the 2020 shock included. In a calm period it over-predicts: out of time, on 2021-2023 originations, it predicted 2.8% against 1.8% realized. Ranking (AUC, KS) is unaffected.",
            "Default means the first month 90 or more days past due (or REO, or an adverse termination). COVID-19 forbearance delinquencies count, although most of those loans later cured.",
            "The property's state is a feature. Geography can stand in for protected characteristics, so a lender using the score in credit decisions must test for disparate impact on its own applicants.",
            "Trained on agency-conforming fixed-rate mortgages; not validated for other products.",
        ],
    })
    print(f"done in {time.time() - started:.0f}s", flush=True)


if __name__ == "__main__":
    main()
