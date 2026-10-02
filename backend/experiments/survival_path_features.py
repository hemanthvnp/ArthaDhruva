"""Experiment (rejected): calendar-month seasonality and burnout as features of the survival model.

Both are textbook prepayment drivers, so they were tried with the hyperparameters of
export_survival_model.py -- trained on loan-months before 2023-01, judged on 2023-01 onwards:

    features                 OOT log loss   default AUC   prepay AUC   prepay predicted / actual
    base (23 features)         0.036446       0.7918        0.6647        1.00
    + burnout_months           0.036460       0.7916        0.6665        1.00
    + month_of_year            0.036404       0.7925        0.6692        1.13
    + both                     0.036397       0.7907        0.6711        1.09

Neither is in the model, for reasons the headline metrics hide:

  * month_of_year. With one stress episode in the sample, the calendar month soaks up the June-July 2020
    forbearance spike: the fitted default hazard is 2.7x higher in June than in May (0.00191 vs 0.00072)
    in EVERY year. A forward projection would print that spike every summer. It also over-predicts
    out-of-time prepayments by 13%. A 0.004 gain in prepay AUC does not buy that.
  * burnout_months (months the loan has already spent 50 bp or more in the money). No out-of-time gain
    -- after 2022 almost no loan is in the money, so the test period cannot see it -- and what it learns
    in-sample is a ramp (prepay hazard rising 0.026 -> 0.039 over the first 12 months in the money, then
    flat), the 2020-21 refinancing wave building up, not burnout. One refinancing wave cannot identify it.

Run from backend/:  PYTHONPATH=. python experiments/survival_path_features.py both burnout season
(then `sweep <set>` prints the partial dependence quoted above).
"""
from __future__ import annotations

import json
import sys
import time

import lightgbm as lgb
import numpy as np
import polars as pl
from sklearn.metrics import log_loss, roc_auc_score

from credit_common import (CATEGORICAL, SURVIVAL, category_mappings, encode, loan_bucket, quantize, with_macro,
                           with_spread, with_state_macro)
from export_survival_model import FEATURES as BASE, PARAMS, TEST_START

BURNOUT_THRESHOLD = 0.5  # points of rate advantage at which a refinance pays for its closing costs
SETS = {
    "base": BASE,
    "season": BASE + ["month_of_year"],
    "burnout": BASE + ["burnout_months"],
    "both": BASE + ["month_of_year", "burnout_months"],
}


def with_path_features(df: pl.DataFrame, orig_month: pl.Expr) -> pl.DataFrame:
    """month_of_year (1-12) and burnout_months: months strictly after origination and before this one in
    which the market rate was at least BURNOUT_THRESHOLD below the note rate. Attached by row position,
    so call it before any join."""
    macro = pl.read_parquet(SURVIVAL / "macro.parquet").sort("month")
    first = int(macro["month"][0])
    rates = macro["rate"].to_numpy()
    threshold = df["original_interest_rate"].to_numpy().astype(np.float64) - BURNOUT_THRESHOLD
    unique, inverse = np.unique(threshold, return_inverse=True)
    in_money = np.cumsum(rates[None, :] <= unique[:, None], axis=1)
    last = len(rates) - 1
    month = df["month"].to_numpy().astype(np.int64) - first
    orig = df.select(orig_month.cast(pl.Int64).alias("_o"))["_o"].to_numpy() - first
    before = in_money[inverse, np.clip(month - 1, 0, last)] - in_money[inverse, np.clip(orig, 0, last)]
    return df.with_columns(
        month_of_year=(pl.col("month") % 12 + 1).cast(pl.Float32),
        burnout_months=pl.Series(np.maximum(before, 0).astype(np.float32)),
    )


def load(pattern: str = "*.parquet") -> pl.DataFrame:
    rows = pl.read_parquet(str(SURVIVAL / "rows" / pattern))
    orig_month = pl.col("month") - pl.col("loan_age").cast(pl.Int32)
    rows = with_path_features(rows, orig_month)
    rows = with_state_macro(with_spread(rows, orig_month), orig_month)
    return encode(with_macro(rows), category_mappings()).with_columns(
        loan_age=pl.col("loan_age").cast(pl.Float32), bucket=loan_bucket(pl.col("loan_sequence_number")))


def matrix(df: pl.DataFrame, features: list[str]) -> np.ndarray:
    return quantize(df.select(features).to_numpy(), features)  # the two path features are whole numbers already


def run(name: str, rows: pl.DataFrame) -> None:
    features = SETS[name]
    started = time.time()
    train = rows.filter((pl.col("month") < TEST_START) & (pl.col("bucket") >= 2))
    valid = rows.filter((pl.col("month") < TEST_START) & (pl.col("bucket") == 1))
    test = rows.filter(pl.col("month") >= TEST_START)
    y, w = test["label"].to_numpy(), test["weight"].to_numpy()
    model = lgb.LGBMClassifier(**PARAMS)
    model.fit(matrix(train, features), train["label"].to_numpy(), sample_weight=train["weight"].to_numpy(),
              eval_set=[(matrix(valid, features), valid["label"].to_numpy())], eval_sample_weight=[valid["weight"].to_numpy()],
              eval_metric="multi_logloss", categorical_feature=[features.index(c) for c in CATEGORICAL],
              callbacks=[lgb.early_stopping(50, verbose=False)])
    p = model.predict_proba(matrix(test, features))
    print(json.dumps({
        "set": name, "best_iteration": int(model.best_iteration_),
        "log_loss": float(log_loss(y, p, sample_weight=w, labels=[0, 1, 2])),
        "default_auc": float(roc_auc_score(y == 1, p[:, 1], sample_weight=w)),
        "prepay_auc": float(roc_auc_score(y == 2, p[:, 2], sample_weight=w)),
        "prepay_predicted_over_actual": float(np.average(p[:, 2], weights=w) / np.average(y == 2, weights=w)),
        "seconds": round(time.time() - started)}), flush=True)
    model.booster_.save_model(str(SURVIVAL / f"experiment_{name}.txt"))


def sweep(name: str) -> None:
    """Partial dependence of the hazards on the path features (other features as observed)."""
    features = SETS[name]
    booster = lgb.Booster(model_file=str(SURVIVAL / f"experiment_{name}.txt"))
    x = matrix(load("20*Q1.parquet").sample(n=40_000, seed=5), features)
    at = {f: i for i, f in enumerate(features)}

    def vary(feature: str, values, mask=None, label: str = "") -> None:
        base = x if mask is None else x[mask]
        print(f"{feature} {label}(n={len(base)})")
        for v in values:
            z = base.copy()
            z[:, at[feature]] = v
            p = booster.predict(z)
            print(f"   {v:5.0f}  default {p[:, 1].mean():.5f}  prepay {p[:, 2].mean():.5f}")

    if "month_of_year" in at:
        vary("month_of_year", range(1, 13))
    if "burnout_months" in at:
        vary("burnout_months", [0, 1, 3, 6, 9, 12, 18, 24, 36, 48], x[:, at["rate_incentive"]] >= 1.0, "| incentive >= 1 ")


if __name__ == "__main__":
    if sys.argv[1:2] == ["sweep"]:
        sweep(sys.argv[2])
    else:
        data = load()
        for chosen in sys.argv[1:]:
            run(chosen, data)
