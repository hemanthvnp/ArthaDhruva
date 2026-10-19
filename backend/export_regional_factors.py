"""Estimates the regional loss factors for the segment graph (openspec/changes/segment-graph-models, tasks 3.1-3.5).

3.1  Reproduces the validation fit of export_survival_model.py (its own functions: loan-months before 2023-01,
     the 2006-2008 vintages included, early stopping on bucket 2), predicts the default hazard of every
     loan-month from 2023-01 to the last month every vintage reports, and sums actual defaults, exposure
     (loan-months, with the sample weights) and expected defaults by state and calendar quarter. Only complete
     quarters are kept. The sums are cached in data/processed/segments/survival_oot_state_quarter.parquet, so
     regions can change without refitting; pass --refit to rebuild them.
3.2  The correlation between the regions of segment_graph.json (segment_regional.region_correlation).
3.3  The regional share rho_r with its interval (segment_regional.estimate); states in no region are left out.
3.4  Before anything is written, the estimator must recover a known share on portfolios simulated at exactly
     these regions' exposures and expected rates: the known value inside the interval in at least 90% of 200
     simulations, at the estimate and at 0.02. Otherwise the script stops.
3.5  Writes data/processed/segments/regional_factors.json, which export_segment_graph.py adds to the graph file
     and its card (rerun it afterwards).

Run from backend/ after export_segment_graph.py:  python export_regional_factors.py [--refit]
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np
import polars as pl

import segment_regional as sr
import segment_series as ss

ROOT = Path(__file__).resolve().parents[1]
SEGMENTS = ROOT / "data" / "processed" / "segments"
CACHE = SEGMENTS / "survival_oot_state_quarter.parquet"
OUT = SEGMENTS / "regional_factors.json"
GRAPH = Path(__file__).resolve().parent / "risk-engine" / "src" / "main" / "resources" / "segment_graph.json"
RECOVERY_SIMS = 200
RECOVERY_MIN = 0.90


def state_quarter_sums() -> pl.DataFrame:
    """Task 3.1: the out-of-time defaults of the survival model's validation fit, by state and quarter."""
    import export_survival_model as esm
    from credit_common import category_mappings, loan_bucket

    mappings = category_mappings()
    loans = pl.read_parquet(str(esm.SURVIVAL / "loans" / "*.parquet"))
    finals = esm.release_final_months(loans)
    coverage_end = finals[0] - 1
    rows = pl.read_parquet(str(esm.SURVIVAL / "rows" / "*.parquet")).filter(~pl.col("month").is_in(finals))
    crisis = pl.read_parquet(str(esm.CRISIS / "rows" / "*.parquet")).filter(pl.col("month") <= esm.CRISIS_END)
    rows = pl.concat([crisis, rows], how="vertical_relaxed")
    rows = esm.featurize(rows, pl.col("month") - pl.col("loan_age").cast(pl.Int32), mappings).with_columns(
        bucket=loan_bucket(pl.col("loan_sequence_number"))).sort(["loan_sequence_number", "loan_age"])
    past = rows.filter(pl.col("month") < esm.TEST_START)
    test = rows.filter((pl.col("month") >= esm.TEST_START) & (pl.col("month") <= coverage_end))
    del rows
    print(f"validation fit: train {past.filter(pl.col('bucket') >= 3).height:,} rows; "
          f"out of time {test.height:,} rows to {ss.month_label(coverage_end)}", flush=True)
    booster, best = esm.fit(past.filter(pl.col("bucket") >= 3), past.filter(pl.col("bucket") == 2))
    print(f"  best iteration {best}", flush=True)
    p_default = booster.predict(esm.matrix(test))[:, esm.CAUSES["default"]]
    sums = (test.select("property_state", "month", "label", "weight")
            .with_columns(p=pl.Series(p_default), quarter=pl.col("month") // 3)
            .group_by("property_state", "quarter").agg(
                events=(pl.col("label") == esm.CAUSES["default"]).sum().cast(pl.Float64),
                exposure=pl.col("weight").cast(pl.Float64).sum(),
                expected=(pl.col("weight").cast(pl.Float64) * pl.col("p")).sum(),
                months=pl.col("month").n_unique()))
    complete = sums.group_by("quarter").agg(pl.col("months").max()).filter(pl.col("months") == 3)["quarter"]
    return sums.filter(pl.col("quarter").is_in(complete)).drop("months").rename({"property_state": "state"}) \
        .sort("state", "quarter")


def quarter_label(q: int) -> str:
    return f"{q // 4}-Q{q % 4 + 1}"


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--refit", action="store_true", help="refit the survival validation model instead of the cache")
    args = ap.parse_args()

    if args.refit or not CACHE.exists():
        sums = state_quarter_sums()
        sums.write_parquet(CACHE)
    sums = pl.read_parquet(CACHE)

    graph = json.loads(GRAPH.read_text(encoding="utf-8"))
    regions = {r["id"]: r["states"] for r in graph.get("regions", [])}
    if len(regions) < 2:
        raise ss.BuildError(f"{GRAPH.name} has {len(regions)} region(s); a regional factor needs at least two")
    region_of = {s: rid for rid, states in regions.items() for s in states}
    by_region = (sums.filter(pl.col("state").is_in(list(region_of)))
                 .with_columns(region=pl.col("state").replace_strict(region_of))
                 .group_by("region", "quarter").agg(pl.col("events", "exposure", "expected").sum())
                 .sort("region", "quarter"))
    ids = list(regions)
    quarters = sorted(by_region["quarter"].unique().to_list())
    grid = {c: by_region.pivot(index="region", on="quarter", values=c).sort("region") for c in ("events", "exposure", "expected")}
    order = grid["events"]["region"].to_list()
    if order != sorted(ids) or any(g.null_count().sum_horizontal().item() for g in grid.values()):
        raise ss.BuildError("a region has no loan-months in some quarter")
    events, exposure, expected = (grid[c].drop("region").to_numpy() for c in ("events", "exposure", "expected"))

    est = sr.estimate(events, exposure, expected, bootstrap=True)
    u, _, _ = sr.deviations(events, exposure, expected)
    corr = sr.region_correlation(u)
    if not (np.allclose(corr, corr.T) and np.allclose(np.diag(corr), 1) and np.linalg.eigvalsh(corr).min() >= -1e-10):
        raise ss.BuildError("region correlation is not a valid correlation matrix")

    base = expected / exposure
    recovery = {f"{rho:.4f}": sr.coverage(exposure, base, rho, sims=RECOVERY_SIMS)
                for rho in sorted({0.02, round(max(est["estimate"], 0.001), 4)})}
    print(f"recovery at these sizes (share of {RECOVERY_SIMS} simulations covering the known value): {recovery}")
    if min(recovery.values()) < RECOVERY_MIN:
        raise ss.BuildError(f"the estimator does not recover a known share at these sizes: {recovery}")

    share = {"estimate": round(est["estimate"], 4), "interval": [round(x, 4) for x in est["interval"]],
             "default": round(est["default"], 4)}
    out = {
        "regions": {rid: regions[rid] for rid in order},
        "regionCorrelation": {"regions": order, "matrix": [[round(float(x), 4) for x in row] for row in corr]},
        "regionalShare": share,
        "card": {
            "data": f"survival model validation fit (trained before 2023-01), out-of-time loan-months "
                    f"{quarter_label(quarters[0])} to {quarter_label(quarters[-1])}, complete quarters only",
            "method": "probit deviation of realised from expected defaults per region and quarter, the quarter's "
                      "exposure-weighted national deviation removed; variance net of exact binomial noise; "
                      "rho_r = v (1 - rho_n) / (1 + v); chi-square interval on (regions - 1) x quarters degrees of freedom",
            "rho_national": sr.RHO_NATIONAL,
            "level": sr.LEVEL,
            "variance_v": round(est["variance_v"], 6),
            "bootstrap_interval_for_comparison": [round(x, 4) for x in est["bootstrap_interval"]],
            "defaults_per_region_quarter": {"min": int(events.min()), "median": int(np.median(events)), "max": int(events.max())},
            "states_in_no_region": sorted(set(ss.STATES) - set(region_of)),
            "recovery": {"simulations": RECOVERY_SIMS, "coverage_at_known_share": recovery},
            "not_distinguishable_from_zero": share["default"] == 0.0,
            "correlation_note": "Ledoit-Wolf, shrunk toward the identity; removing the national deviation biases "
                                "the correlations slightly negative (about -1 / (regions - 1) for equal regions).",
        },
    }
    OUT.write_text(json.dumps(out, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({k: out[k] for k in ("regionCorrelation", "regionalShare")}, indent=2))
    print(f"wrote {OUT}; rerun export_segment_graph.py to add it to the graph file and card")


if __name__ == "__main__":
    main()
