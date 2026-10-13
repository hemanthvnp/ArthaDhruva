"""Decision experiment: should the survival model be re-exported with the 2006-2008 vintages?

Two fits under the shipped out-of-time protocol (export_survival_model.py: nothing from 2023-01 onwards is
seen; loans in buckets 0-1 are never trained on; bucket 2 stops the boosting), same features and
hyperparameters:

    current   2017-2022 loan-months                                   (must reproduce the card's log loss)
    pooled    the same, plus the 2006-2008 vintages' loan-months to 2016-12

and every measurement on both:

    1. out of time, 2023-01 onwards: weighted log loss, AUC, realized / predicted, calibration by decile,
       month-by-month hazard error, and the paired log-loss difference with a loan-level bootstrap interval;
    2. the overlay protocol: does a scalar from the first 18 out-of-time months carry to the later ones;
    3. held-out loans before 2023, by period (calm, COVID stress);
    4. held-out 2006-2008 loans, by year (for `current` this is a crisis it never saw; for `pooled`, unseen
       loans in years it trained on).

The rule, fixed before the run: the pooled model replaces the current one only if its out-of-time log loss
is lower with the bootstrap interval of the difference excluding zero, and neither out-of-time AUC is lower
by more than 0.002. Prints one JSON report and writes it to
data/processed/survival_crisis/pooled_candidate_report.json. Run from backend/.

Outcome: shipped (card 2026.10.13-0616). The pooled fit met the rule: log loss 0.035984 against 0.036028
(difference -0.000044, 95% interval -0.000052 to -0.000037 over 842,867 loans), default AUC 0.7943 against
0.7905, prepayment 0.6642 against 0.6618. It was exported, withdrawn the same day on the four observations
below, and reinstated after era_candidate.py showed that the first two do not hold (the 2017-2026 fit is
worse in the top hazard band, 0.75 against 0.82; on 812 out-of-time events the pooled fit is the better
calibrated on risky loans, 1.06 against 1.11; and the riskiest 2007 loans did default at 31% in a year).
The observations as first written:

  * the production fit over-predicts its highest hazards on held-out loans (predicted 4-8% a month: realized
    3.5% on the 2006-2008 loans and 4.6% on 2017-2026; predicted above 8%: 12% against 7.6%), and a stress
    scenario moves risky loans into that band;
  * for a new loan with score 640 and LTV 95 the 12-month PD went from 8.0% to 14.9% at baseline and from 9.1%
    to 55.5% under SEVERELY_ADVERSE; recent first-year loans with score < 660 and LTV >= 95 realize 0.71 of
    what it predicts, against 0.85 for the 2017-2026 fit (20 events);
  * a scenario's forced stress months (the COVID-type regime) and a fall in house prices then act together,
    a combination no training month contains;
  * it needs 1,392 boosting rounds against 596 (ONNX 34.8 MB against 16.4 MB).

era_candidate.py added those checks to the rule (the top hazard band, the recent risky segment, the crisis
years, the response to falling prices); this fit passes all of them. The third and fourth observations stand
and are limitations in the card and MODELS.md.
"""
from __future__ import annotations

import json
import math
import sys
import time
from pathlib import Path

import numpy as np
import polars as pl
from sklearn.metrics import roc_auc_score

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from credit_common import RESOURCES, SURVIVAL, category_mappings  # noqa: E402
from export_survival_model import (CAUSES, PROTOCOL_OVERLAY_MONTHS, TEST_START, calibration_report, drift, fit, matrix,  # noqa: E402
                                   month_label, release_final_months, scaled)

sys.path.insert(0, str(Path(__file__).resolve().parent))
from cross_crisis import CRISIS, CRISIS_END, WINDOWS_MODERN, load  # noqa: E402

BOOTSTRAP = 500
AUC_TOLERANCE = 0.002


def row_loss(probs: np.ndarray, y: np.ndarray, w: np.ndarray) -> np.ndarray:
    p = probs / probs.sum(axis=1, keepdims=True)
    return -w * np.log(np.clip(p[np.arange(len(y)), y], 1e-15, 1.0))


def measure(probs: np.ndarray, df: pl.DataFrame) -> dict:
    y, w = df["label"].to_numpy().astype(int), df["weight"].to_numpy().astype(np.float64)
    report = calibration_report(probs, y, w)
    out: dict = {"loan_months": round(float(w.sum())), "weighted_log_loss": report["weighted_log_loss"]}
    for cause, k in CAUSES.items():
        r = report[cause]
        deciles = [abs(math.log(d["actual"] / d["predicted"])) for d in r["deciles"] if d["actual"] > 0]
        out[cause] = {"events": int((y == k).sum()), "auc": float(roc_auc_score(y == k, probs[:, k], sample_weight=w)),
                      "actual_over_predicted": r["actual_over_predicted"],
                      "worst_decile_abs_log_ratio": max(deciles), "mean_decile_abs_log_ratio": float(np.mean(deciles)),
                      "top_percentile_actual_over_predicted": r["top_percentile"]["actual"] / r["top_percentile"]["predicted"]}
    return out


def monthly_error(probs: np.ndarray, df: pl.DataFrame) -> dict:
    """Mean absolute error of the monthly portfolio hazard, in basis points."""
    frame = df.select(["month", "label", "weight"]).with_columns(d=pl.Series(probs[:, 1]), p=pl.Series(probs[:, 2]))
    m = frame.group_by("month").agg(
        pd_=(pl.col("weight") * pl.col("d")).sum() / pl.col("weight").sum(), ad=(pl.col("weight") * (pl.col("label") == 1)).sum() / pl.col("weight").sum(),
        pp_=(pl.col("weight") * pl.col("p")).sum() / pl.col("weight").sum(), ap=(pl.col("weight") * (pl.col("label") == 2)).sum() / pl.col("weight").sum())
    return {"default_bp": float((m["pd_"] - m["ad"]).abs().mean() * 1e4), "prepay_bp": float((m["pp_"] - m["ap"]).abs().mean() * 1e4)}


def windows(probs: np.ndarray, df: pl.DataFrame, spans: dict) -> dict:
    month, out = df["month"].to_numpy(), {}
    for name, (first, last) in spans.items():
        mask = (month >= first) & (month <= last)
        if mask.sum():
            out[name] = measure(probs[mask], df.filter(pl.Series(mask)))
    return out


def overlay_protocol(probs: np.ndarray, test: pl.DataFrame, coverage_end: int) -> dict:
    y, w, month = test["label"].to_numpy(), test["weight"].to_numpy(), test["month"].to_numpy()
    fit_on = month < TEST_START + PROTOCOL_OVERLAY_MONTHS
    judge = (month >= TEST_START + PROTOCOL_OVERLAY_MONTHS) & (month <= coverage_end)
    d = drift(probs[fit_on], y[fit_on], w[fit_on])
    scalars = {cause: d[cause]["ratio"] for cause in CAUSES}
    before, after = calibration_report(probs[judge], y[judge], w[judge]), calibration_report(scaled(probs[judge], scalars), y[judge], w[judge])
    return {cause: {"overlay": scalars[cause], "later_months_before": before[cause]["actual_over_predicted"],
                    "later_months_after": after[cause]["actual_over_predicted"]} for cause in CAUSES} | {
        "later_months_log_loss_before": before["weighted_log_loss"], "later_months_log_loss_after": after["weighted_log_loss"]}


def main() -> None:
    started = time.time()
    mappings = category_mappings()
    modern, crisis = load(SURVIVAL, mappings), load(CRISIS, mappings, CRISIS_END)
    loans = pl.read_parquet(str(SURVIVAL / "loans" / "*.parquet"))
    coverage_end = release_final_months(loans)[0] - 1
    past, test = modern.filter(pl.col("month") < TEST_START), modern.filter(pl.col("month") >= TEST_START)
    held_past, held_crisis = past.filter(pl.col("bucket") <= 1), crisis.filter(pl.col("bucket") <= 1)
    x_test, x_past, x_crisis = matrix(test), matrix(held_past), matrix(held_crisis)
    years = {str(y): (y * 12, y * 12 + 11) for y in range(2006, 2017)}
    card = json.loads((RESOURCES / "survival_model.json").read_text())["validation"]["out_of_time"]

    report: dict = {"test_months": [month_label(TEST_START), month_label(int(test["month"].max()))], "models": {}}
    losses = {}
    for name, train in (("current", past), ("pooled", pl.concat([crisis, past]))):
        booster, best = fit(train.filter(pl.col("bucket") >= 3), train.filter(pl.col("bucket") == 2))
        print(f"{name}: {train.filter(pl.col('bucket') >= 3).height:,} training rows, best iteration {best}  ({time.time() - started:.0f}s)", flush=True)
        p_test = booster.predict(x_test)
        losses[name] = row_loss(p_test, test["label"].to_numpy().astype(int), test["weight"].to_numpy().astype(np.float64))
        p_crisis = booster.predict(x_crisis)
        report["models"][name] = {
            "best_iteration": best,
            "out_of_time": measure(p_test, test) | {"monthly_hazard_mae": monthly_error(p_test, test)},
            "overlay_protocol": overlay_protocol(p_test, test, coverage_end),
            "held_out_loans_before_2023": windows(booster.predict(x_past), held_past, WINDOWS_MODERN),
            "held_out_2006_2008_loans": {"all": measure(p_crisis, held_crisis)} | windows(p_crisis, held_crisis, years)}
        print(json.dumps({name: report["models"][name]["out_of_time"]}, indent=1), flush=True)

    # paired difference in out-of-time log loss, resampling loans (a loan's months are not independent)
    weight = test["weight"].to_numpy().astype(np.float64)
    per_loan = (test.select("loan_sequence_number").with_columns(diff=pl.Series(losses["pooled"] - losses["current"]), w=pl.Series(weight))
                .group_by("loan_sequence_number").agg(pl.col("diff").sum(), pl.col("w").sum()))
    diff, w = per_loan["diff"].to_numpy(), per_loan["w"].to_numpy()
    rng = np.random.default_rng(0)
    draws = []
    for _ in range(BOOTSTRAP):
        pick = rng.integers(0, len(diff), len(diff))
        draws.append(diff[pick].sum() / w[pick].sum())
    difference = float(diff.sum() / w.sum())
    low, high = (float(v) for v in np.quantile(draws, [0.025, 0.975]))
    current, pooled = report["models"]["current"]["out_of_time"], report["models"]["pooled"]["out_of_time"]
    auc_ok = all(pooled[c]["auc"] >= current[c]["auc"] - AUC_TOLERANCE for c in CAUSES)
    report["decision"] = {
        "current_reproduces_card": {"card_log_loss": card["weighted_log_loss"], "refit_log_loss": current["weighted_log_loss"]},
        "log_loss_pooled_minus_current": difference, "bootstrap_95pct": [low, high], "loans_resampled": int(len(diff)),
        "auc_within_tolerance": auc_ok,
        "pooled_replaces_current": bool(high < 0 and auc_ok)}
    print(json.dumps({"decision": report["decision"]}, indent=1), flush=True)
    out = CRISIS / "pooled_candidate_report.json"
    out.write_text(json.dumps(report, indent=1) + "\n", encoding="utf-8")
    print(f"wrote {out}  ({time.time() - started:.0f}s)")


if __name__ == "__main__":
    main()
