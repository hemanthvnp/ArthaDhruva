"""Independent reference for the trajectory LSTM, for a cross-language parity test.

TrajectoryModelService (Java) turns a loan's months into (status, UPB ratio, modified) rows -- status parsed or
sentinel 12, UPB ratio clipped to [0, 5], the first two standardised with lstm_meta.json, the flag raw 0/1 -- and
runs lstm_model.onnx. This script does the same in plain Python/numpy from the committed artifacts and writes
inputs, expected feature vectors and expected probability to
risk-engine/src/test/resources/lstm_golden.json (TrajectoryParityTest).

It only reads the artifacts; nothing is trained or exported.

Run from backend/:  python golden_lstm.py
"""
from __future__ import annotations

import hashlib
import json
from pathlib import Path

import numpy as np
import onnxruntime as ort

RESOURCES = Path("risk-engine/src/main/resources")
OUT = Path("risk-engine/src/test/resources/lstm_golden.json")
SENTINEL = 12


def month(status, upb, mod=""):
    return dict(currentLoanDelinquencyStatus=status, currentActualUpb=upb, modificationFlag=mod)


CASES = [
    ("clean-12-months", dict(originalUpb=300000.0, months=[month("0", 300000.0 - 400.0 * i) for i in range(12)])),
    ("single-month", dict(originalUpb=250000.0, months=[month("0", 250000.0)])),
    ("late-payments", dict(originalUpb=300000.0, months=[month("0", 299000.0)] * 4 + [month("1", 298800.0), month("2", 298800.0),
                                                                                       month("3", 298800.0)] + [month("0", 298000.0)] * 3)),
    ("non-numeric-status", dict(originalUpb=300000.0, months=[month("0", 299000.0), month("RA", 299000.0), month("XX", 299000.0)])),
    ("modified", dict(originalUpb=300000.0, months=[month("0", 299000.0), month("0", 298500.0, "Y"), month("0", 298000.0, "Y")])),
    ("modified-lowercase-ignored", dict(originalUpb=300000.0, months=[month("0", 299000.0, "y"), month("0", 298500.0, "N")])),
    ("upb-above-original-clipped", dict(originalUpb=100000.0, months=[month("0", 900000.0), month("0", 100000.0)])),
    ("zero-balance", dict(originalUpb=300000.0, months=[month("0", 100000.0), month("0", 0.0)])),
    ("padded-status", dict(originalUpb=300000.0, months=[month(" 2 ", 299000.0), month("0", 298000.0)])),
]


def main() -> None:
    meta = json.loads((RESOURCES / "lstm_meta.json").read_text())
    mean, std = meta["feat_mean"], meta["feat_std"]
    onnx_path = RESOURCES / "lstm_model.onnx"
    session = ort.InferenceSession(str(onnx_path))

    cases = []
    for name, loan in CASES:
        original = loan["originalUpb"] if loan["originalUpb"] > 0 else 1.0
        rows = []
        for m in loan["months"]:
            try:
                status = float(int(m["currentLoanDelinquencyStatus"].strip()))
            except ValueError:
                status = float(SENTINEL)
            ratio = min(5.0, max(0.0, m["currentActualUpb"] / original))
            rows.append([np.float32((status - mean[0]) / std[0]), np.float32((ratio - mean[1]) / std[1]),
                         np.float32(1.0 if m["modificationFlag"] == "Y" else 0.0)])
        x = np.array([rows], dtype=np.float32)
        logit = float(session.run(None, {"input": x})[0][0][0])
        probability = 1.0 / (1.0 + np.exp(-logit))
        cases.append(dict(name=name, loan=loan, featureVector=[float(v) for r in rows for v in r], probability=float(probability)))
        print(f"{name:28s} p {probability:.6f}", flush=True)

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps({"artifact_sha256": hashlib.sha256(onnx_path.read_bytes()).hexdigest(), "cases": cases},
                              indent=2) + "\n", encoding="utf-8")
    print(f"wrote {OUT} ({len(cases)} cases)")


if __name__ == "__main__":
    main()
