"""Independent reference implementation of the multi-state (delinquency-aware) term-structure engine.

survival.MultiStateEngine (Java) projects a loan whose current delinquency status is known. It reuses the
existing engine's scenario paths, exposure, loss given default and discounting, and replaces the hazards with the
transition model's: from each active state (current, 30, 60 days past due) the probability of every next state. This
script does the same in plain Python from the exported artifacts (delinquency_model.onnx and .json, plus the
survival artifacts the scenario and regime chain come from), runs the same ONNX graph, and writes inputs and expected
outputs to risk-engine/src/test/resources/delinquency_golden.json. DelinquencyGoldenTest holds the Java engine to
them to 1e-6.

It builds on golden_term_structure.py (the independent reference of the existing engine): that module's
scenario, plan and amortization code is the one source of "the same logic" for both engines, as the Java
MultiStateEngine reuses TermStructureEngine's.

The recursion carries a_k(r, s), the probability of being active at the end of month k, in regime r and delinquency
state s, forward through the regime chain and then the state transition of that regime's month:
    b_k(r', s) = sum_r a_{k-1}(r, s) P[r][r']        (all mass on the stressed regime while a scenario forces it)
    default_k  = sum_{r', s} b_k(r', s) T_k(r', s -> default)      prepay_k likewise
    a_k(r', s') = sum_s b_k(r', s) T_k(r', s -> s')
which is the exact expectation over all 2^T regime paths in O(T x R x S^2), with no sampling.

The state transition of month k is conditioned on the loan's history at the start of the projection, aged with the
projection by a fixed rule (delinquency_history.history_at; a path-dependent history would make the recursion inexact,
so the card states the approximation), and from a delinquent state the current spell is part of the history, as in
every training row. From a delinquent state no probability is below the card's floor, and each month's three source rows
are made stochastically ordered (delinquency_history.monotone_repair), which is what makes lifetime default monotone in the
starting state.

Run from backend/ after export_delinquency_model.py:  python golden_multistate.py
"""
from __future__ import annotations

import json
from pathlib import Path

import numpy as np
import onnxruntime as ort

import golden_term_structure as gt
from delinquency_history import NO_CURE, history_at, monotone_repair

RESOURCES = gt.RESOURCES
OUT = Path("risk-engine/src/test/resources/delinquency_golden.json")
CURRENT, D30, D60, DEFAULT, PREPAID = 0, 1, 2, 3, 4
ACTIVE = 3
DEFAULT_HISTORY = {"episodes": 0, "monthsSinceCure": NO_CURE, "maxState": 0}


def start_state(days_past_due: int) -> int:
    if days_past_due >= 90:
        raise ValueError("a loan 90 or more days past due has defaulted; it is not projected forward by this model")
    return 0 if days_past_due < 30 else 1 if days_past_due < 60 else 2


class MultiState(gt.Artifacts):
    """The survival artifacts (scenario, regime chain, macro) plus the delinquency model."""

    def __init__(self, resources: Path = RESOURCES) -> None:
        super().__init__()
        self.card = json.loads((resources / "delinquency_model.json").read_text())
        options = ort.SessionOptions()
        options.intra_op_num_threads = 1
        self.ms_session = ort.InferenceSession(str(resources / "delinquency_model.onnx"), options)
        survival_width = len(self.order)
        assert self.card["features_in_order"][:survival_width] == self.order, "the first columns are the survival features"
        assert self.card["features_in_order"][survival_width:] == ["source_state", "hist_episodes_12m",
                                                                    "hist_months_since_cure", "hist_max_state_12m"]

    def transition_probabilities(self, survival_rows: list, history: dict, elapsed: list[int]) -> np.ndarray:
        """(rows, source state, next state) probabilities for survival-feature rows: each row is evaluated from every
        active source state, with the history aged to its month (`elapsed[i]` months after the start; see
        delinquency_history.history_at). Renormalized in float64, as the Java model does."""
        wide = np.array([list(r) + [s, *history_at(s, elapsed[i], history["episodes"], history["monthsSinceCure"], history["maxState"])]
                         for i, r in enumerate(survival_rows) for s in range(ACTIVE)], dtype=np.float32)
        p = np.asarray(self.ms_session.run(None, {"input": wide})[1], dtype=np.float64)
        p /= p.sum(axis=1, keepdims=True)
        floor = self.card["probability_floor"]["value"]
        late = wide[:, len(self.order)] > 0           # the source state column
        p[late] = np.maximum(p[late], floor)
        p[late] /= p[late].sum(axis=1, keepdims=True)
        return monotone_repair(p.reshape(len(survival_rows), ACTIVE, 5))


def recurse(a: gt.Artifacts, p: dict, probs: np.ndarray, first_row: int, initial_regime: list[float], start: int) -> list[dict]:
    """Forward recursion over (regime, state). `probs[row]` is the (source state, next state) matrix of one
    survival-feature row; a month's rows are those of gt.plan (one regime while forced, else calm and stressed)."""
    t = a.transition
    alive = [[0.0] * ACTIVE, [0.0] * ACTIVE]
    for r in (0, 1):
        alive[r][start] = initial_regime[r]
    out = []
    for m in p["months"]:
        row = first_row + m["row"]
        if m["forced"]:
            b = [[0.0] * ACTIVE, [alive[0][s] + alive[1][s] for s in range(ACTIVE)]]
            regimes = {1: probs[row]}
        else:
            b = [[alive[0][s] * t[0][r] + alive[1][s] * t[1][r] for s in range(ACTIVE)] for r in (0, 1)]
            regimes = {0: probs[row], 1: probs[row + 1]}
        defaults = prepays = 0.0
        new = [[0.0] * ACTIVE, [0.0] * ACTIVE]
        for r, matrix in regimes.items():
            for s in range(ACTIVE):
                mass = b[r][s]
                defaults += mass * matrix[s][DEFAULT]
                prepays += mass * matrix[s][PREPAID]
                for s2 in range(ACTIVE):
                    new[r][s2] += mass * matrix[s][s2]
        alive = new
        active = sum(alive[0]) + sum(alive[1])
        at_risk = sum(b[0]) + sum(b[1])
        out.append(dict(survival=active, default=defaults, prepay=prepays, stressed=sum(b[1]) / at_risk if at_risk > 0 else 0.0,
                        byState=[alive[0][s] + alive[1][s] for s in range(ACTIVE)]))
    return out


def row_months(p: dict) -> list[int]:
    """Months elapsed since the start for each model row of a plan (a month has one row while a scenario forces the
    stressed regime, two otherwise)."""
    out = []
    for i, m in enumerate(p["months"]):
        out += [i] * (1 if m["forced"] else 2)
    return out


def project(a: MultiState, case: dict) -> dict:
    """The same quantities as golden_term_structure.project, from the case's delinquency status and history.
    case['daysPastDue'] gives the starting state; case['history'] the history features."""
    l, s = case["loan"], case["scenario"]
    start = start_state(case.get("daysPastDue") or 0)
    history = case.get("history") or DEFAULT_HISTORY
    months_on_book_in = case.get("monthsOnBook")
    new_loan = l["originationMonth"] is None and not months_on_book_in
    origination = gt.ym(l["originationMonth"]) if l["originationMonth"] else (a.origin if new_loan else a.origin - months_on_book_in)
    on_book = max(0, a.origin - origination)
    months = min(l["originalLoanTerm"] - on_book, gt.MAX_MONTHS)
    note = l["originalInterestRate"]
    lock = a.rate_as_of if new_loan else origination - a.lag
    spread = note - a.rate(lock)
    hpi_origination = a.series(l["propertyState"], "hpi", origination)
    scheduled = l["originalUpb"] * gt.amortization(note, l["originalLoanTerm"], on_book)
    balance_scale = 1.0 if case.get("currentBalance") is None else case["currentBalance"] / scheduled
    lgd0 = gt.fitted_lgd(a, l)
    market = a.rates[-1] + s["rateShockBp"] / 100.0
    main = gt.plan(a, l, s, a.origin, 1, months, on_book, note - market, hpi_origination, balance_scale, lgd0, spread)
    reference = None
    dpd = case.get("daysPastDue") or 0
    if dpd < 30 and on_book > 0 and case.get("originationPd12m") is None:
        reference = gt.plan(a, l, gt.BASELINE, origination, on_book + 1, min(12, months), 0, note - a.rate(origination),
                            hpi_origination, 1.0, lgd0, spread)
    probs = a.transition_probabilities(main["rows"], history, row_months(main))
    curve = recurse(a, main, probs, 0, a.regime_distribution(a.origin, a.origin), start)

    discount, monthly_discount = 1.0, 1.0 / (1.0 + note / 1200.0)
    ecl12 = ecl_life = life = cum_d = cum_p = pd12 = pd24 = lgd_peak = 0.0
    points = []
    for i, (c, m) in enumerate(zip(curve, main["months"])):
        discount *= monthly_discount
        loss = c["default"] * m["lgd"] * m["exposure"] * discount
        ecl_life += loss
        cum_d += c["default"]
        cum_p += c["prepay"]
        if i < 12:
            ecl12 += loss
            pd12 = cum_d
        if i < 24:
            pd24 = cum_d
        life += 1.0 if i == 0 else curve[i - 1]["survival"]
        lgd_peak = max(lgd_peak, m["lgd"])
        if m["k"] in gt.CHECKPOINTS or i == len(curve) - 1:
            points.append(dict(k=m["k"], month=gt.label(a.origin + m["k"]), loanAge=m["age"], survival=c["survival"],
                               marginalDefault=c["default"], marginalPrepay=c["prepay"], cumulativeDefault=cum_d,
                               stressedProbability=c["stressed"], exposure=m["exposure"], lgd=m["lgd"],
                               discountedExpectedLoss=loss, byState=c["byState"]))
    reference_pd = case.get("originationPd12m")
    if reference:
        ref_probs = a.transition_probabilities(reference["rows"], DEFAULT_HISTORY, row_months(reference))
        ref = recurse(a, reference, ref_probs, 0, a.regime_distribution(a.origin, origination), CURRENT)
        reference_pd = sum(c["default"] for c in ref)
    if dpd >= 30:
        stage = 2
    elif reference_pd is not None and pd12 >= gt.SICR_RELATIVE * reference_pd and pd12 - reference_pd >= gt.SICR_ABSOLUTE:
        stage = 2
    else:
        stage = 1
    exposure = main["months"][0]["exposure"]
    impaired = main["months"][0]["lgd"] * exposure
    return dict(monthsOnBook=on_book, remainingMonths=months, startState=start, pd12m=pd12, pd24m=pd24, pdLifetime=cum_d,
                prepayLifetime=cum_p, expectedLifeMonths=life, maturityProbability=curve[-1]["survival"], stage=stage,
                referencePd12m=reference_pd, exposure=exposure, lgd=lgd0, lgdPeak=lgd_peak, ecl12m=ecl12, eclLifetime=ecl_life,
                eclIfrs9=ecl12 if stage == 1 else ecl_life, eclCecl=ecl_life, points=points,
                conservation=max(abs(c["survival"] + cd + cp - 1.0) for c, cd, cp in zip(
                    curve, np.cumsum([c["default"] for c in curve]), np.cumsum([c["prepay"] for c in curve]))))


H_CLEAN = DEFAULT_HISTORY
H_RECENT = {"episodes": 2, "monthsSinceCure": 3, "maxState": 1}
H_DEEP = {"episodes": 1, "monthsSinceCure": NO_CURE, "maxState": 2}
CASES = [
    dict(loan=gt.PRIME, scenario=gt.BASELINE, daysPastDue=0, history=H_RECENT),
    dict(loan=gt.PRIME, scenario=gt.BASELINE, daysPastDue=35, history=H_RECENT),
    dict(loan=gt.PRIME, scenario=gt.BASELINE, daysPastDue=65, history=H_DEEP),
    dict(loan=gt.PRIME, scenario=gt.SEVERELY_ADVERSE, daysPastDue=35, history=H_RECENT),
    dict(loan=gt.SEASONED, scenario=gt.BASELINE, daysPastDue=0, history=H_RECENT),
    dict(loan=gt.SEASONED, scenario=gt.ADVERSE, daysPastDue=40, history=H_RECENT, currentBalance=281000.0),
    dict(loan=gt.SEASONED, scenario=gt.RATES_UP_200, daysPastDue=62, history=H_DEEP, originationPd12m=0.0004),
    dict(loan=gt.RISKY, scenario=gt.BASELINE, daysPastDue=0, history=H_CLEAN),
    dict(loan=gt.RISKY, scenario=gt.SEVERELY_ADVERSE, daysPastDue=45, history=H_RECENT),
    dict(loan=gt.FIFTEEN, scenario=gt.RATES_UP_200, daysPastDue=33, history=H_RECENT, monthsOnBook=30),
    dict(loan=gt.NO_MI, scenario=gt.CUSTOM, daysPastDue=66, history=H_DEEP),
    dict(loan=gt.NO_MI, scenario=gt.SEVERELY_ADVERSE, daysPastDue=0, history=H_RECENT, currentBalance=430000.0),
]


def main() -> None:
    a = MultiState()
    cases = []
    for case in CASES:
        expected = project(a, case)
        cases.append(dict(request=case, expected=expected))
        print(f"{case['loan']['loanId']:9s} {case['scenario']['name']:17s} dpd {case.get('daysPastDue', 0):3d} "
              f"pd12 {expected['pd12m']:.5f} life {expected['pdLifetime']:.5f} stage {expected['stage']} "
              f"eclLife {expected['eclLifetime']:.2f} conservation {expected['conservation']:.1e}")
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps({"model_version": a.card["version"], "survival_model_version": a.meta["version"],
                               "liquidation_haircut": gt.LIQUIDATION_HAIRCUT, "sicr_relative": gt.SICR_RELATIVE,
                               "sicr_absolute": gt.SICR_ABSOLUTE, "cases": cases}, indent=1) + "\n", encoding="utf-8")
    print(f"wrote {OUT} ({len(cases)} cases, model {a.card['version']})")


if __name__ == "__main__":
    main()
