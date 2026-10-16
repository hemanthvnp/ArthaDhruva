"""Decision experiment 3: two changes to the shipped (pooled) survival model, each against it.

    pooled      the shipped fit: 2017-2022 plus the 2006-2008 vintages to 2016-12 (refit here under the protocol)
    burnout     + burnout_months, the months a loan has already spent 50 bp or more in the money. It was rejected
                on 2017-2026 alone because one refinancing wave could not identify it
                (survival_path_features.py); the 2006-2008 vintages add the 2009-2012 waves.
    regime08    the regime flag set to stressed for 2009-01 to 2012-12 as well. The regime series starts in 2017,
                so the shipped model sees the 2008 downturn as calm, and a scenario's forced stress months then
                add the COVID-type effect on top of a fall in house prices, a pair no training month contains.
                The window is a rule, not a fit: the months in which the 2006-2008 cohort's monthly default
                hazard was 30 bp or more. (The 2-state HMM of export_hmm.py, fitted on that cohort's hazard and
                the market rate, marks 2008-12 to 2015-07, which follows falling rates and an ageing cohort, not
                stress.)

All under the shipped out-of-time protocol (nothing from 2023-01 onwards is seen; buckets 0-1 never trained on).
The rule, fixed before the run; a candidate replaces the shipped fit only if all hold:

    R1  out-of-time log loss below pooled's, loan-level bootstrap interval excluding zero; neither AUC lower by
        more than 0.002;
    R2  out-of-time realized / predicted prepayment no further from 1 than pooled's (log terms, 0.02 slack);
    R3  held-out 2006-2008 loans, each year 2007-2013: realized / predicted default within 0.80 to 1.25;
    R4  held-out loans, 2020-06 to 2022-06: realized / predicted default within 0.80 to 1.25;
    R5  (burnout only) what it learned is burnout: on held-out loan-months in the money, the mean prepayment
        hazard with the count set to 36 is below that with it set to 6.

Reported, not ruled on: for a new loan under SEVERELY_ADVERSE, the 12-month PD with and without the forced
stress months (what regime08 is meant to change).

Outcome (2026-10-13): neither replaces the shipped fit.

    burnout   fails R1 and R5. Out-of-time log loss is higher (0.036018 against 0.035984; difference +0.000034,
              95% interval +0.000027 to +0.000040), and what it learned is again a ramp, not burnout: the
              prepayment hazard of in-the-money loan-months is 203 bp at 0 months, 266 at 6, 300 at 24, 286 at
              36. More refinancing waves did not change the earlier finding. (It does bring the out-of-time
              prepayment level closer to 1: 0.986 against 0.959.)
    regime08  fails R1 because nothing measurable changes: log-loss difference +0.0000006, interval
              -0.0000038 to +0.0000047; AUCs 0.7951 / 0.6642 against 0.7943 / 0.6642; crisis years and the
              COVID window as before. It does shrink what the forced stress months add on top of a price fall
              (prime new loan under SEVERELY_ADVERSE: 12-month PD 0.52% with them and 0.44% without, against
              1.20% and 0.62% in the shipped fit), which is what it was for. Not adopted under a rule about
              accuracy; if the stacking limitation is to be fixed, this is the candidate, and it needs its own
              rule and a regime chain that covers 2009-2012.

Resumable (parts under data/processed/survival_crisis/path_regime_parts/). Prints one JSON report and writes
data/processed/survival_crisis/path_regime_report.json. Run from backend/.
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
import golden_term_structure as gts  # noqa: E402
from credit_common import SURVIVAL, category_mappings, loan_bucket  # noqa: E402
from export_survival_model import FEATURES, PROTOCOL_OVERLAY_MONTHS, TEST_START, featurize, release_final_months  # noqa: E402

sys.path.insert(0, str(Path(__file__).resolve().parent))
from cross_crisis import CRISIS, CRISIS_END  # noqa: E402
from era_candidate import fit, matrix  # noqa: E402
from pooled_candidate import row_loss  # noqa: E402
from survival_path_features import BURNOUT_THRESHOLD, with_path_features  # noqa: E402

BURNOUT = "burnout_months"
STRESS_2008 = (2009 * 12, 2012 * 12 + 11)
COVID = (2020 * 12 + 5, 2022 * 12 + 5)
BOOTSTRAP = 500


def load(directory: Path, mappings: dict, until: int | None) -> pl.DataFrame:
    rows = pl.read_parquet(str(directory / "rows" / "*.parquet"))
    if until is None:
        rows = rows.filter(~pl.col("month").is_in(release_final_months(pl.read_parquet(str(directory / "loans" / "*.parquet")))))
    else:
        rows = rows.filter(pl.col("month") <= until)
    orig = pl.col("month") - pl.col("loan_age").cast(pl.Int32)
    rows = with_path_features(rows, orig).drop("month_of_year")  # by row position: before any join
    return featurize(rows, orig, mappings).with_columns(
        bucket=loan_bucket(pl.col("loan_sequence_number")),
        regime_2008=pl.when(pl.col("month").is_between(*STRESS_2008)).then(1.0).otherwise(pl.col("regime_stressed")).cast(pl.Float32),
    ).sort(["loan_sequence_number", "loan_age"])


def ratios(probs: np.ndarray, df: pl.DataFrame, mask: np.ndarray) -> dict:
    y, w = df["label"].to_numpy()[mask], df["weight"].to_numpy()[mask]
    out = {}
    for cause, k in (("default", 1), ("prepay", 2)):
        events = int((y == k).sum())
        out[cause] = {"events": events, "actual_over_predicted": round(float(np.average(y == k, weights=w) / np.average(probs[mask, k], weights=w)), 3) if events else None}
    return out


def scenario_pd(a: gts.Artifacts, booster, features: list[str], loan: dict, scenario: dict) -> float:
    """12-month PD of a new loan from the reference engine's rows and recursion, hazards unscaled."""
    note = loan["originalInterestRate"]
    market = a.rates[-1] + scenario["rateShockBp"] / 100.0
    months = min(loan["originalLoanTerm"], gts.MAX_MONTHS)
    plan = gts.plan(a, loan, scenario, a.origin, 1, months, 0, note - market, a.series(loan["propertyState"], "hpi", a.origin),
                    1.0, 0.0, note - a.rate(a.rate_as_of))
    rows = np.array(plan["rows"], dtype=np.float32)
    if BURNOUT in features:  # rates are flat in these scenarios: in the money every month, or never
        in_money = note - market >= BURNOUT_THRESHOLD
        count = np.array([max(m["k"] - 1, 0) if in_money else 0 for m in plan["months"] for _ in ([1] if m["forced"] else [0, 1])], dtype=np.float32)
        rows = np.hstack([rows, count[:, None]])
    curve = gts.recurse(a, plan, booster.predict(rows)[:, 1:3], 0, a.regime_distribution(a.origin, a.origin))
    return round(float(sum(c["default"] for c in curve[:12])), 4)


def main() -> None:
    started = time.time()
    mappings = category_mappings()
    modern, crisis = load(SURVIVAL, mappings, None), load(CRISIS, mappings, CRISIS_END)
    past, test = modern.filter(pl.col("month") < TEST_START), modern.filter(pl.col("month") >= TEST_START)
    pooled = pl.concat([crisis, past])
    relabelled = pooled.with_columns(regime_stressed=pl.col("regime_2008"))
    candidates = {"pooled": (pooled, FEATURES), "burnout": (pooled, FEATURES + [BURNOUT]), "regime08": (relabelled, FEATURES)}

    a = gts.Artifacts()
    risky_new = dict(gts.RISKY, originationMonth=None)
    severe = gts.SEVERELY_ADVERSE
    views = {"baseline": gts.BASELINE, "severely adverse": severe, "severely adverse, no forced stress months": dict(severe, stressedMonths=0),
             "forced stress months only": dict(gts.BASELINE, name="FORCED", stressedMonths=severe["stressedMonths"])}
    y_test, w_test, m_test = test["label"].to_numpy().astype(int), test["weight"].to_numpy().astype(np.float64), test["month"].to_numpy()
    fit_on = m_test < TEST_START + PROTOCOL_OVERLAY_MONTHS
    parts = CRISIS / "path_regime_parts"
    parts.mkdir(parents=True, exist_ok=True)
    report: dict = {"stress_2008_window": ["2009-01", "2012-12"], "models": {}}
    losses = {}
    for name, (train, features) in candidates.items():
        if (parts / f"{name}.json").exists() and (parts / f"{name}.npy").exists():
            report["models"][name] = json.loads((parts / f"{name}.json").read_text())
            losses[name] = np.load(parts / f"{name}.npy")
            print(f"{name}: reused", flush=True)
            continue
        held_past = train.filter((pl.col("bucket") <= 1) & (pl.col("month") > CRISIS_END))
        held_crisis = train.filter((pl.col("bucket") <= 1) & (pl.col("month") <= CRISIS_END))
        booster, best = fit(train.filter(pl.col("bucket") >= 3), train.filter(pl.col("bucket") == 2), features, None)
        p_test = booster.predict(matrix(test, features))
        losses[name] = row_loss(p_test, y_test, w_test)
        p_past, p_crisis = booster.predict(matrix(held_past, features)), booster.predict(matrix(held_crisis, features))
        mp, mc = held_past["month"].to_numpy(), held_crisis["month"].to_numpy()
        early = ratios(p_test, test, fit_on)["prepay"]["actual_over_predicted"]
        later = ratios(p_test, test, ~fit_on)["prepay"]["actual_over_predicted"]
        model = {
            "best_iteration": best,
            "out_of_time": {"weighted_log_loss": float(losses[name].sum() / w_test.sum()),
                            "default_auc": float(roc_auc_score(y_test == 1, p_test[:, 1], sample_weight=w_test)),
                            "prepay_auc": float(roc_auc_score(y_test == 2, p_test[:, 2], sample_weight=w_test)),
                            **ratios(p_test, test, np.ones(len(y_test), bool)),
                            "prepay_first_18_months_then_later": [early, later]},
            "held_out_covid_window": ratios(p_past, held_past, (mp >= COVID[0]) & (mp <= COVID[1])),
            "held_out_2017_to_2020_05": ratios(p_past, held_past, mp < COVID[0]),
            "held_out_2006_2008_by_year": {str(y): ratios(p_crisis, held_crisis, (mc >= y * 12) & (mc <= y * 12 + 11)) for y in range(2006, 2017)},
            "new_loan_pd12m": {f"{loan['loanId']}, {view}": scenario_pd(a, booster, features, loan, s)
                               for loan in (gts.PRIME, risky_new) for view, s in views.items()}}
        if BURNOUT in features:
            x = matrix(held_past, features)
            money = x[:, features.index("rate_incentive")] >= BURNOUT_THRESHOLD
            x, w = x[money][:60_000], held_past["weight"].to_numpy()[money][:60_000]
            curve = []
            for months in (0, 6, 12, 24, 36, 60):
                moved = x.copy()
                moved[:, features.index(BURNOUT)] = months
                curve.append(round(float(np.average(booster.predict(moved)[:, 2], weights=w)) * 1e4, 2))
            model["prepay_hazard_bp_at_burnout (0, 6, 12, 24, 36, 60 months)"] = curve
        report["models"][name] = model
        np.save(parts / f"{name}.npy", losses[name])
        (parts / f"{name}.json").write_text(json.dumps(model), encoding="utf-8")
        print(f"{name}: best iteration {best}  ({time.time() - started:.0f}s)", flush=True)
        print(json.dumps({name: model}, indent=1), flush=True)

    base = report["models"]["pooled"]
    per_loan_w = test.select("loan_sequence_number").with_columns(w=pl.Series(w_test))
    rng = np.random.default_rng(0)
    report["decision"] = {}
    for name in ("burnout", "regime08"):
        m = report["models"][name]
        loans = per_loan_w.with_columns(diff=pl.Series(losses[name] - losses["pooled"])).group_by("loan_sequence_number").agg(pl.col("diff").sum(), pl.col("w").sum())
        diff, w = loans["diff"].to_numpy(), loans["w"].to_numpy()
        draws = []
        for _ in range(BOOTSTRAP):
            i = rng.integers(0, len(diff), len(diff))
            draws.append(diff[i].sum() / w[i].sum())
        low, high = (float(v) for v in np.quantile(draws, [0.025, 0.975]))
        years = [m["held_out_2006_2008_by_year"][str(y)]["default"]["actual_over_predicted"] for y in range(2007, 2014)]
        covid = m["held_out_covid_window"]["default"]["actual_over_predicted"]
        rules = {
            "R1_out_of_time_accuracy": bool(high < 0 and m["out_of_time"]["default_auc"] >= base["out_of_time"]["default_auc"] - 0.002
                                            and m["out_of_time"]["prepay_auc"] >= base["out_of_time"]["prepay_auc"] - 0.002),
            "R2_prepayment_level": bool(abs(math.log(m["out_of_time"]["prepay"]["actual_over_predicted"]))
                                        <= abs(math.log(base["out_of_time"]["prepay"]["actual_over_predicted"])) + 0.02),
            "R3_crisis_years": bool(all(0.80 <= v <= 1.25 for v in years)),
            "R4_covid_window": bool(0.80 <= covid <= 1.25)}
        if name == "burnout":
            curve = m["prepay_hazard_bp_at_burnout (0, 6, 12, 24, 36, 60 months)"]
            rules["R5_learned_burnout"] = bool(curve[4] < curve[1])
        report["decision"][name] = {"log_loss_minus_pooled": float(diff.sum() / w.sum()), "bootstrap_95pct": [low, high],
                                    "crisis_years_2007_2013": years, "covid_window": covid, **rules, "passes_all": all(rules.values())}
    print(json.dumps({"decision": report["decision"]}, indent=1), flush=True)
    out = CRISIS / "path_regime_report.json"
    out.write_text(json.dumps(report, indent=1) + "\n", encoding="utf-8")
    print(f"wrote {out}  ({time.time() - started:.0f}s)")


if __name__ == "__main__":
    main()
