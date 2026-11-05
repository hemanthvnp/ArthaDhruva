"""Out-of-time validation and model card for the macro regime chain (hmm_regime.json).

The chain is a two-state Gaussian hidden Markov model on two monthly portfolio series (the share of loans 90+ days past due and
the average note rate), fitted by export_hmm.py. Its transition matrix is the survival model's regime chain and the regime
forecast's, and the survival model's stressed periods are read from the labels it decodes. It had no card and no out-of-time
result.

This script does not rewrite the artifact. It reads hmm_regime.json as the model of record, refits the same specification (same
window rules, seed and settings) on the whole series to check that the shipped matrix reproduces, then on shortened histories to
judge it on the months it has not seen:

  * does the chain it learns, and the expected stress spell and long-run stressed share that follow, hold as months are added;
  * does it label the months after the cut as the full fit does;
  * does it explain the later months better than a single regime (the held-out log-likelihood against one Gaussian).

It writes hmm_regime_card.json, whose checksum of hmm_regime.json the service verifies at start-up (governance/ModelRegistry).
Run from backend/. The series is data/docs/portfolio_monthly_series_full.parquet, the aggregate export_hmm.py rebuilds from the panel.
"""
from __future__ import annotations

import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path

import numpy as np
import pandas as pd
import polars as pl
import torch
from pomegranate.distributions import Normal
from pomegranate.hmm import DenseHMM

RESOURCES = Path("risk-engine/src/main/resources")
SERIES = Path("../data/docs/portfolio_monthly_series_full.parquet")
LOOKUP = Path("../data/processed/hmm_regime_lookup.parquet")
MATURE_LOAN_AGE_THRESHOLD = 6
CUTS = ["2020-12", "2022-12"]        # last month of the fitting history; the months after it are held out


def window() -> pd.DataFrame:
    """The months export_hmm.py fits on: from the first month the oldest loan is 6 months old, to the month before the coverage cliff."""
    pdf = pl.read_parquet(SERIES).to_pandas().set_index("monthly_reporting_period")
    pdf.index = pd.to_datetime(pdf.index).to_period("M").to_timestamp()
    first = pdf.index[pdf["max_loan_age"] >= MATURE_LOAN_AGE_THRESHOLD].min()
    pdf = pdf.loc[first:]
    coverage = pdf["n_active"] / pdf["n_active"].shift(1)
    cliff = coverage[coverage < 0.5].index
    if len(cliff) > 0:
        pdf = pdf.loc[: cliff.min() - pd.DateOffset(months=1)]
    return pdf


def fit(raw: np.ndarray, mean: np.ndarray, std: np.ndarray):
    """The export's model on `raw`, standardized with the given mean and std. Returns the model, the calm/stressed order of its
    states and its transition matrix in that order (index 0 = calm, 1 = stressed)."""
    X = torch.tensor(((raw - mean) / std).reshape(1, -1, 2), dtype=torch.float32)
    model = DenseHMM([Normal(covariance_type="full"), Normal(covariance_type="full")], max_iter=200, tol=1e-4, random_state=42)
    model.fit(X)
    regime = model.predict(X).numpy().flatten()
    means = [raw[regime == s, 0].mean() if (regime == s).any() else -np.inf for s in range(2)]
    stressed = int(np.argmax(means))
    order = [1 - stressed, stressed]
    trans = np.exp(model.edges.detach().numpy())
    trans = trans / trans.sum(axis=1, keepdims=True)
    return model, order, trans[np.ix_(order, order)]


def labels(model, order, raw: np.ndarray, mean: np.ndarray, std: np.ndarray) -> np.ndarray:
    """0 calm / 1 stressed for every month of `raw`, decoded by `model` (states renumbered by `order`)."""
    X = torch.tensor(((raw - mean) / std).reshape(1, -1, 2), dtype=torch.float32)
    state = model.predict(X).numpy().flatten()
    return np.array([order.index(s) for s in state])


def chain_facts(trans: np.ndarray) -> dict:
    p_cs, p_sc = trans[0][1], trans[1][0]
    return {"transition_matrix": trans.tolist(), "expected_stress_spell_months": float(1.0 / p_sc),
            "long_run_stressed_share": float(p_cs / (p_cs + p_sc))}


def held_out_log_likelihood(model, raw: np.ndarray, cut: int, mean: np.ndarray, std: np.ndarray) -> dict:
    """Log-likelihood per held-out month: log P(whole history) - log P(fitting history) under the chain, against a single Gaussian
    fitted to the fitting months (the same two series, standardized the same way, full covariance)."""
    z = (raw - mean) / std
    whole = float(model.log_probability(torch.tensor(z.reshape(1, -1, 2), dtype=torch.float32)).item())
    prefix = float(model.log_probability(torch.tensor(z[:cut].reshape(1, -1, 2), dtype=torch.float32)).item())
    held = z[cut:]
    mu, cov = z[:cut].mean(axis=0), np.cov(z[:cut].T) + 1e-6 * np.eye(2)
    diff = held - mu
    inv = np.linalg.inv(cov)
    single = float((-0.5 * (np.einsum("ij,jk,ik->i", diff, inv, diff) + np.log(np.linalg.det(cov)) + 2 * np.log(2 * np.pi))).sum())
    n = len(held)
    return {"months": int(n), "chain_per_month": (whole - prefix) / n, "single_regime_per_month": single / n,
            "chain_minus_single_per_month": (whole - prefix - single) / n}


def main() -> None:
    pdf = window()
    raw = pdf[["delinquency_rate_90", "avg_interest_rate"]].to_numpy()
    months = [d.strftime("%Y-%m") for d in pdf.index]
    mean, std = raw.mean(axis=0), raw.std(axis=0)
    shipped = json.loads((RESOURCES / "hmm_regime.json").read_text())
    shipped_matrix = np.array(shipped["transition_matrix"])

    full_model, full_order, full_trans = fit(raw, mean, std)
    full_labels = labels(full_model, full_order, raw, mean, std)
    reproduces = bool(np.allclose(full_trans, shipped_matrix, atol=1e-4))
    print(f"{len(raw)} months {months[0]} to {months[-1]}; the refit's matrix {'reproduces' if reproduces else 'DIFFERS FROM'} the shipped one "
          f"(max |diff| {np.abs(full_trans - shipped_matrix).max():.2e})")
    stressed_runs, start = [], None
    for m, s in zip(months, full_labels):
        if s == 1 and start is None:
            start = m
        if s == 0 and start is not None:
            stressed_runs.append([start, months[months.index(m) - 1]])
            start = None
    if start is not None:
        stressed_runs.append([start, months[-1]])

    results = {}
    for cut_month in CUTS:
        cut = months.index(cut_month) + 1
        t_mean, t_std = raw[:cut].mean(axis=0), raw[:cut].std(axis=0)
        model, order, trans = fit(raw[:cut], t_mean, t_std)
        decoded = labels(model, order, raw, t_mean, t_std)
        held = slice(cut, None)
        results[cut_month] = {
            "fitting_months": int(cut), "held_out_months": int(len(raw) - cut),
            "fitted_on_history_through": cut_month, "held_out": [months[cut], months[-1]],
            "chain": chain_facts(trans),
            "agreement_with_full_fit_on_held_out_months": float((decoded[held] == full_labels[held]).mean()),
            "stressed_months_labelled_held_out": int(decoded[held].sum()), "stressed_months_in_full_fit_held_out": int(full_labels[held].sum()),
            "agreement_with_full_fit_on_fitting_months": float((decoded[:cut] == full_labels[:cut]).mean()),
            "held_out_log_likelihood": held_out_log_likelihood(model, raw, cut, t_mean, t_std),
        }
        print(cut_month, json.dumps({k: v for k, v in results[cut_month].items() if k != "chain"}, indent=1), results[cut_month]["chain"])

    artifact = RESOURCES / "hmm_regime.json"
    lookup = pl.read_parquet(LOOKUP)
    counts = [[0, 0], [0, 0]]
    seq = [1 if v == "stressed" else 0 for v in lookup.sort("monthly_reporting_period")["hmm_regime"].to_list()]
    for a, b in zip(seq[:-1], seq[1:]):
        counts[a][b] += 1
    card = {
        "model": "regime_hmm",
        "version": datetime.now(timezone.utc).strftime("%Y.%m.%d"),
        "validated_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "artifact": "hmm_regime.json",
        "artifact_sha256": hashlib.sha256(artifact.read_bytes()).hexdigest(),
        "algorithm": "Two-state Gaussian hidden Markov model, full covariance, on the standardized monthly share of loans 90+ days past due and average note rate",
        "inputs": ["delinquency_rate_90", "avg_interest_rate"],
        "training": {"months": int(len(raw)), "first_month": months[0], "last_month": months[-1],
                     "window": "from the first month the oldest loan is 6 months old to the month before the panel's coverage cliff (export_hmm.py)",
                     "stressed_periods_decoded": stressed_runs, "decoded_transition_counts": counts},
        "shipped_chain": chain_facts(shipped_matrix),
        "reproduction": {"refit_reproduces_shipped_matrix": reproduces, "max_abs_difference": float(np.abs(full_trans - shipped_matrix).max())},
        "validation": {
            "protocol": "refit on the history through a cut month with its own standardization, then decode the whole series and score the months after the cut; "
                        "two cuts: 2020-12 (the onset of the one stress episode is in the fitting months) and 2022-12 (the whole episode is)",
            "out_of_time": results,
        },
        "limitations": [
            f"One stress episode ({stressed_runs[0][0]} to {stressed_runs[0][1]}) in {len(raw)} months. The chain's stress spell and long-run stressed share rest on a single observed entry into stress and a single exit ({counts[0][1]} and {counts[1][0]} transitions), so they are uncertain by more than any figure here shows; the survival model's intervals draw the chain from a Dirichlet posterior for that reason.",
            "There is no ground truth for a regime: the labels are the model's own decoding, so agreement between fits measures stability, not accuracy.",
            "Every month after 2022-12 is decoded calm, so the held-out result for the later cut says the model does not invent stress where there was none; it cannot say how it would call a new stress episode.",
            "The two series are portfolio aggregates of the Freddie Mac sample; a different lender's portfolio is not described by this regime.",
            "The panel's coverage falls in the last months (vintage files were pulled at different times), so the series is cut before it, and the chain's as-of month is 2025-09.",
        ],
    }
    path = RESOURCES / "hmm_regime_card.json"
    path.write_text(json.dumps(card, indent=2, default=float) + "\n", encoding="utf-8")
    print(f"wrote {path}")


if __name__ == "__main__":
    main()
