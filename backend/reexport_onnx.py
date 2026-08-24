"""Re-run just the ONNX conversion step from the saved checkpoint, with zipmap=False for a
plain float tensor output instead of the ZipMap sequence<map> format -- much simpler to consume
from Java (a plain [N,2] float tensor vs. ONNX Runtime's OnnxSequence/OnnxMap API)."""
import json
import numpy as np
import joblib
from onnxmltools.convert import convert_lightgbm
from onnxmltools.convert.common.data_types import FloatTensorType

data = joblib.load("trained_checkpoint.joblib")
model = data["model"]
X_holdout = data["X_holdout"]

NUMERIC_FEATURES = [
    "credit_score", "original_dti", "original_upb", "original_cltv", "original_ltv",
    "original_interest_rate", "original_loan_term", "number_of_borrowers",
    "number_of_units", "mi_percent",
]
CATEGORICAL_FEATURES = [
    "occupancy_status", "property_type", "loan_purpose", "channel",
    "first_time_homebuyer_flag", "property_state",
]
ALL_FEATURES = NUMERIC_FEATURES + CATEGORICAL_FEATURES

initial_type = [("input", FloatTensorType([None, len(ALL_FEATURES)]))]
onnx_model = convert_lightgbm(model, initial_types=initial_type, target_opset=15, zipmap=False)

RESOURCES_DIR = "risk-engine/src/main/resources"
with open(f"{RESOURCES_DIR}/model.onnx", "wb") as f:
    f.write(onnx_model.SerializeToString())
print(f"Re-saved {RESOURCES_DIR}/model.onnx with zipmap=False")

import onnxruntime as ort
sess = ort.InferenceSession(f"{RESOURCES_DIR}/model.onnx")
print("=== Outputs ===")
for out in sess.get_outputs():
    print(out.name, out.shape, out.type)

sample = X_holdout.iloc[:3].to_numpy().astype(np.float32)
result = sess.run(None, {"input": sample})
print("\n=== Sample output ===")
for i, r in enumerate(result):
    print(f"output[{i}]:", r)

sklearn_proba = model.predict_proba(X_holdout.iloc[:20])[:, 1]
onnx_proba = sess.run(None, {"input": X_holdout.iloc[:20].to_numpy().astype(np.float32)})[1][:, 1]
max_diff = np.abs(onnx_proba - sklearn_proba).max()
print(f"\nONNX vs. sklearn max diff (20 rows): {max_diff:.6f}  {'MATCH' if max_diff < 1e-4 else 'MISMATCH'}")
