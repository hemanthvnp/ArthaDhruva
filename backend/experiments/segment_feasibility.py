"""Feasibility gate for openspec/changes/archive/2026-10-19-segment-graph-models (task 1.6), from the output of build_state_panel.py.

1. Coverage: states with a ratio in at least 90% of months, for each candidate first month of the window.
2. Cell definition: loan age x credit-score band x LTV band against loan age only. Each difference in the log
   ratio is compared with its Poisson noise, sqrt(1 / observed); if the definitions differ by no more than noise
   the simpler one would do.
3. Variants: the series without disaster-flagged and without assistance-flagged delinquencies against the
   primary, in the same terms, and how many state-months change by more than twice the noise.

Run from backend/:  python experiments/segment_feasibility.py   (prints JSON)
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np
import polars as pl

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import segment_series as ss  # noqa: E402

SEGMENTS = Path(__file__).resolve().parents[2] / "data" / "processed" / "segments"
STARTS = ["2017-01", "2018-01", "2019-01", "2019-07", "2020-01"]


def month(label: str) -> int:
    y, m = map(int, label.split("-"))
    return y * 12 + m - 1


def compare(a: pl.DataFrame, b: pl.DataFrame, since: int) -> dict:
    j = (a.select("state", "month", la="log_ratio", obs="observed")
         .join(b.select("state", "month", lb="log_ratio"), on=["state", "month"])
         .filter((pl.col("month") >= since) & pl.col("la").is_not_null() & pl.col("lb").is_not_null()))
    d = (j["la"] - j["lb"]).to_numpy()
    se = np.sqrt(1.0 / np.maximum(j["obs"].to_numpy(), 1.0))
    z = np.abs(d) / se
    corr = float(np.corrcoef(j["la"].to_numpy(), j["lb"].to_numpy())[0, 1])
    return {"state_months": len(d), "median_abs_difference": round(float(np.median(np.abs(d))), 4),
            "median_noise": round(float(np.median(se)), 4), "share_beyond_2_noise": round(float((z > 2).mean()), 3),
            "correlation": round(corr, 4)}


def main() -> None:
    cells = pl.read_parquet(SEGMENTS / "state_month_cells.parquet")
    ratio = pl.read_parquet(SEGMENTS / "state_ratio.parquet")
    primary = ratio.filter(pl.col("variant") == "primary")
    months = sorted(primary["month"].unique().to_list())

    coverage = {}
    for start in STARTS:
        window = [m for m in months if m >= month(start)]
        c = ss.coverage(primary, window)
        below = c.filter(pl.col("coverage") < ss.MIN_COVERAGE)
        coverage[start] = {"months": len(window), "states_at_90pct": c.height - below.height,
                           "below": {r["state"]: round(r["coverage"], 3) for r in below.iter_rows(named=True)}}

    age_only = ss.standardised(cells, ss.VARIANTS["primary"], cell=ss.CELL_DEFINITIONS["age_only"])
    since = month("2019-01")
    report = {
        "months": f"{ss.month_label(months[0])} .. {ss.month_label(months[-1])}",
        "coverage_by_first_month": coverage,
        "compared_from": ss.month_label(since),
        "cell_definition_age_score_ltv_vs_age_only": compare(primary, age_only, since),
        "variant_ex_disaster_vs_primary": compare(primary, ratio.filter(pl.col("variant") == "ex_disaster"), since),
        "variant_ex_assistance_vs_primary": compare(primary, ratio.filter(pl.col("variant") == "ex_assistance"), since),
    }
    flagged = cells.filter(pl.col("state").is_in(ss.STATES)).group_by("month").agg(
        pl.col("n_delinquent").sum(), pl.col("n_delinquent_disaster").sum(), pl.col("n_delinquent_assistance").sum()
    ).filter(pl.col("n_delinquent") >= 1000).sort("month")
    peak = lambda col: flagged.with_columns(s=pl.col(col) / pl.col("n_delinquent")).sort("s").tail(1).row(0, named=True)
    report["peak_disaster_share"] = {"month": ss.month_label(peak("n_delinquent_disaster")["month"]),
                                     "share": round(peak("n_delinquent_disaster")["s"], 3)}
    report["peak_assistance_share"] = {"month": ss.month_label(peak("n_delinquent_assistance")["month"]),
                                       "share": round(peak("n_delinquent_assistance")["s"], 3)}
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
