"""Diagnostic: how much of a baseline lifetime figure is decided by the regime chain, and could the regime
have been called in real time?

    1. Chain uncertainty. The chain in survival_model.json is estimated from one stress episode. A
       Dirichlet(1 + counts) posterior on the decoded transition counts (the same posterior as
       hmm_stability.py) is pushed through the reference term-structure projection (golden_term_structure.py),
       hazards unchanged. The recursion is exact, so the spread across draws is parameter uncertainty only,
       with no simulation noise in it.
    2. Real-time regimes. The regime the survival model trains on is decoded once from an HMM fitted on the
       whole series. This refits the same HMM on an expanding window and keeps only the label of the window's
       last month: what the model would have said at the time. It is compared with the hindsight labels.

Reuses the portfolio series cached by sarimax_seasonality.py. Prints one JSON report; writes nothing.
Run from backend/:  python experiments/regime_uncertainty.py
"""
from __future__ import annotations

import json
import sys
import warnings
from pathlib import Path

import numpy as np
import pandas as pd
import torch
from pomegranate.distributions import Normal
from pomegranate.hmm import DenseHMM

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import golden_term_structure as gts  # noqa: E402
from sarimax_seasonality import load  # noqa: E402

warnings.filterwarnings("ignore")

DRAWS = 1000
MIN_WINDOW = 24  # months before the first real-time call
CASES = [("prime, baseline", dict(loan=gts.PRIME, scenario=gts.BASELINE)),
         ("risky, baseline", dict(loan=gts.RISKY, scenario=gts.BASELINE)),
         ("seasoned, baseline", dict(loan=gts.SEASONED, scenario=gts.BASELINE)),
         ("fifteen-year, baseline", dict(loan=gts.FIFTEEN, scenario=gts.BASELINE)),
         ("prime, severely adverse", dict(loan=gts.PRIME, scenario=gts.SEVERELY_ADVERSE))]
FIGURES = ["pd12m", "pdLifetime", "eclLifetime"]


class CachedSession:
    """The hazards do not depend on the chain, so each case's ONNX call is made once and replayed."""

    def __init__(self, session) -> None:
        self.session, self.cache = session, {}

    def run(self, outputs, feed):
        key = feed["input"].tobytes()
        if key not in self.cache:
            self.cache[key] = self.session.run(outputs, feed)
        return self.cache[key]


def decode(series: pd.DataFrame) -> np.ndarray:
    """export_hmm.py's fit: 1 where the month is in the state with the higher mean delinquency."""
    raw = series[["delinquency_rate_90", "avg_interest_rate"]].to_numpy()
    x = torch.tensor(((raw - raw.mean(axis=0)) / raw.std(axis=0)).reshape(1, -1, 2), dtype=torch.float32)
    model = DenseHMM([Normal(covariance_type="full"), Normal(covariance_type="full")], max_iter=200, tol=1e-4, random_state=42)
    model.fit(x)
    regime = model.predict(x).numpy().flatten()
    stressed = int(np.argmax([raw[regime == s, 0].mean() if (regime == s).any() else -1 for s in range(2)]))
    return (regime == stressed).astype(int)


def spells(months: pd.DatetimeIndex, flags: np.ndarray) -> list[list[str]]:
    out, start = [], None
    for i, flag in enumerate(flags):
        if flag and start is None:
            start = i
        if start is not None and (not flag or i == len(flags) - 1):
            end = i if flag else i - 1
            out.append([str(months[start].date())[:7], str(months[end].date())[:7]])
            start = None
    return out


def chain_uncertainty(path: np.ndarray) -> dict:
    counts = np.array([[np.sum((path[:-1] == i) & (path[1:] == j)) for j in range(2)] for i in range(2)], dtype=float)
    rng = np.random.default_rng(0)
    calm, stress = rng.dirichlet(1 + counts[0], DRAWS), rng.dirichlet(1 + counts[1], DRAWS)
    a = gts.Artifacts()
    a.session = CachedSession(a.session)
    shipped = a.transition
    report: dict = {"decoded_transition_counts": counts.astype(int).tolist(), "draws": DRAWS,
                    "shipped_transition_matrix": [[round(v, 4) for v in row] for row in shipped], "cases": {}}
    for name, case in CASES:
        a.transition = shipped
        point = gts.project(a, case)
        draws = {f: [] for f in FIGURES}
        for c, s in zip(calm, stress):
            a.transition = [list(c), list(s)]
            result = gts.project(a, case)
            for f in FIGURES:
                draws[f].append(result[f])
        report["cases"][name] = {
            f: {"shipped": round(point[f], 6), "p05_p50_p95": [round(float(v), 6) for v in np.quantile(draws[f], [0.05, 0.5, 0.95])],
                "p95_over_p05": round(float(np.quantile(draws[f], 0.95) / np.quantile(draws[f], 0.05)), 2)}
            for f in FIGURES}
    return report


def real_time(series: pd.DataFrame, hindsight: np.ndarray) -> dict:
    months = series.index
    calls = []
    for t in range(MIN_WINDOW - 1, len(series)):
        try:
            calls.append(decode(series.iloc[: t + 1])[-1])
        except (AttributeError, RuntimeError, ValueError):  # a state lost all its months: no two-regime fit on this window
            calls.append(-1)
    calls = np.array(calls)
    truth = hindsight[MIN_WINDOW - 1:]
    judged = months[MIN_WINDOW - 1:]
    return {"first_call_month": str(judged[0].date())[:7], "months_judged": len(calls),
            "months_with_no_fit": [str(m.date())[:7] for m in judged[calls == -1]],
            "hindsight_stressed_spells": spells(months, hindsight), "real_time_stressed_spells": spells(judged, calls == 1),
            "agreement": round(float((calls == truth).mean()), 3),
            "false_alarm_months": int(((calls == 1) & (truth == 0)).sum()),
            "missed_stress_months": int(((calls == 0) & (truth == 1)).sum())}


def main() -> None:
    series = load()
    hindsight = decode(series)
    report = {"months": len(series), "first_month": str(series.index[0].date())[:7], "last_month": str(series.index[-1].date())[:7],
              "chain_uncertainty": chain_uncertainty(hindsight), "real_time_regimes": real_time(series, hindsight)}
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
