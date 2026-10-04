"""Independent reference for the early-warning delinquency model, for a cross-language parity test.

EarlyWarningModelService (Java) lays the 32 columns out itself -- numerics, then categoricals encoded through
early_warning_category_mappings.json (-1 for a value the mapping does not contain), then the four boolean flags --
runs early_warning_model.onnx and applies the isotonic calibration. This script does the same in plain
Python/numpy from the committed artifacts and writes inputs, expected feature vectors and expected raw/calibrated
risk to risk-engine/src/test/resources/early_warning_golden.json (EarlyWarningParityTest).

It only reads the artifacts; nothing is trained or exported. Inputs are not rounded to a grid (this model has none):
doubles are cast to float32, as the service does.

Run from backend/:  python golden_early_warning.py
"""
from __future__ import annotations

import hashlib
import json
import re
from pathlib import Path

import numpy as np
import onnxruntime as ort

RESOURCES = Path("risk-engine/src/main/resources")
OUT = Path("risk-engine/src/test/resources/early_warning_golden.json")

# An in-distribution loan; the cases below change a few fields at a time.
BASE = dict(creditScore=726, originalDti=37.0, originalUpb=294000.0, originalCltv=95.0, originalLtv=95.0,
            originalInterestRate=5.5, originalLoanTerm=360, numberOfBorrowers=1, numberOfUnits=1, miPercent=30.0,
            loanAge=15, eltv=96.0, currentInterestRate=5.5, upbPaydownRatio=0.01999482993197277, rateLockSeverity=-1.876,
            eltvChange3m=4.0, upbPaydownChange3m=0.0035494897959184035, rateLockSeverityChange3m=-0.06399999999999961,
            eltvChange6m=1.0, upbPaydownChange6m=0.007050612244898002, rateLockSeverityChange6m=-0.14100000000000001,
            occupancyStatus="P", propertyType="SF", loanPurpose="P", channel="R", firstTimeHomebuyerFlag="Y",
            propertyState="MI", hmmRegime="calm", priorAssistance=False, priorModification=False, priorDisaster=False,
            upbStalled=False)

CASES = [
    ("base", {}),
    ("stressed-regime-other-state", dict(hmmRegime="stressed", propertyState="NM", loanPurpose="C", loanAge=31, eltv=52.0)),
    ("prior-assistance-only", dict(priorAssistance=True)),
    ("prior-modification-only", dict(priorModification=True)),
    ("prior-disaster-only", dict(priorDisaster=True)),
    ("upb-stalled-only", dict(upbStalled=True)),
    ("all-flags", dict(priorAssistance=True, priorModification=True, priorDisaster=True, upbStalled=True)),
    # values the category mappings do not contain: -1, not an error and not the 'unknown' regime (code 2)
    ("unseen-state", dict(propertyState="ZZ")),
    ("unseen-regime", dict(hmmRegime="mystery")),
    ("unseen-everywhere", dict(occupancyStatus="Z", propertyType="XX", loanPurpose="Q", channel="W",
                               firstTimeHomebuyerFlag="M", propertyState="ZZ", hmmRegime="mystery")),
    ("known-unknown-regime", dict(hmmRegime="unknown")),
    ("deep-delinquency-profile", dict(creditScore=560, originalDti=55.0, eltv=118.0, rateLockSeverity=2.5, loanAge=40,
                                      eltvChange3m=12.0, eltvChange6m=20.0, priorAssistance=True, upbStalled=True)),
]


def snake(name: str) -> str:
    return re.sub(r"(?<!^)(?=[A-Z])|(?<=[a-z])(?=\d)", "_", name).lower()


def main() -> None:
    order = json.loads((RESOURCES / "early_warning_feature_order.json").read_text())
    mappings = json.loads((RESOURCES / "early_warning_category_mappings.json").read_text())
    calib = json.loads((RESOURCES / "early_warning_calibration.json").read_text())
    features = order["all_features_in_order"]
    xs, ys = np.array(calib["x_breakpoints"]), np.array(calib["y_breakpoints"])
    onnx_path = RESOURCES / "early_warning_model.onnx"
    session = ort.InferenceSession(str(onnx_path))

    cases = []
    for name, changes in CASES:
        loan = BASE | changes
        values = {snake(k): v for k, v in loan.items()}
        vector = []
        for f in features:
            if f in mappings:
                vector.append(np.float32(mappings[f].get(values[f], -1)))
            else:
                vector.append(np.float32(float(values[f])))  # numerics, and booleans as 1.0 / 0.0
        raw_out = session.run(None, {"input": np.array([vector], dtype=np.float32)})[1]
        raw = float(raw_out[0][1])  # ZipMap: one {class label: probability} map per row
        calibrated = float(np.interp(raw, xs, ys))
        cases.append(dict(name=name, loan=loan, featureVector=[float(v) for v in vector], rawRisk=raw,
                          calibratedRisk=calibrated))
        print(f"{name:28s} raw {raw:.6f}  calibrated {calibrated:.6f}", flush=True)

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps({"artifact_sha256": hashlib.sha256(onnx_path.read_bytes()).hexdigest(),
                               "features": features, "cases": cases}, indent=2) + "\n", encoding="utf-8")
    print(f"wrote {OUT} ({len(cases)} cases)")


if __name__ == "__main__":
    main()
