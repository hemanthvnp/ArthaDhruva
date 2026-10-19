"""Reproduces the baseline numbers in openspec/changes/segment-graph-models/design.md ("Measured on 2 October
2026") from the 15-state series behind today's segment graph, and prints each next to the documented value.

The series is data/docs/segment_monthly_series.parquet (15 states x 69 months, 2020-01 .. 2025-09). The
documented values are rounded; a row passes when the recomputed value rounds to the same digits.

Run from backend/:  python experiments/segment_baseline.py   (exits 1 if any row does not match)
"""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
import pandas as pd
from scipy import stats

SERIES = Path(__file__).resolve().parents[2] / "data" / "docs" / "segment_monthly_series.parquet"
THRESHOLD = 0.95


def pair_values(corr: pd.DataFrame) -> np.ndarray:
    m = corr.to_numpy()
    return m[np.triu_indices_from(m, k=1)]


def residual_changes(wide: pd.DataFrame) -> pd.DataFrame:
    """Each state's monthly change, regressed on the cross-state average change (the average includes the state
    itself, as in the design), with intercept; returns the residuals."""
    changes = wide.diff().dropna()
    avg = changes.mean(axis=1).to_numpy()
    x = np.column_stack([np.ones_like(avg), avg])
    beta, *_ = np.linalg.lstsq(x, changes.to_numpy(), rcond=None)
    return pd.DataFrame(changes.to_numpy() - x @ beta, index=changes.index, columns=changes.columns)


def critical_r(n: int, alpha: float = 0.05) -> float:
    t = stats.t.ppf(1 - alpha / 2, n - 2)
    return float(t / np.sqrt(n - 2 + t * t))


def main() -> int:
    series = pd.read_parquet(SERIES)
    wide = series.pivot(index="monthly_reporting_period", columns="property_state", values="delinquency_rate").sort_index()
    if wide.isna().any().any():
        raise SystemExit("a state is missing months")
    n_months, n_states = wide.shape

    z = (wide - wide.mean()) / wide.std()
    eig = np.linalg.eigvalsh(np.corrcoef(z.to_numpy().T))
    levels = pair_values(wide.corr())
    changes = pair_values(wide.diff().dropna().corr())
    resid = residual_changes(wide)
    resid_pairs = pair_values(resid.corr())
    r_crit = critical_r(len(resid))

    half = n_months // 2
    first, second = wide.iloc[:half], wide.iloc[half:]
    lv1, lv2 = pair_values(first.corr()), pair_values(second.corr())
    # The residuals come from the full-sample regression and are then split (0.23); refitting the regression
    # within each half instead gives 0.15. Both say the residual structure is unstable between halves.
    rs1, rs2 = pair_values(resid.iloc[:half].corr()), pair_values(resid.iloc[half:].corr())

    rows = [
        ("first principal component, % of variance", 100 * eig[-1] / eig.sum(), 89.5, 1),
        ("levels: median pair correlation", np.median(levels), 0.928, 3),
        ("levels: minimum", levels.min(), 0.367, 3),
        ("levels: maximum", levels.max(), 0.991, 3),
        ("levels: pairs at r >= 0.95", (levels >= THRESHOLD).sum(), 41, 0),
        ("levels: pairs at r >= 0.90", (levels >= 0.90).sum(), 67, 0),
        ("changes: median pair correlation", np.median(changes), 0.899, 3),
        ("changes: minimum", changes.min(), 0.536, 3),
        ("residual changes: 5% critical |r|", r_crit, 0.24, 2),
        ("residual changes: pairs beyond it", (np.abs(resid_pairs) > r_crit).sum(), 37, 0),
        ("residual changes: median", np.median(resid_pairs), -0.067, 3),
        ("halves: rank agreement, levels", stats.spearmanr(lv1, lv2).statistic, -0.23, 2),
        ("halves: rank agreement, residuals", stats.spearmanr(rs1, rs2).statistic, 0.23, 2),
        ("halves: edges at 0.95, first", (lv1 >= THRESHOLD).sum(), 56, 0),
        ("halves: edges at 0.95, second", (lv2 >= THRESHOLD).sum(), 67, 0),
        ("halves: edges at 0.95, both", ((lv1 >= THRESHOLD) & (lv2 >= THRESHOLD)).sum(), 32, 0),
        ("smallest state-month, active loans", series["n_active"].min(), 13, 0),
        ("median state-month, active loans", series["n_active"].median(), 218523, 0),
    ]

    print(f"{SERIES.name}: {n_states} states x {n_months} months ({wide.index.min()} .. {wide.index.max()})\n")
    print(f"{'quantity':45} {'recomputed':>12} {'documented':>12}")
    failures = 0
    for name, value, documented, digits in rows:
        ok = round(float(value), digits) == round(float(documented), digits)
        failures += not ok
        print(f"{name:45} {float(value):12.{digits}f} {float(documented):12.{digits}f}  {'ok' if ok else 'MISMATCH'}")
    print(f"\n{len(rows) - failures} of {len(rows)} match")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
