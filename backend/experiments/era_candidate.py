"""Decision experiment 2: a pooled survival model that keeps today's level for today's loans.

pooled_candidate.py's pooled fit won on average and was withdrawn. Looking closer (held-out 2006-2008 loans,
actual macro path) it is NOT wrong about the crisis: score < 660 and LTV >= 95 in FL/CA/AZ/NV, 2007 vintage,
it predicts 26% default in 12 months and 49% in 24 against 31% and 55% realized. What it gets wrong is today:
a 2006 loan and a 2024 loan with the same score, LTV and DTI were not the same risk (underwriting changed after
2008), the macro drivers of 2006 look like today's, so nothing tells the model which is which and it prices a
risky new loan partly as a 2006 one (first-year loans since 2022-07 with score < 660 and LTV >= 95 realize 0.71
of what it predicts; the 2017-2026 fit, 0.85).

Candidates, all under the shipped out-of-time protocol (nothing from 2023-01 onwards is seen):

    current      2017-2022 loan-months
    pooled       + the 2006-2008 vintages to 2016-12                                 (the withdrawn model)
    era          pooled, plus one feature: originated before 2009 (1) or not (0)
    era_shared   era, with interaction constraints: a tree that splits on the era flag may not split on a
                 macro driver or the regime. The era can then move the level by borrower type, but the response
                 to house prices, unemployment and rates is necessarily learned once, for both eras.

A new loan is always scored with the flag at 0. The rule, fixed before the run; a candidate must meet all of:

    R1  out-of-time log loss below current's, loan-level bootstrap interval excluding zero; neither AUC lower
        by more than 0.002;
    R2  out of time, score < 680 and LTV >= 90: realized / predicted default no further from 1 than current's
        (in log terms, with 0.05 slack);
    R3  the response to falling prices survives with the flag at 0: on held-out 2017-2022 loan-months, mean
        default hazard at a -20% house-price change at least 1.15 x that at 0%, and at mark-to-market LTV 130
        at least 1.15 x that at 100;
    R4  held-out 2006-2008 loans, each year 2007-2013: realized / predicted default within 0.80 to 1.25;
    R5  held-out loans before 2023 where the model predicts a monthly default hazard of 4% or more:
        realized / predicted no lower than current's less 0.05.

Outcome: `pooled`, `era` and `era_shared` all pass R1 to R5; `pooled` was shipped (card 2026.10.13-0616).
The premise above was wrong in its second half: on 812 out-of-time events the pooled fit is the best
calibrated of the four on risky loans (1.06; current 1.11, era 1.11, era_shared 1.09), and the 0.71 came from
20 events. The flag does lower a risky new loan's baseline 12-month PD (10.4% against 17.5% pooled and 9.0%
current, hazards unscaled) but nothing measurable out of time improves, and it would add an input the Java
engine has to build, so it was not used.

Note: `current` here is a refit under the protocol, so the script does not depend on which model is shipped.

Prints one JSON report and writes it to data/processed/survival_crisis/era_candidate_report.json. Resumable:
each candidate's results are kept under era_candidate_parts/ as it finishes (delete the folder to refit).
Run from backend/:  python experiments/era_candidate.py
"""
from __future__ import annotations

import json
import math
import sys
import time
from pathlib import Path

import lightgbm as lgb
import numpy as np
import polars as pl
from sklearn.metrics import roc_auc_score

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import golden_term_structure as gts  # noqa: E402
from credit_common import CATEGORICAL, MODEL_ORIGINATION, SURVIVAL, category_mappings, float32_exact_booster, quantize  # noqa: E402
from export_survival_model import FEATURES, PARAMS, TEST_START  # noqa: E402

sys.path.insert(0, str(Path(__file__).resolve().parent))
from cross_crisis import CRISIS, CRISIS_END, load  # noqa: E402
from pooled_candidate import row_loss  # noqa: E402

ERA = "pre_2009_origination"
ERA_FEATURES = FEATURES + [ERA]
REFORM = 2009 * 12
BOOTSTRAP = 500


def matrix(df: pl.DataFrame, features: list[str]) -> np.ndarray:
    return quantize(df.select(features).to_numpy(), features)


def fit(train: pl.DataFrame, valid: pl.DataFrame, features: list[str], constraints: list[list[int]] | None) -> tuple[lgb.Booster, int]:
    params = dict(PARAMS)
    if constraints:
        params["interaction_constraints"] = constraints
    model = lgb.LGBMClassifier(**params)
    model.fit(matrix(train, features), train["label"].to_numpy(), sample_weight=train["weight"].to_numpy(),
              eval_set=[(matrix(valid, features), valid["label"].to_numpy())], eval_sample_weight=[valid["weight"].to_numpy()],
              eval_metric="multi_logloss", categorical_feature=[features.index(c) for c in CATEGORICAL],
              callbacks=[lgb.early_stopping(50, verbose=False)])
    best = int(model.best_iteration_)
    return float32_exact_booster(lgb.Booster(model_str=model.booster_.model_to_string(num_iteration=best))), best


def ratio(probs: np.ndarray, df: pl.DataFrame, mask: np.ndarray) -> dict:
    y, w = (df["label"].to_numpy() == 1)[mask], df["weight"].to_numpy()[mask]
    if y.sum() == 0:
        return {"events": 0}
    return {"events": int(y.sum()), "actual_over_predicted": round(float(np.average(y, weights=w) / np.average(probs[mask, 1], weights=w)), 3)}


def swept(booster: lgb.Booster, x: np.ndarray, w: np.ndarray, column: int, values: list[float]) -> list[float]:
    out = []
    for v in values:
        moved = x.copy()
        moved[:, column] = v
        out.append(round(float(np.average(booster.predict(moved)[:, 1], weights=w)) * 1e4, 3))
    return out


def scenario_pd(a: gts.Artifacts, booster: lgb.Booster, with_era: bool, loan: dict, scenario: dict) -> dict:
    """12-month and lifetime PD of a new loan from the reference engine's rows and recursion, hazards unscaled."""
    note = loan["originalInterestRate"]
    months = min(loan["originalLoanTerm"], gts.MAX_MONTHS)
    plan = gts.plan(a, loan, scenario, a.origin, 1, months, 0, note - (a.rates[-1] + scenario["rateShockBp"] / 100.0),
                    a.series(loan["propertyState"], "hpi", a.origin), 1.0, 0.0, note - a.rate(a.rate_as_of))
    rows = np.array(plan["rows"], dtype=np.float32)
    if with_era:
        rows = np.hstack([rows, np.zeros((len(rows), 1), dtype=np.float32)])
    p = booster.predict(rows)
    curve = gts.recurse(a, plan, p[:, 1:3], 0, a.regime_distribution(a.origin, a.origin))
    cum = np.cumsum([c["default"] for c in curve])
    return {"pd12m": round(float(cum[11]), 4), "pdLifetime": round(float(cum[-1]), 4)}


def main() -> None:
    started = time.time()
    mappings = category_mappings()
    era = (pl.col("month") - pl.col("loan_age").cast(pl.Int32) < REFORM).cast(pl.Float32).alias(ERA)
    modern, crisis = load(SURVIVAL, mappings).with_columns(era), load(CRISIS, mappings, CRISIS_END).with_columns(era)
    past, test = modern.filter(pl.col("month") < TEST_START), modern.filter(pl.col("month") >= TEST_START)
    pooled = pl.concat([crisis, past])
    held_past, held_crisis = past.filter(pl.col("bucket") <= 1), crisis.filter(pl.col("bucket") <= 1)
    macro = [f for f in FEATURES if f not in MODEL_ORIGINATION and f != "loan_age"]  # regime, rate incentive, state macro
    shared = [[ERA_FEATURES.index(f) for f in MODEL_ORIGINATION + ["loan_age", ERA]],
              [ERA_FEATURES.index(f) for f in FEATURES]]
    candidates = {"current": (past, FEATURES, None), "pooled": (pooled, FEATURES, None),
                  "era": (pooled, ERA_FEATURES, None), "era_shared": (pooled, ERA_FEATURES, shared)}

    a = gts.Artifacts()
    risky_new = dict(gts.RISKY, originationMonth=None)
    y_test, w_test = test["label"].to_numpy().astype(int), test["weight"].to_numpy().astype(np.float64)
    risky_test = ((test["credit_score"] < 680) & (test["original_ltv"] >= 90)).fill_null(False).to_numpy()
    month_crisis = held_crisis["month"].to_numpy()
    pick = np.random.default_rng(0).choice(held_past.height, size=min(40_000, held_past.height), replace=False)
    report: dict = {"era_share_of_rows": {"2006-2008 sample": float(crisis[ERA].mean()), "2017-2026 sample": float(modern[ERA].mean())},
                    "macro_features": macro, "models": {}}
    losses = {}
    parts = CRISIS / "era_candidate_parts"  # one candidate is 6 to 15 minutes: keep each as it finishes
    parts.mkdir(parents=True, exist_ok=True)
    for name, (train, features, constraints) in candidates.items():
        if (parts / f"{name}.json").exists() and (parts / f"{name}.npy").exists():
            report["models"][name] = json.loads((parts / f"{name}.json").read_text())
            losses[name] = np.load(parts / f"{name}.npy")
            print(f"{name}: reused  ({time.time() - started:.0f}s)", flush=True)
            continue
        booster, best = fit(train.filter(pl.col("bucket") >= 3), train.filter(pl.col("bucket") == 2), features, constraints)
        p_test = booster.predict(matrix(test, features))
        losses[name] = row_loss(p_test, y_test, w_test)
        p_past, p_crisis = booster.predict(matrix(held_past, features)), booster.predict(matrix(held_crisis, features))
        x_pick, w_pick = matrix(held_past, features)[pick], held_past["weight"].to_numpy()[pick]
        hpi = swept(booster, x_pick, w_pick, features.index("hpi_change_12m"), [-0.20, -0.10, 0.0, 0.10])
        ltv = swept(booster, x_pick, w_pick, features.index("mtm_ltv"), [70.0, 100.0, 130.0, 150.0])
        high = p_past[:, 1] >= 0.04
        report["models"][name] = {
            "best_iteration": best,
            "out_of_time": {"weighted_log_loss": float(losses[name].sum() / w_test.sum()),
                            "default_auc": float(roc_auc_score(y_test == 1, p_test[:, 1], sample_weight=w_test)),
                            "prepay_auc": float(roc_auc_score(y_test == 2, p_test[:, 2], sample_weight=w_test)),
                            "default": ratio(p_test, test, np.ones(len(y_test), bool)),
                            "risky_segment_default": ratio(p_test, test, risky_test)},
            "hpi_sweep_bp (-20%, -10%, 0, +10%)": hpi, "mtm_ltv_sweep_bp (70, 100, 130, 150)": ltv,
            "held_out_2006_2008_by_year": {str(y): ratio(p_crisis, held_crisis, (month_crisis >= y * 12) & (month_crisis <= y * 12 + 11)) for y in range(2006, 2017)},
            "held_out_before_2023_predicted_4pct_or_more": ratio(p_past, held_past, high),
            "new_loan_scenarios": {f"{loan['loanId']} {s['name']}": scenario_pd(a, booster, ERA in features, loan, s)
                                   for loan in (gts.PRIME, risky_new, gts.NO_MI) for s in (gts.BASELINE, gts.SEVERELY_ADVERSE)}}
        np.save(parts / f"{name}.npy", losses[name])
        (parts / f"{name}.json").write_text(json.dumps(report["models"][name]), encoding="utf-8")
        print(f"{name}: best iteration {best}  ({time.time() - started:.0f}s)", flush=True)
        print(json.dumps({name: report["models"][name]}, indent=1), flush=True)

    per_loan_w = test.select("loan_sequence_number").with_columns(w=pl.Series(w_test))
    rng = np.random.default_rng(0)
    base = report["models"]["current"]
    base_risky = abs(math.log(base["out_of_time"]["risky_segment_default"]["actual_over_predicted"]))
    base_high = base["held_out_before_2023_predicted_4pct_or_more"]["actual_over_predicted"]
    report["decision"] = {}
    for name in ("pooled", "era", "era_shared"):
        m = report["models"][name]
        loans = per_loan_w.with_columns(diff=pl.Series(losses[name] - losses["current"])).group_by("loan_sequence_number").agg(pl.col("diff").sum(), pl.col("w").sum())
        diff, w = loans["diff"].to_numpy(), loans["w"].to_numpy()
        draws = []
        for _ in range(BOOTSTRAP):
            i = rng.integers(0, len(diff), len(diff))
            draws.append(diff[i].sum() / w[i].sum())
        low, high_ = (float(v) for v in np.quantile(draws, [0.025, 0.975]))
        hpi, ltv = m["hpi_sweep_bp (-20%, -10%, 0, +10%)"], m["mtm_ltv_sweep_bp (70, 100, 130, 150)"]
        years = [m["held_out_2006_2008_by_year"][str(y)]["actual_over_predicted"] for y in range(2007, 2014)]
        rules = {
            "R1_out_of_time_accuracy": bool(high_ < 0 and m["out_of_time"]["default_auc"] >= base["out_of_time"]["default_auc"] - 0.002
                                            and m["out_of_time"]["prepay_auc"] >= base["out_of_time"]["prepay_auc"] - 0.002),
            "R2_recent_risky_segment": bool(abs(math.log(m["out_of_time"]["risky_segment_default"]["actual_over_predicted"])) <= base_risky + 0.05),
            "R3_falling_price_response": bool(hpi[0] >= 1.15 * hpi[2] and ltv[2] >= 1.15 * ltv[1]),
            "R4_crisis_years": bool(all(0.80 <= v <= 1.25 for v in years)),
            "R5_top_hazard_band": bool(m["held_out_before_2023_predicted_4pct_or_more"]["actual_over_predicted"] >= base_high - 0.05)}
        report["decision"][name] = {"log_loss_minus_current": float(diff.sum() / w.sum()), "bootstrap_95pct": [low, high_],
                                    "hpi_minus20_over_flat": round(hpi[0] / hpi[2], 3), "ltv130_over_ltv100": round(ltv[2] / ltv[1], 3),
                                    "crisis_years_2007_2013": years, **rules, "passes_all": all(rules.values())}
    print(json.dumps({"decision": report["decision"]}, indent=1), flush=True)
    out = CRISIS / "era_candidate_report.json"
    out.write_text(json.dumps(report, indent=1) + "\n", encoding="utf-8")
    print(f"wrote {out}  ({time.time() - started:.0f}s)")


if __name__ == "__main__":
    main()
