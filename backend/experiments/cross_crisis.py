"""Experiment: does a hazard model that learned one crisis hold up in the other?

The shipped survival model is fitted on 2017-2026, whose only stress is COVID-19 forbearance: an income shock
with rising house prices. The 2006-2008 vintages (../build_crisis_dataset.py) lived through the opposite kind, a
collapse in collateral values. Their loan-months up to 2016-12 do not overlap the shipped model's months in
time. Same features, same hyperparameters, same loan-id buckets as export_survival_model.py.

    A. Shipped model on the 2006-2008 vintages, year by year: realized against predicted default and
       prepayment, with the regime feature as the shipped lookup has it (calm before 2017) and forced to
       stressed, and the share of those loan-months outside the shipped model's training range.
    B. A model fitted on the 2006-2008 vintages (months to 2016-12), scored on the 2017-2026 sample.
    C. A pooled model (2006-2008 vintages to 2016-12, plus 2017-2022) on the shipped protocol's out-of-time
       months (2023-01 onwards): does adding the crisis cost anything on recent data?
    D. Response to house prices: the mean default hazard of the same held-out loan-months as the 12-month
       house-price change and the mark-to-market LTV are swept, under each model.

Run on 2026-10-13 against the 2017-2026 production fit, which was then the shipped model. The shipped model
has since been re-exported with the 2006-2008 vintages, and this script reads data/processed/survival/
survival_booster.txt, so a rerun now compares the pooled model with itself in parts A, B and D; the recorded
results are in cross_crisis_report.json and MODELS.md.

Every comparison is on loans in buckets 0-1, which no model here trains on. Prints one JSON report and writes
it to data/processed/survival_crisis/cross_crisis_report.json. No shipped artifact is read for writing.
Run from backend/:  python experiments/cross_crisis.py
"""
from __future__ import annotations

import json
import sys
import time
from pathlib import Path

import lightgbm as lgb
import numpy as np
import polars as pl
from sklearn.metrics import roc_auc_score

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from credit_common import RESOURCES, SURVIVAL, category_mappings, loan_bucket  # noqa: E402
from export_survival_model import FEATURES, TEST_START, featurize, fit, matrix, release_final_months  # noqa: E402

CRISIS = SURVIVAL.parent / "survival_crisis"
CRISIS_END = 2016 * 12 + 11       # 2016-12: the crisis sample's months stop before the shipped model's start
REGIME = FEATURES.index("regime_stressed")
WINDOWS_MODERN = {"2017-01 to 2020-05 (calm)": (2017 * 12, 2020 * 12 + 4), "2020-06 to 2022-06 (COVID stress)": (2020 * 12 + 5, 2022 * 12 + 5),
                  "2022-07 onwards (calm)": (2022 * 12 + 6, 10 ** 6)}
HPI_SWEEP = [-0.20, -0.15, -0.10, -0.05, 0.0, 0.05, 0.10, 0.20]
LTV_SWEEP = [50.0, 70.0, 90.0, 100.0, 110.0, 130.0, 150.0]


def load(directory: Path, mappings: dict, until: int | None = None) -> pl.DataFrame:
    rows = pl.read_parquet(str(directory / "rows" / "*.parquet"))
    if until is None:  # the shipped sample: leave out each release's final month, as the export does
        loans = pl.read_parquet(str(directory / "loans" / "*.parquet"))
        rows = rows.filter(~pl.col("month").is_in(release_final_months(loans)))
    else:
        rows = rows.filter(pl.col("month") <= until)
    return featurize(rows, pl.col("month") - pl.col("loan_age").cast(pl.Int32), mappings).with_columns(
        bucket=loan_bucket(pl.col("loan_sequence_number"))).sort(["loan_sequence_number", "loan_age"])


def summary(probs: np.ndarray, df: pl.DataFrame) -> dict:
    y, w = df["label"].to_numpy(), df["weight"].to_numpy()
    out: dict = {"loan_months": round(float(w.sum())), "default_events": int((y == 1).sum())}
    for cause, k in (("default", 1), ("prepay", 2)):
        actual, predicted = float(np.average(y == k, weights=w)), float(np.average(probs[:, k], weights=w))
        out[cause] = {"actual_hazard_bp": round(actual * 1e4, 2), "predicted_hazard_bp": round(predicted * 1e4, 2),
                      "actual_over_predicted": round(actual / predicted, 3),
                      "auc": round(float(roc_auc_score(y == k, probs[:, k], sample_weight=w)), 4) if 0 < (y == k).sum() < len(y) else None}
    return out


def by_window(probs: np.ndarray, df: pl.DataFrame, windows: dict) -> dict:
    month = df["month"].to_numpy()
    out = {"all": summary(probs, df)}
    for name, (first, last) in windows.items():
        mask = (month >= first) & (month <= last)
        if mask.sum() > 0:
            out[name] = summary(probs[mask], df.filter(pl.Series(mask)))
    return out


def sweep(boosters: dict, x: np.ndarray, w: np.ndarray, feature: str, values: list[float]) -> dict:
    j, out = FEATURES.index(feature), {}
    for name, booster in boosters.items():
        curve = []
        for v in values:
            moved = x.copy()
            moved[:, j] = v
            curve.append(round(float(np.average(booster.predict(moved)[:, 1], weights=w)) * 1e4, 3))
        out[name] = curve
    return {"values": values, "mean_default_hazard_bp": out}


def main() -> None:
    started = time.time()
    mappings = category_mappings()
    modern = load(SURVIVAL, mappings)
    crisis = load(CRISIS, mappings, CRISIS_END)
    print(f"shipped sample {modern.height:,} loan-month rows; 2006-2008 vintages {crisis.height:,} (to 2016-12)  ({time.time() - started:.0f}s)", flush=True)
    card = json.loads((RESOURCES / "survival_model.json").read_text())
    scalar = card["overlay"]["hazard_scalars"]["default"]
    shipped = lgb.Booster(model_file=str(SURVIVAL / "survival_booster.txt"))  # the production fit the ONNX was exported from
    held_modern, held_crisis = modern.filter(pl.col("bucket") <= 1), crisis.filter(pl.col("bucket") <= 1)
    x_modern, x_crisis = matrix(held_modern), matrix(held_crisis)
    report: dict = {"rows": {"shipped_sample": modern.height, "crisis_sample": crisis.height,
                             "held_out_shipped": held_modern.height, "held_out_crisis": held_crisis.height}}

    def shipped_predict(x: np.ndarray) -> np.ndarray:
        p = shipped.predict(x)
        p[:, 1] *= scalar  # the card's default overlay
        return p

    # A. shipped model on the crisis vintages
    years = {str(y): (y * 12, y * 12 + 11) for y in range(2006, 2017)}
    stressed = x_crisis.copy()
    stressed[:, REGIME] = 1.0
    envelope = card["training"]["envelope"] if "envelope" in card.get("training", {}) else None
    outside = {}
    for f in ("hpi_change_12m", "mtm_ltv", "unemployment", "unemployment_change_12m", "rate_incentive"):
        v, ref = held_crisis[f].to_numpy().astype(np.float64), modern[f].to_numpy().astype(np.float64)
        lo, hi = (envelope[f] if envelope else np.nanquantile(ref, [0.005, 0.995]))
        outside[f] = {"shipped_training_range": [round(float(lo), 4), round(float(hi), 4)],
                      "share_of_crisis_loan_months_below": round(float(np.average(v < lo, weights=held_crisis["weight"].to_numpy())), 3),
                      "share_above": round(float(np.average(v > hi, weights=held_crisis["weight"].to_numpy())), 3)}
    report["A_shipped_model_on_2006_2008_vintages"] = {
        "regime_as_shipped_lookup (calm)": by_window(shipped_predict(x_crisis), held_crisis, years),
        "regime_forced_stressed": {k: v for k, v in by_window(shipped_predict(stressed), held_crisis, {}).items()},
        "crisis_loan_months_outside_training_range": outside,
        "unseen_category_share": {c: round(float((held_crisis[c] == -1).mean()), 4) for c in ("channel", "property_type", "property_state")}}
    print(json.dumps({"A": report["A_shipped_model_on_2006_2008_vintages"]}, indent=1), flush=True)

    # B. a model that only knows the 2008 crisis, on the shipped sample
    crisis_model, best = fit(crisis.filter(pl.col("bucket") >= 3), crisis.filter(pl.col("bucket") == 2))
    print(f"crisis fit: best iteration {best}  ({time.time() - started:.0f}s)", flush=True)
    report["B_crisis_model_on_2017_2026"] = {
        "crisis_model": by_window(crisis_model.predict(x_modern), held_modern, WINDOWS_MODERN),
        "shipped_model_same_loans": by_window(shipped_predict(x_modern), held_modern, WINDOWS_MODERN),
        "crisis_model_on_its_own_held_out_loans": by_window(crisis_model.predict(x_crisis), held_crisis, {}),
        "best_iteration": best}
    print(json.dumps({"B": report["B_crisis_model_on_2017_2026"]}, indent=1), flush=True)

    # C. pooled fit under the shipped protocol: nothing from 2023 onwards is seen
    past = pl.concat([crisis, modern.filter(pl.col("month") < TEST_START)])
    pooled, best = fit(past.filter(pl.col("bucket") >= 3), past.filter(pl.col("bucket") == 2))
    print(f"pooled fit: best iteration {best}  ({time.time() - started:.0f}s)", flush=True)
    test = modern.filter(pl.col("month") >= TEST_START)
    validation = card["validation"]["out_of_time"]
    report["C_pooled_model_out_of_time_2023_onwards"] = {
        "pooled_model": summary(pooled.predict(matrix(test)), test),
        "shipped_protocol_validation_fit (from the card)": {k: validation[k] for k in ("weighted_log_loss", "default_auc", "prepay_auc") if k in validation},
        "pooled_model_on_crisis_held_out_loans": by_window(pooled.predict(x_crisis), held_crisis, years),
        "best_iteration": best}
    print(json.dumps({"C": report["C_pooled_model_out_of_time_2023_onwards"]}, indent=1), flush=True)

    # D. what each model does when house prices fall
    pick = np.random.default_rng(0).choice(len(x_modern), size=min(40_000, len(x_modern)), replace=False)
    boosters = {"shipped (2017-2026)": shipped, "crisis only (2006-2008 vintages)": crisis_model, "pooled": pooled}
    report["D_house_price_response_on_held_out_2017_2026_loan_months"] = {
        "hpi_change_12m": sweep(boosters, x_modern[pick], held_modern["weight"].to_numpy()[pick], "hpi_change_12m", HPI_SWEEP),
        "mtm_ltv": sweep(boosters, x_modern[pick], held_modern["weight"].to_numpy()[pick], "mtm_ltv", LTV_SWEEP)}
    print(json.dumps({"D": report["D_house_price_response_on_held_out_2017_2026_loan_months"]}, indent=1), flush=True)

    out = CRISIS / "cross_crisis_report.json"
    out.write_text(json.dumps(report, indent=1) + "\n", encoding="utf-8")
    print(f"wrote {out}  ({time.time() - started:.0f}s)")


if __name__ == "__main__":
    main()
