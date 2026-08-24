"""Retrain the 16-field LightGBM PD model (matching default_risk_model.ipynb), fix the
calibration issue found in that notebook, and export everything the Java backend needs:
  - risk-engine/src/main/resources/model.onnx        (LightGBM, ONNX format)
  - risk-engine/src/main/resources/category_mappings.json  (categorical string -> int code)
  - risk-engine/src/main/resources/calibration.json   (isotonic regression breakpoints)

Categorical features must be integer-encoded explicitly before ONNX export (ONNX's tree
ensemble format has no native categorical type -- it needs the same integer codes LightGBM
used internally at training time). The Java side must apply the same string->int mapping to
incoming categorical values before calling the model.
"""
import json
import numpy as np
import polars as pl
import lightgbm as lgb
from sklearn.model_selection import train_test_split
from sklearn.isotonic import IsotonicRegression
from sklearn.metrics import roc_auc_score, average_precision_score
from onnxmltools.convert import convert_lightgbm
from onnxmltools.convert.common.data_types import FloatTensorType

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
TARGET = "default_target"

print("Loading data...")
df = (
    pl.scan_parquet("../data/processed/loan_level/orig_year=*/orig_quarter=*/*.parquet")
    .select(ALL_FEATURES + [TARGET, "current_loan_age"])
    .collect()
)
seasoned = df.filter(pl.col("current_loan_age") >= 24).drop_nulls(subset=[TARGET])
pdf = seasoned.select(ALL_FEATURES + [TARGET]).to_pandas()

print("Encoding categoricals to explicit integer codes (so the mapping is known and exportable)...")
category_mappings = {}
for col in CATEGORICAL_FEATURES:
    pdf[col] = pdf[col].astype("category")
    categories = list(pdf[col].cat.categories)
    category_mappings[col] = {cat: i for i, cat in enumerate(categories)}
    pdf[col] = pdf[col].cat.codes.astype("float32")  # -1 for unseen/null, matches LightGBM's own convention
    pdf.loc[pdf[col] < 0, col] = -1.0

for col in NUMERIC_FEATURES:
    pdf[col] = pdf[col].astype("float32")

X = pdf[ALL_FEATURES]
y = pdf[TARGET]

# 3-way split: train / calibration / final holdout (avoids double-dipping, same lesson as the
# calibration check in default_risk_model.ipynb)
X_train, X_rest, y_train, y_rest = train_test_split(X, y, test_size=0.3, random_state=42, stratify=y)
X_calib, X_holdout, y_calib, y_holdout = train_test_split(X_rest, y_rest, test_size=0.5, random_state=42, stratify=y_rest)
print(f"Train: {len(X_train):,}  Calibration: {len(X_calib):,}  Holdout: {len(X_holdout):,}")

print("Training LightGBM...")
scale_pos_weight = (y_train == 0).sum() / (y_train == 1).sum()
model = lgb.LGBMClassifier(
    n_estimators=300, learning_rate=0.05, num_leaves=31,
    scale_pos_weight=scale_pos_weight, random_state=42, verbose=-1,
)
model.fit(X_train, y_train, categorical_feature=CATEGORICAL_FEATURES)

raw_calib_proba = model.predict_proba(X_calib)[:, 1]
raw_holdout_proba = model.predict_proba(X_holdout)[:, 1]

print("Fitting isotonic calibration...")
iso = IsotonicRegression(out_of_bounds="clip")
iso.fit(raw_calib_proba, y_calib)
calibrated_holdout_proba = iso.predict(raw_holdout_proba)

print(f"Holdout ROC-AUC -- raw: {roc_auc_score(y_holdout, raw_holdout_proba):.4f}  "
      f"calibrated: {roc_auc_score(y_holdout, calibrated_holdout_proba):.4f}")
print(f"Holdout PR-AUC  -- raw: {average_precision_score(y_holdout, raw_holdout_proba):.4f}  "
      f"calibrated: {average_precision_score(y_holdout, calibrated_holdout_proba):.4f}")
print(f"Over-prediction factor -- raw: {raw_holdout_proba.mean()/y_holdout.mean():.2f}x  "
      f"calibrated: {calibrated_holdout_proba.mean()/y_holdout.mean():.2f}x")

import joblib
joblib.dump({"model": model, "iso": iso, "category_mappings": category_mappings,
             "X_holdout": X_holdout, "y_holdout": y_holdout}, "trained_checkpoint.joblib")
print("Saved trained_checkpoint.joblib (in case ONNX export needs another attempt)")

print("\nConverting to ONNX...")
initial_type = [("input", FloatTensorType([None, len(ALL_FEATURES)]))]
onnx_model = convert_lightgbm(model, initial_types=initial_type, target_opset=15)

import os
RESOURCES_DIR = "risk-engine/src/main/resources"
os.makedirs(RESOURCES_DIR, exist_ok=True)

with open(f"{RESOURCES_DIR}/model.onnx", "wb") as f:
    f.write(onnx_model.SerializeToString())
print(f"Saved {RESOURCES_DIR}/model.onnx")

with open(f"{RESOURCES_DIR}/category_mappings.json", "w") as f:
    json.dump(category_mappings, f, indent=2)
print(f"Saved {RESOURCES_DIR}/category_mappings.json")

with open(f"{RESOURCES_DIR}/feature_order.json", "w") as f:
    json.dump({"numeric_features": NUMERIC_FEATURES, "categorical_features": CATEGORICAL_FEATURES,
               "all_features_in_order": ALL_FEATURES}, f, indent=2)
print(f"Saved {RESOURCES_DIR}/feature_order.json")

calibration_export = {
    "x_breakpoints": iso.X_thresholds_.tolist() if hasattr(iso, "X_thresholds_") else iso.f_.x.tolist(),
    "y_breakpoints": iso.y_thresholds_.tolist() if hasattr(iso, "y_thresholds_") else iso.f_.y.tolist(),
}
with open(f"{RESOURCES_DIR}/calibration.json", "w") as f:
    json.dump(calibration_export, f)
print(f"Saved {RESOURCES_DIR}/calibration.json ({len(calibration_export['x_breakpoints'])} breakpoints)")

# Verify ONNX output matches the original model's raw output before trusting it in Java
import onnxruntime as ort
sess = ort.InferenceSession(f"{RESOURCES_DIR}/model.onnx")
sample = X_holdout.iloc[:20].to_numpy().astype(np.float32)
onnx_out = sess.run(None, {"input": sample})
onnx_proba = np.array(onnx_out[1])[:, 1] if isinstance(onnx_out[1], np.ndarray) else np.array([d[1] for d in onnx_out[1]])
sklearn_proba = model.predict_proba(X_holdout.iloc[:20])[:, 1]
max_diff = np.abs(onnx_proba - sklearn_proba).max()
print(f"\nONNX vs. sklearn max prediction difference on 20 sample rows: {max_diff:.6f}")
print("MATCH" if max_diff < 1e-4 else "MISMATCH -- investigate before trusting the exported model")
