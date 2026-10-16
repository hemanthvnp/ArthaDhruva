"""Retrain the same LSTM trajectory model as lstm_trajectory_model.ipynb and export it to ONNX
for backend serving: near-term default probability from a loan's first up to 12 months of
actual performance (delinquency status, UPB paydown ratio, modification flag) -- not a static
origination-time snapshot.

ONNX export avoids pack_padded_sequence (fragile/unreliable to trace) -- a small inference-only
wrapper runs the same trained lstm/fc submodules unpacked over exactly the real months supplied,
which is mathematically identical to the packed/trained forward for a single non-padded sequence.
That equivalence is verified directly below (not just asserted) before anything is exported.
"""
import hashlib
import json
from pathlib import Path
import numpy as np
import polars as pl
import torch
import torch.nn as nn
from torch.utils.data import Dataset, DataLoader
from sklearn.linear_model import LogisticRegression
from sklearn.model_selection import train_test_split
from sklearn.metrics import brier_score_loss, roc_auc_score, average_precision_score

MAX_SEQ_LEN = 25
INPUT_CUTOFF = 12
N_FEATURES = 3  # status_numeric, upb_ratio, is_modified

sequences = pl.read_parquet("../data/docs/lstm_sequences.parquet")
labels = pl.read_parquet("../data/docs/lstm_sample_labels.parquet")
print(f"Sequence rows: {sequences.height:,}   Loans: {labels.height:,}")

seq_pdf = sequences.with_columns(
    status_numeric=pl.col("current_loan_delinquency_status").cast(pl.Int32, strict=False).fill_null(12),
    is_modified=(pl.col("modification_flag") == "Y").cast(pl.Int32),
).to_pandas()

orig_upb_map = dict(zip(labels["loan_sequence_number"].to_list(), labels["original_upb"].to_list()))
label_map = dict(zip(labels["loan_sequence_number"].to_list(), labels["default_target"].to_list()))
loan_ids = labels["loan_sequence_number"].to_list()
n_loans = len(loan_ids)

X = np.zeros((n_loans, MAX_SEQ_LEN, N_FEATURES), dtype=np.float32)
lengths = np.zeros(n_loans, dtype=np.int64)
y = np.zeros(n_loans, dtype=np.float32)

grouped = seq_pdf.groupby("loan_sequence_number")
for i, loan_id in enumerate(loan_ids):
    y[i] = label_map[loan_id]
    if loan_id not in grouped.groups:
        lengths[i] = 1
        continue
    g = grouped.get_group(loan_id).sort_values("loan_age")
    g = g[g["loan_age"] < MAX_SEQ_LEN]
    orig_upb = orig_upb_map[loan_id]
    if orig_upb is None or (isinstance(orig_upb, float) and np.isnan(orig_upb)) or orig_upb == 0:
        orig_upb = 1.0
    upb_ratio = g["current_actual_upb"].to_numpy() / orig_upb
    upb_ratio = np.clip(np.nan_to_num(upb_ratio, nan=0.0, posinf=5.0, neginf=0.0), 0.0, 5.0)
    ages = g["loan_age"].to_numpy()
    X[i, ages, 0] = g["status_numeric"].to_numpy()
    X[i, ages, 1] = upb_ratio
    X[i, ages, 2] = g["is_modified"].to_numpy()
    lengths[i] = max(int(ages.max()) + 1, 1) if len(ages) else 1

X = np.nan_to_num(X, nan=0.0, posinf=5.0, neginf=0.0)

numeric_status_expr = pl.col("current_loan_delinquency_status").cast(pl.Int32, strict=False)
adverse_within_window = (
    sequences.filter(pl.col("loan_age") < INPUT_CUTOFF)
    .with_columns(is_adverse=(numeric_status_expr >= 3) | (pl.col("current_loan_delinquency_status") == "RA"))
    .group_by("loan_sequence_number")
    .agg(leaked=pl.col("is_adverse").any())
)
leaked_ids = set(adverse_within_window.filter(pl.col("leaked"))["loan_sequence_number"])
keep_mask = np.array([loan_id not in leaked_ids for loan_id in loan_ids])
X_clean = X[keep_mask, :INPUT_CUTOFF, :]
lengths_clean = np.minimum(lengths[keep_mask], INPUT_CUTOFF)
y_clean = y[keep_mask]
print(f"Leakage-excluded loans: {len(leaked_ids):,}   Remaining: {keep_mask.sum():,}")

# Out-of-time split by vintage (the year is in the id: F<YY>Q<q><n>): fit on the 2020-2021 vintages, judge on
# 2022-2023. A random split would score the model on loans originated alongside the ones it learned from.
# 15% of the fitting vintages is held out to calibrate the probabilities (not used to fit the weights).
vintage = np.array([2000 + int(lid[1:3]) for lid, keep in zip(loan_ids, keep_mask) if keep])
TEST_FROM_VINTAGE = 2022
idx = np.arange(keep_mask.sum())
idx_fit_all, idx_test = idx[vintage < TEST_FROM_VINTAGE], idx[vintage >= TEST_FROM_VINTAGE]
idx_train, idx_cal = train_test_split(idx_fit_all, test_size=0.15, random_state=42, stratify=y_clean[idx_fit_all])
print(f"Train {len(idx_train):,}  calibration {len(idx_cal):,}  out-of-time test {len(idx_test):,} (vintages >= {TEST_FROM_VINTAGE})")

device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
print(f"Device: {device}")

# Standardise from the real months only: the zero padding after a loan's last month is not an observation.
real = np.arange(INPUT_CUTOFF)[None, :] < lengths_clean[idx_train][:, None]
train_real = X_clean[idx_train][real][:, :2]
feat_mean = train_real.mean(axis=0)
feat_std = train_real.std(axis=0) + 1e-6
X_norm = X_clean.copy()
X_norm[:, :, :2] = (X_clean[:, :, :2] - feat_mean) / feat_std


class TrajectoryDataset(Dataset):
    def __init__(self, X, lengths, y, indices):
        self.X = torch.tensor(X[indices], dtype=torch.float32)
        self.lengths = torch.tensor(lengths[indices], dtype=torch.int64)
        self.y = torch.tensor(y[indices], dtype=torch.float32)

    def __len__(self):
        return len(self.y)

    def __getitem__(self, i):
        return self.X[i], self.lengths[i], self.y[i]


train_ds = TrajectoryDataset(X_norm, lengths_clean, y_clean, idx_train)
train_loader = DataLoader(train_ds, batch_size=512, shuffle=True)


class TrajectoryLSTM(nn.Module):
    def __init__(self, n_features, hidden_size=32):
        super().__init__()
        self.lstm = nn.LSTM(n_features, hidden_size, batch_first=True)
        self.fc = nn.Linear(hidden_size, 1)

    def forward(self, x, lengths):
        packed = nn.utils.rnn.pack_padded_sequence(x, lengths.cpu(), batch_first=True, enforce_sorted=False)
        _, (h_n, _) = self.lstm(packed)
        return self.fc(h_n[-1]).squeeze(-1)


model = TrajectoryLSTM(n_features=N_FEATURES, hidden_size=32).to(device)
pos_weight = torch.tensor([(y_clean[idx_train] == 0).sum() / (y_clean[idx_train] == 1).sum()]).to(device)
criterion = nn.BCEWithLogitsLoss(pos_weight=pos_weight)
optimizer = torch.optim.Adam(model.parameters(), lr=1e-3)

N_EPOCHS = 8
for epoch in range(N_EPOCHS):
    model.train()
    total_loss = 0.0
    for xb, lb, yb in train_loader:
        xb, lb, yb = xb.to(device), lb, yb.to(device)
        optimizer.zero_grad()
        logits = model(xb, lb)
        loss = criterion(logits, yb)
        loss.backward()
        torch.nn.utils.clip_grad_norm_(model.parameters(), max_norm=5.0)
        optimizer.step()
        total_loss += loss.item() * len(yb)
    print(f"Epoch {epoch + 1}/{N_EPOCHS}  train loss={total_loss / len(train_ds):.4f}")

model.eval()


def logits_for(indices):
    loader = DataLoader(TrajectoryDataset(X_norm, lengths_clean, y_clean, indices), batch_size=2048, shuffle=False)
    with torch.no_grad():
        return np.concatenate([model(xb.to(device), lb).cpu().numpy() for xb, lb, _ in loader])


def summarise(logits, labels):
    p = 1 / (1 + np.exp(-logits))
    return {"n": int(len(labels)), "defaults": int(labels.sum()), "roc_auc": round(float(roc_auc_score(labels, p)), 4),
            "pr_auc": round(float(average_precision_score(labels, p)), 4), "brier": round(float(brier_score_loss(labels, p)), 5),
            "mean_predicted": round(float(p.mean()), 5), "actual_rate": round(float(labels.mean()), 5),
            "predicted_over_actual": round(float(p.mean() / labels.mean()), 3)}


# pos_weight (above) makes the raw sigmoid an over-estimate of the default probability, and the service serves
# it as a probability. Platt scaling (a * logit + b, fit on the held-out calibration loans) is affine, so it is
# folded into the final linear layer: the exported graph and the Java feature code stay exactly as they were.
cal_logits = logits_for(idx_cal)
platt = LogisticRegression(C=1e6).fit(cal_logits.reshape(-1, 1), y_clean[idx_cal])
slope, intercept = float(platt.coef_[0, 0]), float(platt.intercept_[0])
test_logits = logits_for(idx_test)
before = summarise(test_logits, y_clean[idx_test])
with torch.no_grad():
    model.fc.weight.mul_(slope)
    model.fc.bias.mul_(slope).add_(intercept)
after = summarise(slope * test_logits + intercept, y_clean[idx_test])
print(f"Platt: slope={slope:.4f} intercept={intercept:.4f}")
print("Out-of-time, raw        :", before)
print("Out-of-time, calibrated :", after)

order = np.argsort(slope * test_logits + intercept)
deciles = [{"decile": d + 1, "predicted": round(float((1 / (1 + np.exp(-(slope * test_logits + intercept)[g]))).mean()), 5),
            "actual": round(float(y_clean[idx_test][g].mean()), 5)} for d, g in enumerate(np.array_split(order, 10))]

# --- Verify the unpacked ONNX-export wrapper matches the trained (packed) model exactly ---
class TrajectoryLSTMOnnx(nn.Module):
    """Inference-only: no packing, since a single real-time request has no padding to skip --
    output[:, -1, :] after processing exactly `seq_len` real timesteps equals h_n[-1] from the
    packed/trained forward for a batch of one non-padded sequence (verified below, not assumed)."""

    def __init__(self, trained_model):
        super().__init__()
        self.lstm = trained_model.lstm
        self.fc = trained_model.fc

    def forward(self, x):
        output, _ = self.lstm(x)
        return self.fc(output[:, -1, :])


onnx_model = TrajectoryLSTMOnnx(model).to("cpu").eval()
model_cpu = model.to("cpu")

sample_i = idx_test[0]
sample_len = int(lengths_clean[sample_i])
sample_seq = torch.tensor(X_norm[sample_i, :sample_len, :]).unsqueeze(0)
sample_full = torch.tensor(X_norm[sample_i, :INPUT_CUTOFF, :]).unsqueeze(0)

with torch.no_grad():
    trained_logit = model_cpu(sample_full, torch.tensor([sample_len], dtype=torch.int64)).item()
    wrapper_logit = onnx_model(sample_seq).item()

print(f"Parity check -- trained (packed) logit: {trained_logit:.6f}  wrapper (unpacked) logit: {wrapper_logit:.6f}")
assert abs(trained_logit - wrapper_logit) < 1e-4, "ONNX wrapper does not match trained model -- do not export!"
print("Parity confirmed -- exporting.")

dummy_input = torch.zeros(1, 6, N_FEATURES, dtype=torch.float32)
torch.onnx.export(
    onnx_model, dummy_input, "risk-engine/src/main/resources/lstm_model.onnx",
    input_names=["input"], output_names=["logit"],
    dynamic_axes={"input": {1: "seq_len"}},
    opset_version=15,
    dynamo=False,  # the new dynamo-based exporter needs onnxscript, not installed; the legacy
                   # TorchScript-based exporter handles this small LSTM+Linear graph fine
)

export_meta = {
    "feature_names": ["status_numeric", "upb_ratio", "is_modified"],
    "standardized_feature_count": 2,
    "feat_mean": feat_mean.tolist(),
    "feat_std": feat_std.tolist(),
    "max_seq_len": INPUT_CUTOFF,
}
with open("risk-engine/src/main/resources/lstm_meta.json", "w") as f:
    json.dump(export_meta, f, indent=2)
card = {
    "model": "trajectory_lstm",
    "artifact_sha256": hashlib.sha256(Path("risk-engine/src/main/resources/lstm_model.onnx").read_bytes()).hexdigest(),
    "input": f"first {INPUT_CUTOFF} months of status, UPB ratio, modification flag",
    "target": "ever reaches 90+ days past due after month 12 (loans already adverse in the window are excluded)",
    "protocol": f"fit on vintages < {TEST_FROM_VINTAGE} (85%), Platt-calibrated on the other 15%, judged on vintages >= {TEST_FROM_VINTAGE}",
    "calibration": {"method": "Platt scaling folded into the final linear layer", "slope": slope, "intercept": intercept},
    "out_of_time_raw": before, "out_of_time_calibrated": after, "out_of_time_deciles": deciles,
    "limitations": [
        "'Ever defaulted' is right-censored: later vintages have had less time to default, so predicted/actual drifts with vintage",
        "100,000-loan stratified sample of 2020-2023 vintages; one stress episode",
        "A month missing inside the window is encoded as status 0 / UPB ratio 0, indistinguishable from a real zero",
        "The Java feature code is pinned to backend/golden_lstm.py by TrajectoryParityTest (regenerate it after any re-export)",
    ],
}
with open("risk-engine/src/main/resources/lstm_card.json", "w") as f:
    json.dump(card, f, indent=2)
print("Saved risk-engine/src/main/resources/lstm_model.onnx, lstm_meta.json and lstm_card.json")
