"""Independent reference implementation of the term-structure engine, for a cross-language golden test.

survival.TermStructureEngine (Java) builds every model input itself -- loan age, mark-to-market LTV,
unemployment and house-price paths, quantization -- and then runs the regime recursion and the ECL
arithmetic. This script does all of that again in plain Python from the same exported artifacts, runs the
same ONNX graph, and writes inputs and expected outputs to
risk-engine/src/test/resources/term_structure_golden.json. TermStructureGoldenTest asserts the Java engine
reproduces them, so a change on either side that alters a single feature or formula fails the build.

Run from backend/ after export_survival_model.py:  python golden_term_structure.py
"""
from __future__ import annotations

import json
import math
from pathlib import Path

import numpy as np
import onnxruntime as ort

RESOURCES = Path("risk-engine/src/main/resources")
OUT = Path("risk-engine/src/test/resources/term_structure_golden.json")
LIQUIDATION_HAIRCUT = 0.15
SICR_RELATIVE, SICR_ABSOLUTE = 2.0, 0.005
MAX_MONTHS = 480
MAX_EXIT = 0.999
CHECKPOINTS = [1, 2, 3, 6, 12, 13, 24, 25, 36, 60, 120, 240, 360]

BASELINE = dict(name="BASELINE", description="", stressedMonths=0, unemploymentShockPp=0.0, unemploymentShockMonths=12,
                unemploymentRecoveryMonths=0, hpiShockPct=0.0, hpiShockMonths=12, hpiAnnualGrowthPct=3.0, rateShockBp=0.0)
ADVERSE = dict(BASELINE, name="ADVERSE", stressedMonths=12, unemploymentShockPp=3.0, unemploymentRecoveryMonths=36,
               hpiShockPct=10.0, hpiAnnualGrowthPct=2.0)
SEVERELY_ADVERSE = dict(BASELINE, name="SEVERELY_ADVERSE", stressedMonths=24, unemploymentShockPp=5.0,
                        unemploymentRecoveryMonths=48, hpiShockPct=25.0, hpiShockMonths=24, hpiAnnualGrowthPct=2.0)
RATES_UP_200 = dict(BASELINE, name="RATES_UP_200", rateShockBp=200.0)
CUSTOM = dict(name="CUSTOM", description="golden test", stressedMonths=6, unemploymentShockPp=2.0, unemploymentShockMonths=6,
              unemploymentRecoveryMonths=0, hpiShockPct=15.0, hpiShockMonths=18, hpiAnnualGrowthPct=1.0, rateShockBp=-100.0)


def loan(loan_id, credit, dti, upb, cltv, ltv, rate, term, borrowers, units, mi, occ, prop, purpose, channel, fthb, state, orig):
    return dict(loanId=loan_id, creditScore=credit, originalDti=dti, originalUpb=upb, originalCltv=cltv, originalLtv=ltv,
                originalInterestRate=rate, originalLoanTerm=term, numberOfBorrowers=borrowers, numberOfUnits=units,
                miPercent=mi, occupancyStatus=occ, propertyType=prop, loanPurpose=purpose, channel=channel,
                firstTimeHomebuyerFlag=fthb, propertyState=state, originationMonth=orig)


PRIME = loan("prime", 760, 32.0, 350000.0, 75.0, 75.0, 6.5, 360, 2, 1, 0.0, "P", "SF", "P", "R", "N", "CA", None)
SEASONED = loan("seasoned", 720, 38.0, 300000.0, 80.0, 80.0, 3.0, 360, 2, 1, 0.0, "P", "SF", "N", "R", "N", "TX", "2021-06")
RISKY = loan("risky", 640, 46.0, 250000.0, 97.0, 95.0, 7.25, 360, 1, 1, 30.0, "P", "SF", "P", "R", "Y", "FL", "2023-09")
FIFTEEN = loan("fifteen", 780, 25.0, 200000.0, 60.0, 60.0, 5.75, 180, 2, 1, 0.0, "P", "SF", "N", "R", "N", "OH", None)
NO_MI = loan("no-mi", 690, 44.0, 420000.0, 95.0, 95.0, 6.875, 360, 1, 1, 0.0, "I", "CO", "C", "B", "N", "NV", "2024-11")

CASES = [
    dict(loan=PRIME, scenario=BASELINE),
    dict(loan=PRIME, scenario=SEVERELY_ADVERSE),
    dict(loan=SEASONED, scenario=BASELINE),
    dict(loan=SEASONED, scenario=ADVERSE, currentBalance=281000.0, daysPastDue=45),
    dict(loan=SEASONED, scenario=RATES_UP_200, originationPd12m=0.0004),
    dict(loan=RISKY, scenario=BASELINE),
    dict(loan=RISKY, scenario=SEVERELY_ADVERSE),
    dict(loan=RISKY, scenario=BASELINE, daysPastDue=120),
    dict(loan=FIFTEEN, scenario=RATES_UP_200, monthsOnBook=30),
    dict(loan=NO_MI, scenario=CUSTOM),
    dict(loan=NO_MI, scenario=SEVERELY_ADVERSE, currentBalance=430000.0),
]


def ym(text: str) -> int:
    return int(text[:4]) * 12 + int(text[5:7]) - 1


def label(m: int) -> str:
    return f"{m // 12:04d}-{m % 12 + 1:02d}"


class Artifacts:
    def __init__(self) -> None:
        self.meta = json.loads((RESOURCES / "survival_model.json").read_text())
        self.mappings = json.loads((RESOURCES / "category_mappings.json").read_text())
        self.lgd = json.loads((RESOURCES / "lgd_model.json").read_text())
        rates = json.loads((RESOURCES / "market_rates.json").read_text())
        months = sorted(ym(k) for k in rates["monthly"])
        self.rate_first, self.rate_as_of = months[0], ym(rates["as_of_month"])
        by_month = {ym(k): v for k, v in rates["monthly"].items()}
        self.rates, last = [], None
        for m in range(months[0], months[-1] + 1):
            last = by_month.get(m, last)
            self.rates.append(last)
        macro = json.loads((RESOURCES / "macro_state.json").read_text())
        self.macro_first, self.origin = ym(macro["first_month"]), ym(macro["last_month"])
        self.states = macro["states"]
        regimes = self.meta["regimes"]
        # rows renormalized exactly as the Java model does on loading (they are stored in single precision)
        self.transition = [[row[0] / (row[0] + row[1]), row[1] / (row[0] + row[1])] for row in regimes["transition_matrix"]]
        self.regime_as_of = ym(regimes["as_of_month"])
        self.current_regime = regimes["current_state_index"]
        self.stressed_periods = [(ym(a), ym(b)) for a, b in regimes["stressed_periods"]]
        self.scalars = self.meta["overlay"]["hazard_scalars"]
        self.order = self.meta["features_in_order"]
        self.decimals = self.meta["feature_decimals"]
        self.lag = self.meta["rate_lock_lag_months"]
        options = ort.SessionOptions()
        options.intra_op_num_threads = 1
        self.session = ort.InferenceSession(str(RESOURCES / "survival_model.onnx"), options)

    def rate(self, m: int) -> float:
        return self.rates[max(0, min(len(self.rates) - 1, m - self.rate_first))]

    def series(self, state: str, name: str, m: int) -> float:
        values = self.states[state][name]
        return values[max(0, min(len(values) - 1, m - self.macro_first))]

    def decoded_regime(self, m: int) -> int:
        if m == self.regime_as_of:
            return self.current_regime
        return 1 if any(a <= m <= b for a, b in self.stressed_periods) else 0

    def regime_distribution(self, m: int, known_at: int) -> list[float]:
        anchor = min(known_at, self.regime_as_of)
        anchor = min(anchor, m)
        d = [0.0, 0.0]
        d[self.decoded_regime(anchor)] = 1.0
        p = self.transition
        for _ in range(m - anchor):
            d = [d[0] * p[0][0] + d[1] * p[1][0], d[0] * p[0][1] + d[1] * p[1][1]]
        return d


def quantize(value: float, decimals: int) -> np.float32:
    scale = 10.0 ** decimals
    return np.float32(np.rint(value * scale) / scale)


def amortization(rate_pct: float, term: int, payments: int) -> float:
    a = max(0, min(payments, term))
    r = rate_pct / 1200.0
    if r <= 0:
        return 1.0 - a / term
    grow_n = math.pow(1 + r, term)
    return (grow_n - math.pow(1 + r, a)) / (grow_n - 1)


def unemployment_delta(s: dict, k: int) -> float:
    if k <= 0:
        return 0.0
    if k <= s["unemploymentShockMonths"]:
        return s["unemploymentShockPp"] * k / s["unemploymentShockMonths"]
    if s["unemploymentRecoveryMonths"] == 0:
        return s["unemploymentShockPp"]
    return s["unemploymentShockPp"] * max(0.0, 1.0 - (k - s["unemploymentShockMonths"]) / s["unemploymentRecoveryMonths"])


def hpi_factor(s: dict, k: int) -> float:
    if k <= 0:
        return 1.0
    growth = math.log1p(s["hpiAnnualGrowthPct"] / 100.0) / 12.0
    if s["hpiShockPct"] == 0:
        return math.exp(growth * k)
    trough = math.log1p(-s["hpiShockPct"] / 100.0)
    if k <= s["hpiShockMonths"]:
        return math.exp(trough * k / s["hpiShockMonths"])
    return math.exp(trough + growth * (k - s["hpiShockMonths"]))


def fitted_lgd(a: Artifacts, l: dict) -> float:
    c = a.lgd["coefficients"]
    linear = (a.lgd["const"] + c["credit_score"] * l["creditScore"] + c["original_dti"] * l["originalDti"]
              + c["original_upb"] * l["originalUpb"] + c["original_cltv"] * l["originalCltv"]
              + c["original_interest_rate"] * l["originalInterestRate"])
    return max(0.0, min(1.0, 1.0 / (1.0 + math.exp(-linear))))


def plan(a: Artifacts, l: dict, s: dict, path_origin: int, first_k: int, months: int, age_at_origin: int,
         incentive: float, hpi_origination: float, balance_scale: float, lgd0: float, spread: float) -> dict:
    """The rows of one run of months, and the per-month exposure and LGD."""
    state = l["propertyState"]
    u0, h0 = a.series(state, "unemployment", path_origin), a.series(state, "hpi", path_origin)
    static = {"credit_score": l["creditScore"], "original_dti": l["originalDti"], "original_upb": l["originalUpb"],
              "original_cltv": l["originalCltv"], "original_ltv": l["originalLtv"], "rate_spread": spread,
              "original_loan_term": l["originalLoanTerm"], "number_of_borrowers": l["numberOfBorrowers"],
              "number_of_units": l["numberOfUnits"], "mi_percent": l["miPercent"]}
    codes = {"occupancy_status": l["occupancyStatus"], "property_type": l["propertyType"], "loan_purpose": l["loanPurpose"],
             "channel": l["channel"], "first_time_homebuyer_flag": l["firstTimeHomebuyerFlag"], "property_state": state}
    rows, months_out = [], []
    for i in range(months):
        k = first_k + i
        age = age_at_origin + k
        forced = k <= s["stressedMonths"]
        u = u0 + unemployment_delta(s, k)
        u_lag = a.series(state, "unemployment", path_origin + k - 12) if k <= 12 else u0 + unemployment_delta(s, k - 12)
        factor = hpi_factor(s, k)
        hpi = h0 * factor
        hpi_lag = a.series(state, "hpi", path_origin + k - 12) if k <= 12 else h0 * hpi_factor(s, k - 12)
        value_ratio = hpi_origination / hpi
        mtm = l["originalLtv"] * amortization(l["originalInterestRate"], l["originalLoanTerm"], age) * value_ratio
        balance_fraction = amortization(l["originalInterestRate"], l["originalLoanTerm"], age - 1) * balance_scale
        exposure = l["originalUpb"] * balance_fraction
        shortfall = 1.0 - (1.0 - LIQUIDATION_HAIRCUT) * 100.0 / (l["originalLtv"] * balance_fraction * value_ratio) - l["miPercent"] / 100.0
        lgd = min(1.0, max(lgd0, shortfall))
        varying = {"loan_age": age, "rate_incentive": incentive, "unemployment": u, "unemployment_change_12m": u - u_lag,
                   "hpi_change_12m": hpi / hpi_lag - 1.0, "mtm_ltv": mtm}
        first_row = len(rows)
        for regime in ([1] if forced else [0, 1]):
            row = []
            for f in a.order:
                if f in codes:
                    row.append(np.float32(a.mappings[f][codes[f]]))
                elif f == "regime_stressed":
                    row.append(np.float32(regime))
                else:
                    value = static[f] if f in static else varying[f]
                    row.append(quantize(value, a.decimals[f]) if f in a.decimals else np.float32(value))
            rows.append(row)
        months_out.append(dict(k=k, age=age, forced=forced, row=first_row, unemployment=u, hpi_factor=factor, mtm=mtm,
                               exposure=exposure, lgd=lgd))
    return dict(rows=rows, months=months_out)


def recurse(a: Artifacts, p: dict, hazards: np.ndarray, first_row: int, initial: list[float]) -> list[dict]:
    t = a.transition
    alive = list(initial)
    out = []
    for m in p["months"]:
        r = first_row + m["row"]
        if m["forced"]:
            at = [0.0, alive[0] + alive[1]]
            hd, hp = [0.0, hazards[r][0]], [0.0, hazards[r][1]]
        else:
            at = [alive[0] * t[0][0] + alive[1] * t[1][0], alive[0] * t[0][1] + alive[1] * t[1][1]]
            hd, hp = [hazards[r][0], hazards[r + 1][0]], [hazards[r][1], hazards[r + 1][1]]
        defaults = at[0] * hd[0] + at[1] * hd[1]
        prepays = at[0] * hp[0] + at[1] * hp[1]
        alive = [at[0] * (1 - hd[0] - hp[0]), at[1] * (1 - hd[1] - hp[1])]
        out.append(dict(survival=alive[0] + alive[1], default=defaults, prepay=prepays,
                        stressed=at[1] / (at[0] + at[1]) if at[0] + at[1] > 0 else 0.0))
    return out


def project(a: Artifacts, case: dict) -> dict:
    l, s = case["loan"], case["scenario"]
    months_on_book_in = case.get("monthsOnBook")
    new_loan = l["originationMonth"] is None and not months_on_book_in
    origination = ym(l["originationMonth"]) if l["originationMonth"] else (a.origin if new_loan else a.origin - months_on_book_in)
    on_book = max(0, a.origin - origination)
    months = min(l["originalLoanTerm"] - on_book, MAX_MONTHS)
    note = l["originalInterestRate"]
    lock = a.rate_as_of if new_loan else origination - a.lag
    spread = note - a.rate(lock)
    hpi_origination = a.series(l["propertyState"], "hpi", origination)
    scheduled = l["originalUpb"] * amortization(note, l["originalLoanTerm"], on_book)
    balance_scale = 1.0 if case.get("currentBalance") is None else case["currentBalance"] / scheduled
    lgd0 = fitted_lgd(a, l)
    market = a.rates[-1] + s["rateShockBp"] / 100.0
    main = plan(a, l, s, a.origin, 1, months, on_book, note - market, hpi_origination, balance_scale, lgd0, spread)
    dpd = case.get("daysPastDue") or 0
    reference = None
    if dpd < 30 and on_book > 0 and case.get("originationPd12m") is None:
        reference = plan(a, l, BASELINE, origination, on_book + 1, min(12, months), 0, note - a.rate(origination),
                         hpi_origination, 1.0, lgd0, spread)
    rows = main["rows"] + (reference["rows"] if reference else [])
    probs = a.session.run(None, {"input": np.array(rows, dtype=np.float32)})[1]
    hazards = np.zeros((len(rows), 2))
    for i in range(len(rows)):
        hd, hp = float(probs[i][1]) * a.scalars["default"], float(probs[i][2]) * a.scalars["prepay"]
        if hd + hp > MAX_EXIT:
            hd, hp = hd * MAX_EXIT / (hd + hp), hp * MAX_EXIT / (hd + hp)
        hazards[i] = (hd, hp)

    curve = recurse(a, main, hazards, 0, a.regime_distribution(a.origin, a.origin))
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
        if m["k"] in CHECKPOINTS or i == len(curve) - 1:
            points.append(dict(k=m["k"], month=label(a.origin + m["k"]), loanAge=m["age"], survival=c["survival"],
                               marginalDefault=c["default"], marginalPrepay=c["prepay"], cumulativeDefault=cum_d,
                               stressedProbability=c["stressed"], exposure=m["exposure"], lgd=m["lgd"],
                               discountedExpectedLoss=loss, unemployment=m["unemployment"],
                               housePriceIndex=100.0 * m["hpi_factor"], mtmLtv=m["mtm"]))
    reference_pd = case.get("originationPd12m")
    if reference:
        ref = recurse(a, reference, hazards, len(main["rows"]), a.regime_distribution(a.origin, origination))
        reference_pd = sum(c["default"] for c in ref)
    if dpd >= 90:
        stage = 3
    elif dpd >= 30:
        stage = 2
    elif reference_pd is not None and pd12 >= SICR_RELATIVE * reference_pd and pd12 - reference_pd >= SICR_ABSOLUTE:
        stage = 2
    else:
        stage = 1
    exposure = main["months"][0]["exposure"]
    impaired = main["months"][0]["lgd"] * exposure
    return dict(monthsOnBook=on_book, remainingMonths=months, pd12m=pd12, pd24m=pd24, pdLifetime=cum_d, prepayLifetime=cum_p,
                expectedLifeMonths=life, maturityProbability=curve[-1]["survival"], stage=stage, referencePd12m=reference_pd,
                exposure=exposure, lgd=lgd0, lgdPeak=lgd_peak, ecl12m=ecl12, eclLifetime=ecl_life,
                eclIfrs9=ecl12 if stage == 1 else ecl_life if stage == 2 else impaired,
                eclCecl=impaired if stage == 3 else ecl_life, rateSpread=spread, points=points)


def main() -> None:
    a = Artifacts()
    cases = []
    for case in CASES:
        expected = project(a, case)
        cases.append(dict(request=case, expected=expected))
        print(f"{case['loan']['loanId']:9s} {case['scenario']['name']:17s} pd12 {expected['pd12m']:.5f} life {expected['pdLifetime']:.5f} "
              f"stage {expected['stage']} ecl12 {expected['ecl12m']:.2f} eclLife {expected['eclLifetime']:.2f} lgdPeak {expected['lgdPeak']:.4f}")
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps({"model_version": a.meta["version"], "liquidation_haircut": LIQUIDATION_HAIRCUT,
                               "sicr_relative": SICR_RELATIVE, "sicr_absolute": SICR_ABSOLUTE, "cases": cases}, indent=1) + "\n",
                   encoding="utf-8")
    print(f"wrote {OUT} ({len(cases)} cases, model {a.meta['version']})")


if __name__ == "__main__":
    main()
