"""Diagnostic: how stable is the 2-state regime chain that the survival model and the regime forecast
inherit (export_hmm.py -> hmm_regime.json)?

    1. Tail: export_hmm.py fits on every month after the ramp-up window. The SARIMAX notebook found that
       the last months of the panel are an artifact (vintage files stop refreshing, n_active collapses) and
       truncates them. This refits the HMM both ways and compares the chains and the 'current' regime.
    2. Sampling error: the chain is estimated from one stress episode. A Dirichlet posterior on the decoded
       transition counts gives an interval on each stay probability and on the expected stress spell.

Reuses the full portfolio series cached by sarimax_seasonality.py. Prints one JSON report.
Run from backend/:  python experiments/hmm_stability.py
"""
from __future__ import annotations

import json

import numpy as np
import pandas as pd
import torch
from pomegranate.distributions import Normal
from pomegranate.hmm import DenseHMM

from sarimax_seasonality import MATURE_LOAN_AGE, aggregate


def fit(series: pd.DataFrame) -> dict:
    raw = series[["delinquency_rate_90", "avg_interest_rate"]].to_numpy()
    x = torch.tensor(((raw - raw.mean(axis=0)) / raw.std(axis=0)).reshape(1, -1, 2), dtype=torch.float32)
    model = DenseHMM([Normal(covariance_type="full"), Normal(covariance_type="full")], max_iter=200, tol=1e-4, random_state=42)
    model.fit(x)
    regime = model.predict(x).numpy().flatten()
    stressed = int(np.argmax([raw[regime == s, 0].mean() if (regime == s).any() else -1 for s in range(2)]))
    order = [1 - stressed, stressed]
    trans = np.exp(model.edges.detach().numpy())
    trans = (trans / trans.sum(axis=1, keepdims=True))[np.ix_(order, order)]
    path = (regime == stressed).astype(int)
    counts = np.array([[np.sum((path[:-1] == i) & (path[1:] == j)) for j in range(2)] for i in range(2)])
    return {"months": len(series), "last_month": str(series.index[-1].date()), "transition_matrix": trans.round(4).tolist(),
            "current_regime": "stressed" if path[-1] else "calm", "stressed_share_of_months": round(float(path.mean()), 3),
            "expected_stress_spell_months": round(float(1 / (1 - trans[1, 1])), 1),
            "decoded_transition_counts": counts.tolist()}


def main() -> None:
    pdf = aggregate().set_index("monthly_reporting_period")
    pdf.index = pd.to_datetime(pdf.index).to_period("M").to_timestamp()
    pdf = pdf.loc[pdf.index[pdf["max_loan_age"] >= MATURE_LOAN_AGE].min():]
    ratio = pdf["n_active"] / pdf["n_active"].shift(1)
    cliff = ratio[ratio < 0.5].index
    report: dict = {"coverage_cliff_month": str(cliff.min().date()) if len(cliff) else None,
                    "months_after_cliff": int((pdf.index >= cliff.min()).sum()) if len(cliff) else 0}
    report["as_exported (all months)"] = fit(pdf)
    if len(cliff):
        trimmed = pdf.loc[: cliff.min() - pd.DateOffset(months=1)]
        report["trimmed at cliff"] = fit(trimmed)
        counts = np.array(report["trimmed at cliff"]["decoded_transition_counts"], dtype=float)
    else:
        counts = np.array(report["as_exported (all months)"]["decoded_transition_counts"], dtype=float)

    rng = np.random.default_rng(0)  # Dirichlet(1 + counts) posterior over each row of the transition matrix
    calm_stay = rng.dirichlet(1 + counts[0], 10_000)[:, 0]
    stress_stay = rng.dirichlet(1 + counts[1], 10_000)[:, 1]
    q = lambda a: [round(float(v), 4) for v in np.quantile(a, [0.05, 0.5, 0.95])]
    report["sampling_uncertainty_90pct (5%, 50%, 95%)"] = {
        "calm_to_calm": q(calm_stay), "stressed_to_stressed": q(stress_stay),
        "expected_stress_spell_months": [round(float(v), 1) for v in np.quantile(1 / (1 - stress_stay), [0.05, 0.5, 0.95])]}
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
