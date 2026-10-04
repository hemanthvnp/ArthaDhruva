"""Independent reference for the origination PD model, for a cross-language parity test.

score.ModelService (Java) builds the 16-column feature vector itself -- the rate spread over the PMMS rate at
the rate-lock month, rounding to the training grid, category codes -- runs model.onnx and applies the binned
isotonic calibration. This script does the same in plain Python/numpy from the committed artifacts and writes
the inputs, the expected feature vectors and the expected raw/calibrated probabilities to
risk-engine/src/test/resources/pd_model_golden.json. PdModelParityTest asserts the Java service reproduces them.

It only reads the artifacts (model.onnx, calibration.json, category_mappings.json, feature_order.json,
market_rates.json, pd_model_card.json); nothing is trained or exported.

Conventions (the same as golden_term_structure.py and the training pipeline): the spread is computed in float64
and cast to float32 (credit_common.with_spread), then rounded to the 3-decimal grid in float64 (numpy.rint, half to
even) and cast back to float32 -- so an exact tie resolves by the float32 representation error, not half-to-even.
The other continuous inputs are float32 values rounded the same way.

Run from backend/:  python golden_pd_model.py
"""
from __future__ import annotations

import json
from pathlib import Path

import numpy as np
import onnxruntime as ort

RESOURCES = Path("risk-engine/src/main/resources")
OUT = Path("risk-engine/src/test/resources/pd_model_golden.json")


def loan(loan_id, credit, dti, upb, cltv, ltv, rate, term, borrowers, units, mi, occ, prop, purpose, channel, fthb,
         state, orig):
    return dict(loanId=loan_id, creditScore=credit, originalDti=dti, originalUpb=upb, originalCltv=cltv, originalLtv=ltv,
                originalInterestRate=rate, originalLoanTerm=term, numberOfBorrowers=borrowers, numberOfUnits=units,
                miPercent=mi, occupancyStatus=occ, propertyType=prop, loanPurpose=purpose, channel=channel,
                firstTimeHomebuyerFlag=fthb, propertyState=state, originationMonth=orig)


LOANS = [
    loan("prime", 760, 32.0, 350000.0, 75.0, 75.0, 6.5, 360, 2, 1, 0.0, "P", "SF", "P", "R", "N", "CA", "2023-03"),
    loan("risky", 640, 46.0, 250000.0, 97.0, 95.0, 7.25, 360, 1, 1, 30.0, "P", "SF", "P", "R", "Y", "FL", "2023-09"),
    loan("safest", 850, 10.0, 180000.0, 20.0, 20.0, 5.0, 360, 2, 1, 0.0, "P", "SF", "N", "R", "N", "CA", "2021-02"),
    loan("riskiest", 560, 60.0, 300000.0, 110.0, 105.0, 8.5, 360, 1, 1, 35.0, "I", "CO", "C", "B", "Y", "FL", "2023-10"),
    # 6.5 - PMMS(2024-06)=6.9175 is -0.4175 (float64 -0.41750000000000043): a tie on the 3-decimal grid. Rounded from
    # the double it would be -0.418; training casts to float32 first, which gives -0.417 (see PdModelParityTest)
    loan("spread-tie", 700, 40.0, 300000.0, 85.0, 85.0, 6.5, 360, 1, 1, 12.0, "P", "SF", "P", "R", "N", "TX", "2024-08"),
    loan("unseen-categories", 720, 36.0, 260000.0, 80.0, 80.0, 6.0, 360, 2, 1, 0.0, "Z", "XX", "Q", "W", "M", "ZZ", "2022-06"),
    loan("new-application", 735, 38.0, 410000.0, 90.0, 90.0, 6.75, 360, 2, 1, 25.0, "P", "PU", "P", "C", "Y", "WA", None),
    loan("before-rate-history", 700, 35.0, 120000.0, 70.0, 70.0, 9.0, 360, 1, 1, 0.0, "P", "SF", "N", "R", "N", "OH", "1990-01"),
    loan("off-grid-inputs", 701, 36.4, 243000.4, 80.3, 79.6, 6.375, 360, 2, 1, 17.6, "P", "SF", "P", "R", "N", "NV", "2022-11"),
    loan("fifteen-year-multi-unit", 780, 28.0, 220000.0, 60.0, 60.0, 5.75, 180, 2, 2, 0.0, "S", "MH", "N", "R", "N", "NY", "2021-08"),
]


def ym(text: str) -> int:
    return int(text[:4]) * 12 + int(text[5:7]) - 1


def market_rates(path: Path):
    """Monthly PMMS rates, forward-filled over any gap and clamped to the series ends (MarketData)."""
    doc = json.loads(path.read_text())
    monthly = {ym(k): float(v) for k, v in doc["monthly"].items()}
    first, last = min(monthly), max(monthly)
    series, current = [], None
    for m in range(first, last + 1):
        current = monthly.get(m, current)
        series.append(current)
    return (lambda month: series[min(max(month - first, 0), len(series) - 1)]), ym(doc["as_of_month"])


def main() -> None:
    mappings = json.loads((RESOURCES / "category_mappings.json").read_text())
    order = json.loads((RESOURCES / "feature_order.json").read_text())
    calib = json.loads((RESOURCES / "calibration.json").read_text())
    card = json.loads((RESOURCES / "pd_model_card.json").read_text())
    rate_at, rate_as_of = market_rates(RESOURCES / "market_rates.json")
    features, decimals, lag = order["all_features_in_order"], order["feature_decimals"], order["rate_lock_lag_months"]
    xs, ys = np.array(calib["x_breakpoints"]), np.array(calib["y_breakpoints"])

    def quantize(value, name):
        d = decimals.get(name)
        return np.float32(value) if d is None else np.float32(np.rint(float(value) * 10.0 ** d) / 10.0 ** d)

    session = ort.InferenceSession(str(RESOURCES / "model.onnx"))
    cases = []
    for l in LOANS:
        lock = (ym(l["originationMonth"]) if l["originationMonth"] else rate_as_of) - (lag if l["originationMonth"] else 0)
        spread = l["originalInterestRate"] - rate_at(lock)
        raw_inputs = {"credit_score": l["creditScore"], "original_dti": l["originalDti"], "original_upb": l["originalUpb"],
                      "original_cltv": l["originalCltv"], "original_ltv": l["originalLtv"], "original_loan_term": l["originalLoanTerm"],
                      "number_of_borrowers": l["numberOfBorrowers"], "number_of_units": l["numberOfUnits"],
                      "mi_percent": l["miPercent"]}
        categorical = {"occupancy_status": l["occupancyStatus"], "property_type": l["propertyType"], "loan_purpose": l["loanPurpose"],
                       "channel": l["channel"], "first_time_homebuyer_flag": l["firstTimeHomebuyerFlag"],
                       "property_state": l["propertyState"]}
        vector = []
        for f in features:
            if f == "rate_spread":
                vector.append(quantize(np.float32(spread), f))  # float32 first, as training's with_spread does
            elif f in categorical:
                vector.append(np.float32(mappings[f].get(categorical[f], -1)))
            else:
                vector.append(quantize(np.float32(raw_inputs[f]), f))
        x = np.array([vector], dtype=np.float32)
        raw = float(session.run(None, {"input": x})[1][0, 1])
        calibrated = float(np.interp(raw, xs, ys))
        region = "below" if raw <= xs[0] else "above" if raw >= xs[-1] else "inside"
        cases.append(dict(loan=l, rateSpread=float(vector[features.index("rate_spread")]),
                          featureVector=[float(v) for v in vector], rawProbability=raw, calibratedProbability=calibrated,
                          calibrationRegion=region))
        print(f"{l['loanId']:24s} spread {vector[features.index('rate_spread')]:+.3f}  raw {raw:.6f}  "
              f"calibrated {calibrated:.6f}  [{region}]", flush=True)

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps({"model_version": card["version"], "artifact_sha256": card["artifact_sha256"],
                               "features": features, "cases": cases}, indent=2) + "\n", encoding="utf-8")
    print(f"wrote {OUT} ({len(cases)} cases, model {card['version']})")


if __name__ == "__main__":
    main()
