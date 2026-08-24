"""Refit the same 2-state HMM as hmm_regime_detector.ipynb and export what the backend needs
for N-step-ahead regime forecasting: the transition matrix and the most recent known regime.
Matrix exponentiation of the transition matrix is enough to forecast -- no need to reimplement
the HMM fitting itself in Java, just the (much simpler) Markov chain forecast on top of it."""
import json
import numpy as np
import pandas as pd
import polars as pl
import torch
from pomegranate.distributions import Normal
from pomegranate.hmm import DenseHMM

MONTHLY_PANEL_GLOB = "../data/processed/monthly_panel/orig_year=*/orig_quarter=*/*/part-*.parquet"

print("Rebuilding the portfolio series...")
lf = pl.scan_parquet(MONTHLY_PANEL_GLOB).select(
    "monthly_reporting_period", "current_loan_delinquency_status", "current_interest_rate"
)
numeric_status = pl.col("current_loan_delinquency_status").cast(pl.Int32, strict=False)
agg = (
    lf.group_by("monthly_reporting_period")
    .agg(n_active=pl.len(), n_delinquent_90=(numeric_status >= 3).sum(),
         avg_interest_rate=pl.col("current_interest_rate").mean())
    .sort("monthly_reporting_period")
    .collect()
    .with_columns(delinquency_rate_90=pl.col("n_delinquent_90") / pl.col("n_active"))
)
pdf_full = agg.to_pandas().set_index("monthly_reporting_period")
pdf_full.index = pd.to_datetime(pdf_full.index).to_period("M").to_timestamp()
pdf = pdf_full.loc["2020-06-01":]  # exclude the ramp-up months, same fix as the notebook

print("Fitting the 2-state HMM...")
raw = pdf[["delinquency_rate_90", "avg_interest_rate"]].to_numpy()
standardized = (raw - raw.mean(axis=0)) / raw.std(axis=0)
X = torch.tensor(standardized.reshape(1, -1, 2), dtype=torch.float32)

model = DenseHMM([Normal(covariance_type="full"), Normal(covariance_type="full")],
                  max_iter=200, tol=1e-4, random_state=42)
model.fit(X)
regime = model.predict(X).numpy().flatten()
state_means = [raw[regime == s, 0].mean() for s in range(2)]
stressed_state = int(np.argmax(state_means))
calm_state = 1 - stressed_state

edges = model.edges.detach().numpy()
trans = np.exp(edges)
trans = trans / trans.sum(axis=1, keepdims=True)

# Reorder so index 0 = calm, index 1 = stressed (consistent, human-readable convention for the
# exported JSON -- the raw state indices from pomegranate are otherwise arbitrary).
order = [calm_state, stressed_state]
trans_ordered = trans[np.ix_(order, order)]
current_state_raw = regime[-1]
current_state_ordered = order.index(current_state_raw)

print("Transition matrix (rows/cols = [calm, stressed]):")
print(trans_ordered)
print(f"Most recent month ({pdf.index[-1].date()}) regime: {'stressed' if current_state_ordered == 1 else 'calm'}")

export = {
    "state_labels": ["calm", "stressed"],
    "transition_matrix": trans_ordered.tolist(),
    "current_state_index": int(current_state_ordered),
    "as_of_month": str(pdf.index[-1].date()),
}
with open("risk-engine/src/main/resources/hmm_regime.json", "w") as f:
    json.dump(export, f, indent=2)
print("\nSaved risk-engine/src/main/resources/hmm_regime.json")
