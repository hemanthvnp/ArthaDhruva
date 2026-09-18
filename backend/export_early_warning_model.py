"""Retrain the early-warning delinquency model (matching notebooks/early_warning_delinquency.ipynb,
v3 feature set), and export everything the Java backend needs:
  - risk-engine/src/main/resources/early_warning_model.onnx              (LightGBM, ONNX format)
  - risk-engine/src/main/resources/early_warning_category_mappings.json  (categorical string -> int code)
  - risk-engine/src/main/resources/early_warning_feature_order.json      (numeric/categorical/boolean order)
  - risk-engine/src/main/resources/early_warning_calibration.json        (isotonic regression breakpoints)

Question the model answers: for a currently-performing loan (0 DPD, never delinquent before),
what's the probability it goes 30+ days late within the next 3 months? See the notebook for the
full target definition, the COVID-era forbearance caveat, and the v1->v2->v3 feature history.

Categorical features (including hmm_regime) must be integer-encoded explicitly before ONNX export,
same reasoning as export_model.py. Boolean features are encoded as 0.0/1.0 floats directly (no
mapping needed). The Java side must apply the same encoding before calling the model.

Raw model output is trained on a 10:1 downsampled target rate, so it systematically overpredicts
real-world risk -- isotonic calibration (fit on the natural-rate calibration holdout, which is a
separate file built from different quarters at the true population rate, not a split of the
training data) corrects this, exactly as the notebook demonstrates.
"""
import json
import numpy as np
import polars as pl
import pandas as pd
import lightgbm as lgb
from sklearn.model_selection import train_test_split
from sklearn.isotonic import IsotonicRegression
from sklearn.metrics import roc_auc_score, average_precision_score
from onnxmltools.convert import convert_lightgbm
from onnxmltools.convert.common.data_types import FloatTensorType

NUMERIC_FEATURES = [
    "credit_score", "original_dti", "original_upb", "original_cltv", "original_ltv",
    "original_interest_rate", "original_loan_term", "number_of_borrowers", "number_of_units", "mi_percent",
    "loan_age", "eltv", "current_interest_rate", "upb_paydown_ratio", "rate_lock_severity",
    "eltv_change_3m", "upb_paydown_change_3m", "rate_lock_severity_change_3m",
    "eltv_change_6m", "upb_paydown_change_6m", "rate_lock_severity_change_6m",
]
CATEGORICAL_FEATURES = [
    "occupancy_status", "property_type", "loan_purpose", "channel",
    "first_time_homebuyer_flag", "property_state", "hmm_regime",
]
BOOLEAN_FEATURES = ["prior_assistance", "prior_modification", "prior_disaster", "upb_stalled"]
ALL_FEATURES = NUMERIC_FEATURES + CATEGORICAL_FEATURES + BOOLEAN_FEATURES
TARGET = "target"

print("Loading training set (downsampled) and calibration holdout (natural rate)...")
train_raw = pl.read_parquet("../data/processed/early_warning_delinquency_v3.parquet")
calib_raw = pl.read_parquet("../data/processed/early_warning_calibration_holdout_v3.parquet")
print(f"Training set: {train_raw.height:,} rows, target_rate={train_raw[TARGET].mean():.4%}")
print(f"Calibration holdout: {calib_raw.height:,} rows, target_rate={calib_raw[TARGET].mean():.4%}")

train_pdf = train_raw.select(ALL_FEATURES + [TARGET]).to_pandas()
calib_pdf = calib_raw.select(ALL_FEATURES + [TARGET]).to_pandas()
# Target is boolean in the parquet; onnxmltools' LightGBM classifier export chokes on numpy bool
# class labels (tries to .encode() them as if they were strings), so use plain 0/1 ints instead.
train_pdf[TARGET] = train_pdf[TARGET].astype(int)
calib_pdf[TARGET] = calib_pdf[TARGET].astype(int)

print("Encoding categoricals to explicit integer codes, over the union of train+calibration "
      "categories (so the mapping the Java side gets is complete, not just what training happened to see)...")
category_mappings = {}
for col in CATEGORICAL_FEATURES:
    categories = sorted(set(train_pdf[col].dropna().unique()) | set(calib_pdf[col].dropna().unique()))
    category_mappings[col] = {cat: i for i, cat in enumerate(categories)}
    for pdf in (train_pdf, calib_pdf):
        codes = pdf[col].map(category_mappings[col])
        pdf[col] = codes.fillna(-1).astype("float32")

for col in NUMERIC_FEATURES:
    for pdf in (train_pdf, calib_pdf):
        pdf[col] = pdf[col].astype("float32")

for col in BOOLEAN_FEATURES:
    # upb_stalled (and in principle the others) can be null for a loan's first eligible month --
    # no prior-month comparison point yet. Treat "no comparison point yet" as not-stalled, same
    # convention as the notebook.
    for pdf in (train_pdf, calib_pdf):
        pdf[col] = pdf[col].fillna(False).astype("float32")

print("\nSplitting training set by loan (not by row) -- same leakage-prevention rule as the notebook. "
      "Note: this split is only for a sanity-check evaluation below; every row of train_raw's 80% "
      "share still trains the exported model, matching the notebook's own X_train.")
unique_loans = train_raw.select("loan_sequence_number").unique()["loan_sequence_number"].to_list()
train_loans, test_loans = train_test_split(unique_loans, test_size=0.2, random_state=42)
train_loans = set(train_loans)
train_mask = train_raw["loan_sequence_number"].is_in(train_loans).to_numpy()

X_train, y_train = train_pdf.loc[train_mask, ALL_FEATURES], train_pdf.loc[train_mask, TARGET]
X_test, y_test = train_pdf.loc[~train_mask, ALL_FEATURES], train_pdf.loc[~train_mask, TARGET]
print(f"Train: {len(X_train):,}  Test: {len(X_test):,}")

print("\nTraining LightGBM (same hyperparameters as the notebook -- no scale_pos_weight, since "
      "the training set is already downsampled to a fixed 10:1 ratio)...")
model = lgb.LGBMClassifier(n_estimators=300, learning_rate=0.05, num_leaves=31, random_state=42, verbose=-1)
model.fit(X_train, y_train, categorical_feature=CATEGORICAL_FEATURES)

test_proba = model.predict_proba(X_test)[:, 1]
print(f"Downsampled test set -- ROC-AUC: {roc_auc_score(y_test, test_proba):.4f}   "
      f"PR-AUC: {average_precision_score(y_test, test_proba):.4f} (PR-AUC not meaningful here -- see calibration check below)")

X_calib, y_calib = calib_pdf[ALL_FEATURES], calib_pdf[TARGET]
calib_proba_raw = model.predict_proba(X_calib)[:, 1]
print(f"Natural-rate holdout -- ROC-AUC: {roc_auc_score(y_calib, calib_proba_raw):.4f}   "
      f"PR-AUC: {average_precision_score(y_calib, calib_proba_raw):.4f}  "
      f"(base rate {y_calib.mean():.4%})")

print("\nFitting isotonic calibration on half the natural-rate holdout, evaluating on the other half "
      "(same held-out-from-fitting rule as the notebook)...")
calib_fit_idx, calib_eval_idx = train_test_split(np.arange(len(y_calib)), test_size=0.5, random_state=42)
iso = IsotonicRegression(out_of_bounds="clip")
iso.fit(calib_proba_raw[calib_fit_idx], y_calib.to_numpy()[calib_fit_idx])

calib_proba_cal_eval = iso.predict(calib_proba_raw[calib_eval_idx])
y_eval = y_calib.to_numpy()[calib_eval_idx]
print(f"Eval half -- ROC-AUC raw: {roc_auc_score(y_eval, calib_proba_raw[calib_eval_idx]):.4f}  "
      f"calibrated: {roc_auc_score(y_eval, calib_proba_cal_eval):.4f}  (should match: monotonic transform)")
print(f"Over-prediction factor -- raw: {calib_proba_raw[calib_eval_idx].mean()/y_eval.mean():.2f}x  "
      f"calibrated: {calib_proba_cal_eval.mean()/y_eval.mean():.2f}x")

import joblib
joblib.dump({"model": model, "iso": iso, "category_mappings": category_mappings,
             "X_calib": X_calib, "y_calib": y_calib}, "early_warning_trained_checkpoint.joblib")
print("Saved early_warning_trained_checkpoint.joblib (in case ONNX export needs another attempt)")

print("\nConverting to ONNX...")
initial_type = [("input", FloatTensorType([None, len(ALL_FEATURES)]))]
onnx_model = convert_lightgbm(model, initial_types=initial_type, target_opset=15)

import os
RESOURCES_DIR = "risk-engine/src/main/resources"
os.makedirs(RESOURCES_DIR, exist_ok=True)

with open(f"{RESOURCES_DIR}/early_warning_model.onnx", "wb") as f:
    f.write(onnx_model.SerializeToString())
print(f"Saved {RESOURCES_DIR}/early_warning_model.onnx")

with open(f"{RESOURCES_DIR}/early_warning_category_mappings.json", "w") as f:
    json.dump(category_mappings, f, indent=2)
print(f"Saved {RESOURCES_DIR}/early_warning_category_mappings.json")

with open(f"{RESOURCES_DIR}/early_warning_feature_order.json", "w") as f:
    json.dump({
        "numeric_features": NUMERIC_FEATURES,
        "categorical_features": CATEGORICAL_FEATURES,
        "boolean_features": BOOLEAN_FEATURES,
        "all_features_in_order": ALL_FEATURES,
    }, f, indent=2)
print(f"Saved {RESOURCES_DIR}/early_warning_feature_order.json")

calibration_export = {
    "x_breakpoints": iso.X_thresholds_.tolist() if hasattr(iso, "X_thresholds_") else iso.f_.x.tolist(),
    "y_breakpoints": iso.y_thresholds_.tolist() if hasattr(iso, "y_thresholds_") else iso.f_.y.tolist(),
}
with open(f"{RESOURCES_DIR}/early_warning_calibration.json", "w") as f:
    json.dump(calibration_export, f)
print(f"Saved {RESOURCES_DIR}/early_warning_calibration.json ({len(calibration_export['x_breakpoints'])} breakpoints)")

# Verify ONNX output matches the original model's raw output before trusting it in Java
import onnxruntime as ort
sess = ort.InferenceSession(f"{RESOURCES_DIR}/early_warning_model.onnx")
sample = X_calib.iloc[:20].to_numpy().astype(np.float32)
onnx_out = sess.run(None, {"input": sample})
onnx_proba = np.array(onnx_out[1])[:, 1] if isinstance(onnx_out[1], np.ndarray) else np.array([d[1] for d in onnx_out[1]])
sklearn_proba = model.predict_proba(X_calib.iloc[:20])[:, 1]
max_diff = np.abs(onnx_proba - sklearn_proba).max()
print(f"\nONNX vs. sklearn max prediction difference on 20 sample rows: {max_diff:.6f}")
print("MATCH" if max_diff < 1e-4 else "MISMATCH -- investigate before trusting the exported model")
