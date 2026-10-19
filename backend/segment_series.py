"""Pure functions behind the segment risk series (openspec/changes/segment-graph-models, group 1): the cell
definitions, indirect standardisation with leave-one-state-out national rates, and the checks that stop a
build. No file I/O here, so backend/tests/ can run every function on synthetic data.

Indirect standardisation: for state s and month t, the observed count O is the number of active loans 90 or
more days past due (or in REO). The expected count E is what O would be if each of the state's cells (loan age
x credit-score band x LTV band) were delinquent at the national rate for that cell and month, computed without
the state's own loans. The series is log((O + 0.5) / (E + 0.5)), missing where E < MIN_EXPECTED. The 0.5 keeps a
month with no delinquencies finite; with E >= 20 it moves the log ratio by less than 0.025.
"""
from __future__ import annotations

import numpy as np
import polars as pl

STATES = [
    "AK", "AL", "AR", "AZ", "CA", "CO", "CT", "DC", "DE", "FL", "GA", "HI", "IA", "ID", "IL", "IN", "KS",
    "KY", "LA", "MA", "MD", "ME", "MI", "MN", "MO", "MS", "MT", "NC", "ND", "NE", "NH", "NJ", "NM", "NV",
    "NY", "OH", "OK", "OR", "PA", "RI", "SC", "SD", "TN", "TX", "UT", "VA", "VT", "WA", "WI", "WV", "WY",
]
MIN_EXPECTED = 20.0
MIN_COVERAGE = 0.90
# design.md, feasibility gate: the graph used for validation is learned through LEARN_END and tested on the
# delinquency series from VALIDATION_START (months as year * 12 + month - 1).
LEARN_END = 2016 * 12 + 11
VALIDATION_START = 2019 * 12

# Upper edges, exclusive for age and score, inclusive for LTV (LTV 80 is the conventional break).
AGE_EDGES = [6, 12, 24, 36, 60]
SCORE_EDGES = [620, 680, 740, 780]
LTV_EDGES = [60, 80, 90]
CELL = ["age_bucket", "score_band", "ltv_band"]
KEY = ["state", "month", *CELL]
COUNTS = ["n_active", "n_delinquent", "n_delinquent_disaster", "n_delinquent_assistance"]

# Each variant is (name, the delinquent count it uses). Both exclusions apply to the numerator only: the loan
# stays active, and its delinquency is not counted, in the state and in the national rates alike.
VARIANTS = {
    "primary": pl.col("n_delinquent"),
    "ex_disaster": pl.col("n_delinquent") - pl.col("n_delinquent_disaster"),
    "ex_assistance": pl.col("n_delinquent") - pl.col("n_delinquent_assistance"),
}
# Cell definitions compared at the feasibility gate (task 1.6).
CELL_DEFINITIONS = {"age_score_ltv": CELL, "age_only": ["age_bucket"]}


class BuildError(SystemExit):
    """A build input that cannot be trusted. Exits non-zero with the message, before anything is written."""

    def __init__(self, message: str):
        super().__init__(f"segment build stopped: {message}")


def bucket(expr: pl.Expr, edges: list[float], right_closed: bool = False) -> pl.Expr:
    """0 .. len(edges) by the upper edges; -1 when the value is missing."""
    out = pl.lit(len(edges), dtype=pl.Int8)
    for i in reversed(range(len(edges))):
        out = pl.when(expr <= edges[i] if right_closed else expr < edges[i]).then(pl.lit(i, dtype=pl.Int8)).otherwise(out)
    return pl.when(expr.is_null()).then(pl.lit(-1, dtype=pl.Int8)).otherwise(out)


def delinquent(status: str = "current_loan_delinquency_status") -> pl.Expr:
    """90 or more days past due, or REO acquisition: the default event the rest of the project uses."""
    s = pl.col(status)
    return ((s.cast(pl.Int32, strict=False) >= 3) | (s == "RA")).fill_null(False)


def check_cells(cells: pl.DataFrame, states: list[str] = STATES) -> None:
    """Stops on a missing state, a duplicate key, or a state whose series is empty."""
    if cells.is_empty():
        raise BuildError("no cell counts")
    present = set(cells["state"].unique().to_list())
    missing = sorted(set(states) - present)
    if missing:
        raise BuildError(f"states missing from the cell counts: {', '.join(missing)}")
    dup = cells.filter(cells.select(KEY).is_duplicated())
    if dup.height:
        raise BuildError(f"{dup.height} rows share a (state, month, cell) key, e.g. {dup.row(0, named=True)}")
    empty = (cells.filter(pl.col("state").is_in(states)).group_by("state").agg(pl.col("n_active").sum())
             .filter(pl.col("n_active") == 0)["state"].sort().to_list())
    if empty:
        raise BuildError(f"states with no active loans in any month: {', '.join(empty)}")


def standardised(cells: pl.DataFrame, delinquent_expr: pl.Expr, cell: list[str] = CELL,
                 states: list[str] = STATES, min_expected: float = MIN_EXPECTED) -> pl.DataFrame:
    """Observed, expected and log ratio per state and month, using leave-one-state-out national cell rates.

    National totals are over `states` only (territories excluded). A cell with no loans outside the state in
    that month has no rate; its loans are left out of both O and E and counted in `n_active_unrated`.
    """
    c = (cells.filter(pl.col("state").is_in(states))
         .with_columns(d=delinquent_expr.cast(pl.Float64), n=pl.col("n_active").cast(pl.Float64))
         .group_by("state", "month", *cell).agg(pl.col("n").sum(), pl.col("d").sum()))
    national = c.group_by("month", *cell).agg(N=pl.col("n").sum(), D=pl.col("d").sum())
    c = c.join(national, on=["month", *cell]).with_columns(
        rest_n=pl.col("N") - pl.col("n"), rest_d=pl.col("D") - pl.col("d"))
    rated = pl.col("rest_n") > 0
    out = (c.group_by("state", "month").agg(
        observed=pl.when(rated).then(pl.col("d")).otherwise(0.0).sum(),
        expected=pl.when(rated).then(pl.col("n") * pl.col("rest_d") / pl.col("rest_n")).otherwise(0.0).sum(),
        n_active=pl.col("n").sum(),
        n_active_unrated=pl.when(rated).then(0.0).otherwise(pl.col("n")).sum(),
    ).with_columns(
        log_ratio=pl.when(pl.col("expected") >= min_expected)
        .then(((pl.col("observed") + 0.5) / (pl.col("expected") + 0.5)).log())
        .otherwise(None)
    ).sort("state", "month"))
    return out


def coverage(series: pl.DataFrame, months: list[int], states: list[str] = STATES) -> pl.DataFrame:
    """Share of `months` with a non-missing log ratio, per state, lowest first."""
    present = (series.filter(pl.col("month").is_in(months) & pl.col("log_ratio").is_not_null())
               .group_by("state").agg(months_present=pl.len()))
    return (pl.DataFrame({"state": states}).join(present, on="state", how="left")
            .with_columns(pl.col("months_present").fill_null(0).cast(pl.Int64))
            .with_columns(coverage=pl.col("months_present") / len(months))
            .sort("coverage", "state"))


def last_complete_month(national: pl.DataFrame, share: float = 0.9) -> int:
    """The last month whose national active-loan count is at least `share` of the peak. The panel's later months
    are reported by only the newest vintages (2025-10 onward: about 1.2 million active loans against 10.8 million
    in 2025-09), and a state ratio over such a month would describe a different book."""
    peak = national["n_active"].max()
    return int(national.filter(pl.col("n_active") >= share * peak)["month"].max())


def month_label(month: int) -> str:
    return f"{month // 12}-{month % 12 + 1:02d}"


# ---- macro panel (task 1.4) -----------------------------------------------------------------------------

def residualise(changes: np.ndarray, national: np.ndarray, fit_rows: np.ndarray | None = None) -> np.ndarray:
    """Per column (state), the residual of an OLS regression with intercept on the national series, standardised
    to mean 0 and variance 1. The regression and the standardisation use only `fit_rows` (a boolean mask over
    rows), so a graph learned through a cut-off date sees nothing after it; residuals are returned for all rows."""
    rows = np.ones(len(national), dtype=bool) if fit_rows is None else fit_rows
    x = np.column_stack([np.ones(len(national)), national])
    beta, *_ = np.linalg.lstsq(x[rows], changes[rows], rcond=None)
    resid = changes - x @ beta
    mean, sd = resid[rows].mean(axis=0), resid[rows].std(axis=0, ddof=1)
    if (sd == 0).any():
        raise BuildError("a state's series equals the national one (zero residual variance)")
    return (resid - mean) / sd


def residualise_loo(changes: np.ndarray, fit_rows: np.ndarray | None = None) -> np.ndarray:
    """residualise() with each state's own national series: the mean of the other states' changes. Leaving the
    state out keeps it from being regressed partly on itself, which biases residual correlations negative."""
    n = changes.shape[1]
    total = changes.sum(axis=1)
    return np.column_stack([residualise(changes[:, [j]], (total - changes[:, j]) / (n - 1), fit_rows)[:, 0]
                            for j in range(n)])


def first_pc_share(panel: np.ndarray) -> float:
    """Share of total variance on the first principal component of the column-standardised panel."""
    z = (panel - panel.mean(axis=0)) / panel.std(axis=0, ddof=1)
    eig = np.linalg.eigvalsh(np.cov(z, rowvar=False))
    return float(eig[-1] / eig.sum())
