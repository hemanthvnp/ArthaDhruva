"""Out-of-time validation and model card for the early-warning model (early_warning_model.onnx).

The model (export_early_warning_model.py) answers: for a currently performing loan (0 days past due, never delinquent before),
what is the probability that it goes 30+ days late within the next three months? LightGBM on the v3 feature set, trained on a
10:1 downsampled target and calibrated to the natural rate by an isotonic map fitted on a separate holdout. It had no card and no
out-of-time result.

This script does not retrain or rewrite the artifact. It refits the same specification on the months before 2023-01 (loans in the
export's 80% fitting split only) and judges that fit on the months from 2023-01, for loans it never saw (out of time and out of
sample), and the isotonic map the same way: fitted on the natural-rate holdout before 2023-01 and judged on the holdout from
2023-01. It also scores the shipped artifact on the natural-rate holdout, whose loans are in no fit (not out of time: the shipped
model's fitting rows run to 2025). It writes early_warning_card.json, whose checksums of the artifact and its calibration the
service verifies at start-up (governance/ModelRegistry). Run from backend/.
"""
from __future__ import annotations

import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path

import lightgbm as lgb
import numpy as np
import onnxruntime as ort
import pandas as pd
import polars as pl
from sklearn.isotonic import IsotonicRegression
from sklearn.metrics import average_precision_score, roc_auc_score
from sklearn.model_selection import train_test_split

RESOURCES = Path("risk-engine/src/main/resources")
TRAIN = Path("../data/processed/early_warning_delinquency_v3.parquet")
HOLDOUT = Path("../data/processed/early_warning_calibration_holdout_v3.parquet")
CUT = pd.Timestamp("2023-01-01")
TARGET = "target"


def load():
    order = json.loads((RESOURCES / "early_warning_feature_order.json").read_text())
    mappings = json.loads((RESOURCES / "early_warning_category_mappings.json").read_text())
    features = order["all_features_in_order"]
    frames = {}
    for name, path in (("train", TRAIN), ("holdout", HOLDOUT)):
        raw = pl.read_parquet(path)
        pdf = raw.select(["loan_sequence_number", "monthly_reporting_period", *features, TARGET]).to_pandas()
        pdf[TARGET] = pdf[TARGET].astype(int)
        for col in order["categorical_features"]:
            pdf[col] = pdf[col].map(mappings[col]).fillna(-1).astype("float32")
        for col in order["numeric_features"]:
            pdf[col] = pdf[col].astype("float32")
        for col in order["boolean_features"]:
            pdf[col] = pdf[col].fillna(False).astype("float32")
        frames[name] = pdf
    return order, features, frames["train"], frames["holdout"]


def positive_probability(session, xs: np.ndarray) -> np.ndarray:
    """Probability of the positive class from the exported graph, in batches (the graph returns a matrix or a list of dicts)."""
    out = []
    for i in range(0, len(xs), 200_000):
        probs = session.run(None, {"input": xs[i:i + 200_000]})[1]
        out.append(np.asarray(probs)[:, 1] if isinstance(probs, np.ndarray) else np.array([d[1] for d in probs]))
    return np.concatenate(out)


def decile_table(p: np.ndarray, y: np.ndarray, groups: int = 10) -> list[dict]:
    order = np.argsort(p, kind="stable")
    return [{"decile": i + 1, "loans": int(len(g)), "predicted": float(p[g].mean()), "actual": float(y[g].mean())}
            for i, g in enumerate(np.array_split(order, groups))]


def scores(p: np.ndarray, y: np.ndarray) -> dict:
    return {"rows": int(len(y)), "base_rate": float(y.mean()), "mean_predicted": float(p.mean()),
            "predicted_over_actual": float(p.mean() / y.mean()) if y.mean() > 0 else None,
            "roc_auc": float(roc_auc_score(y, p)), "pr_auc": float(average_precision_score(y, p)), "deciles": decile_table(p, y)}


def main() -> None:
    order, features, train, holdout = load()
    print(f"training set {len(train):,} rows ({train[TARGET].mean():.2%} positive, downsampled); natural-rate holdout {len(holdout):,} ({holdout[TARGET].mean():.3%})")

    # the export's 80/20 split by loan, so "loans it never saw" means the same loans
    loans = pl.read_parquet(TRAIN, columns=["loan_sequence_number"])["loan_sequence_number"].unique().to_list()
    fit_loans, _ = train_test_split(loans, test_size=0.2, random_state=42)
    fit_loans = set(fit_loans)
    in_fit = train["loan_sequence_number"].isin(fit_loans)
    before = train["monthly_reporting_period"] < CUT
    fit_rows, judge_rows = train[in_fit & before], train[~in_fit & ~before]
    print(f"fit {len(fit_rows):,} rows of fitting loans before {CUT.date()}; judge {len(judge_rows):,} rows of loans never seen from {CUT.date()}")

    model = lgb.LGBMClassifier(n_estimators=300, learning_rate=0.05, num_leaves=31, random_state=42, verbose=-1)
    model.fit(fit_rows[features], fit_rows[TARGET], categorical_feature=order["categorical_features"])

    judged_raw = model.predict_proba(judge_rows[features])[:, 1]
    downsampled = {"rows": int(len(judge_rows)), "base_rate": float(judge_rows[TARGET].mean()),
                   "roc_auc": float(roc_auc_score(judge_rows[TARGET], judged_raw)),
                   "note": "target rate is the 10:1 downsampled one, so only the ranking (AUC) is meaningful here"}

    h_before = holdout["monthly_reporting_period"] < CUT
    h_fit, h_judge = holdout[h_before], holdout[~h_before]
    raw_fit = model.predict_proba(h_fit[features])[:, 1]
    raw_judge = model.predict_proba(h_judge[features])[:, 1]
    iso = IsotonicRegression(out_of_bounds="clip").fit(raw_fit, h_fit[TARGET].to_numpy())
    natural = {"refit_raw": scores(raw_judge, h_judge[TARGET].to_numpy()),
               "refit_calibrated": scores(iso.predict(raw_judge), h_judge[TARGET].to_numpy()),
               "calibration_fitted_on": int(len(h_fit)), "judged_on": int(len(h_judge)),
               "months": [str(h_judge["monthly_reporting_period"].min().date()), str(h_judge["monthly_reporting_period"].max().date())]}
    for k in ("refit_raw", "refit_calibrated"):
        print(k, {a: round(b, 4) for a, b in natural[k].items() if isinstance(b, float)})

    # the shipped artifact, on the natural-rate holdout (its loans are in no fit; its fitting rows do run past the cut)
    session = ort.InferenceSession(str(RESOURCES / "early_warning_model.onnx"))
    xs = holdout[features].to_numpy(dtype=np.float32)
    shipped_raw = positive_probability(session, xs)
    calibration = json.loads((RESOURCES / "early_warning_calibration.json").read_text())
    shipped_cal = np.interp(shipped_raw, calibration["x_breakpoints"], calibration["y_breakpoints"])
    y_h = holdout[TARGET].to_numpy()
    after = ~h_before.to_numpy()
    shipped = {"all_months": {"raw": scores(shipped_raw, y_h), "calibrated": scores(shipped_cal, y_h)},
               "from_2023_01": {"raw": scores(shipped_raw[after], y_h[after]), "calibrated": scores(shipped_cal[after], y_h[after])},
               "note": "the natural-rate holdout's loans are in no fit, but the calibration map was fitted on half of this same holdout and the "
                       "model's fitting rows run to 2025, so this is out of sample and not out of time"}
    print("shipped, holdout from 2023-01:", {a: round(b, 4) for a, b in shipped["from_2023_01"]["calibrated"].items() if isinstance(b, float)})

    onnx_path, calib_path = RESOURCES / "early_warning_model.onnx", RESOURCES / "early_warning_calibration.json"
    card = {
        "model": "early_warning",
        "version": datetime.now(timezone.utc).strftime("%Y.%m.%d"),
        "validated_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "artifact": "early_warning_model.onnx",
        "artifact_sha256": hashlib.sha256(onnx_path.read_bytes()).hexdigest(),
        "calibration": "early_warning_calibration.json",
        "calibration_sha256": hashlib.sha256(calib_path.read_bytes()).hexdigest(),
        "algorithm": "LightGBM (300 trees, 31 leaves) on loan-months, isotonic calibration fitted on a natural-rate holdout",
        "target": "a currently performing loan (0 days past due, never delinquent before) goes 30+ days past due within the next 3 months",
        "features_in_order": features,
        "training": {"rows": int(len(train)), "months": [str(train["monthly_reporting_period"].min().date()), str(train["monthly_reporting_period"].max().date())],
                     "target_rate_downsampled": float(train[TARGET].mean()), "case_control": "10:1 downsampled",
                     "calibration_holdout_rows": int(len(holdout)), "calibration_holdout_base_rate": float(holdout[TARGET].mean())},
        "validation": {
            "protocol": f"the same specification refit on fitting loans' rows before {CUT.date()} (the export's 80% loan split), judged from {CUT.date()} on loans it never saw; "
                        "the isotonic map refit on the natural-rate holdout before the cut and judged on the holdout from it",
            "out_of_time_downsampled": downsampled,
            "out_of_time_natural_rate": natural,
            "shipped_artifact_on_natural_rate_holdout": shipped,
        },
        "limitations": [
            "The target counts the first 30+ day delinquency, and a large share of 2020 and 2021 events were pandemic forbearance, not a change in borrowers' ability to pay; the model learned that period too.",
            "The training rows are downsampled 10:1 and the calibration map, fitted on a separate natural-rate holdout of four origination quarters, is what restores real-world rates; a portfolio with a different mix needs its own check.",
            "Predicted over actual after calibration is shown by decile in this card; where the map is thin (the top decile) the rate is the least certain.",
            "Not a decision model: it ranks performing loans for analyst attention and says nothing about the loan once it is late (the delinquency model projects that).",
            "The property's state is a feature; geography can stand in for protected characteristics, and a lender using these scores in credit decisions must test for disparate impact on its own portfolio.",
        ],
    }
    path = RESOURCES / "early_warning_card.json"
    path.write_text(json.dumps(card, indent=2, default=float) + "\n", encoding="utf-8")
    print(f"wrote {path}")


if __name__ == "__main__":
    main()
