"""Builds the state delinquency panel for the segment graph (openspec/changes/archive/2026-10-19-segment-graph-models, tasks 1.2-1.3).

Stage 1, cell counts. The monthly panel has no property state, so each panel part file is joined to its own
vintage's loan-level file on loan_sequence_number and reduced to counts per state, month, loan-age bucket,
credit-score band and LTV band: loans active (positive actual UPB), loans 90+ days past due or in REO, and of
those the ones flagged for disaster (delinquency_due_to_disaster = Y) and for borrower assistance
(borrower_assistance_status_code F, R or T). Each part file is one bounded slice (300,000 rows, the
pipeline/chunked.py convention) and gets its own output under data/processed/segments/cells_parts/; a slice whose
output exists is skipped, so an interrupted build resumes, and deleting one slice's output rebuilds that slice
only. Outputs are written to a temporary name and renamed, so a killed run never leaves a half-written slice.

Stage 2, series. Months after the last complete month (segment_series.last_complete_month) are dropped. From
the cell counts, the standardised delinquency series of segment_series.standardised for
the 50 states and DC, in three variants from the same counts (primary, without disaster-flagged and without
assistance-flagged delinquencies), and the coverage table. Writes, under data/processed/segments/:
  state_month_cells.parquet   the cell counts, every place in the loan-level table including territories
  state_ratio.parquet         state, month, variant, observed, expected, n_active, n_active_unrated, log_ratio
  coverage.parquet            share of months with a ratio, per state and variant

The national active-loan count is printed next to data/docs/portfolio_monthly_series.parquet. They differ:
that file came from an earlier, uncommitted build over an earlier panel (1,592 part files against 2,264 today;
it has 784 active loans in 2020-01, when today's panel already carries the 2017-2019 vintages), the same
provenance gap build_segment_series.py documents. The check that matters is internal: the national count here
equals the sum of the panel's active rows that have a state.

Run from backend/:  python build_state_panel.py [--workers 6]
"""
from __future__ import annotations

import argparse
import glob
import os
import time
from concurrent.futures import ProcessPoolExecutor
from pathlib import Path

import polars as pl

import segment_series as ss

ROOT = Path(__file__).resolve().parents[1]
PANEL = ROOT / "data" / "processed" / "monthly_panel"
LOAN_LEVEL = ROOT / "data" / "processed" / "loan_level"
OUT = ROOT / "data" / "processed" / "segments"
PARTS = OUT / "cells_parts"
PORTFOLIO = ROOT / "data" / "docs" / "portfolio_monthly_series.parquet"

PANEL_COLUMNS = ["loan_sequence_number", "monthly_reporting_period", "current_actual_upb", "loan_age",
                 "current_loan_delinquency_status", "delinquency_due_to_disaster", "borrower_assistance_status_code"]


def quarters() -> dict[str, list[Path]]:
    out: dict[str, list[Path]] = {}
    for p in sorted(glob.glob(str(PANEL / "orig_year=*" / "orig_quarter=*" / "*" / "part-*.parquet"))):
        path = Path(p)
        out.setdefault(path.parent.name, []).append(path)
    return out


def loan_file(quarter: str) -> Path:
    year, q = quarter[:4], quarter[4:]
    return LOAN_LEVEL / f"orig_year={year}" / f"orig_quarter={q}" / f"{quarter}.parquet"


def slice_counts(path: Path, loans: pl.DataFrame) -> tuple[pl.DataFrame, int]:
    panel = pl.read_parquet(path, columns=PANEL_COLUMNS).filter(pl.col("current_actual_upb") > 0)
    joined = panel.join(loans, on="loan_sequence_number", how="inner")
    dq = ss.delinquent()
    cells = (joined.with_columns(
        state=pl.col("property_state"),
        month=(pl.col("monthly_reporting_period").dt.year() * 12 + pl.col("monthly_reporting_period").dt.month() - 1).cast(pl.Int32),
        age_bucket=ss.bucket(pl.col("loan_age"), ss.AGE_EDGES),
        is_dq=dq,
    ).group_by(ss.KEY).agg(
        n_active=pl.len().cast(pl.Int64),
        n_delinquent=pl.col("is_dq").sum().cast(pl.Int64),
        n_delinquent_disaster=(pl.col("is_dq") & (pl.col("delinquency_due_to_disaster") == "Y")).sum().cast(pl.Int64),
        n_delinquent_assistance=(pl.col("is_dq") & pl.col("borrower_assistance_status_code").is_in(["F", "R", "T"])).sum().cast(pl.Int64),
    ))
    return cells, panel.height - joined.height


def build_quarter(quarter: str, paths: list[Path]) -> tuple[str, int, int]:
    """Writes the missing slices of one vintage quarter. Returns (quarter, slices built, panel rows without a state)."""
    todo = [p for p in paths if not (PARTS / f"{quarter}_{p.stem}.parquet").exists()]
    if not todo:
        return quarter, 0, 0
    lf = loan_file(quarter)
    if not lf.exists():
        raise ss.BuildError(f"no loan-level file for vintage {quarter} ({lf})")
    loans = (pl.read_parquet(lf, columns=["loan_sequence_number", "property_state", "credit_score", "original_ltv"])
             .drop_nulls("property_state").unique("loan_sequence_number")
             .with_columns(score_band=ss.bucket(pl.col("credit_score"), ss.SCORE_EDGES),
                           ltv_band=ss.bucket(pl.col("original_ltv"), ss.LTV_EDGES, right_closed=True))
             .select("loan_sequence_number", "property_state", "score_band", "ltv_band"))
    unmatched = 0
    for path in todo:
        cells, missed = slice_counts(path, loans)
        unmatched += missed
        target = PARTS / f"{quarter}_{path.stem}.parquet"
        tmp = target.with_suffix(".tmp")
        cells.write_parquet(tmp)
        os.replace(tmp, target)
    return quarter, len(todo), unmatched


def combine() -> pl.DataFrame:
    total = None
    files = sorted(PARTS.glob("*.parquet"))
    for i in range(0, len(files), 200):
        batch = pl.concat([pl.read_parquet(f) for f in files[i:i + 200]])
        if total is not None:
            batch = pl.concat([total, batch])
        total = batch.group_by(ss.KEY).agg(pl.col(c).sum() for c in ss.COUNTS)
    return total.sort(ss.KEY)


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--workers", type=int, default=6)
    args = ap.parse_args()

    PARTS.mkdir(parents=True, exist_ok=True)
    for stale in PARTS.glob("*.tmp"):
        stale.unlink()
    plan = quarters()
    n_slices = sum(len(v) for v in plan.values())
    print(f"{n_slices} panel slices in {len(plan)} vintage quarters; "
          f"{sum(1 for _ in PARTS.glob('*.parquet'))} already built", flush=True)

    started, built, unmatched = time.time(), 0, 0
    with ProcessPoolExecutor(max_workers=args.workers) as pool:
        for quarter, n, missed in pool.map(build_quarter, plan.keys(), plan.values()):
            built, unmatched = built + n, unmatched + missed
            if n:
                print(f"  {quarter}: {n} slices  ({built} built, {time.time() - started:.0f}s)", flush=True)
    print(f"stage 1: built {built} slices; {unmatched:,} active panel rows had no state", flush=True)
    if len(list(PARTS.glob("*.parquet"))) != n_slices:
        raise ss.BuildError("slice outputs do not match the panel's part files")

    cells = combine()
    ss.check_cells(cells)

    national = (cells.filter(pl.col("state").is_in(ss.STATES)).group_by("month")
                .agg(pl.col("n_active").sum(), pl.col("n_delinquent").sum()).sort("month"))
    if PORTFOLIO.exists():
        doc = (pl.read_parquet(PORTFOLIO, columns=["monthly_reporting_period", "n_active"])
               .with_columns(month=(pl.col("monthly_reporting_period").dt.year() * 12 + pl.col("monthly_reporting_period").dt.month() - 1).cast(pl.Int32))
               .select("month", pl.col("n_active").alias("portfolio_doc")))
        cmp = national.join(doc, on="month", how="inner")
        print("\nnational active loans, this build vs data/docs/portfolio_monthly_series.parquet (see header):")
        for row in cmp.filter(pl.col("month") % 12 == 0).iter_rows(named=True):
            print(f"  {ss.month_label(row['month'])}  {row['n_active']:>11,}  {row['portfolio_doc']:>11,}  "
                  f"ratio {row['n_active'] / max(row['portfolio_doc'], 1):.2f}")

    last = ss.last_complete_month(national)
    dropped = national.filter(pl.col("month") > last)
    if dropped.height:
        print(f"\nlast complete month {ss.month_label(last)}; dropped {dropped.height} later months "
              f"(at most {dropped['n_active'].max():,} active loans, against {national['n_active'].max():,} at the peak)")
    cells = cells.filter(pl.col("month") <= last)
    national = national.filter(pl.col("month") <= last)
    months = national["month"].to_list()
    frames, cover = [], []
    for name, expr in ss.VARIANTS.items():
        series = ss.standardised(cells, expr).with_columns(variant=pl.lit(name))
        frames.append(series)
        cover.append(ss.coverage(series, months).with_columns(variant=pl.lit(name)))
    ratio = pl.concat(frames).select("state", "month", "variant", "observed", "expected", "n_active",
                                     "n_active_unrated", "log_ratio").sort("variant", "state", "month")
    coverage = pl.concat(cover)

    OUT.mkdir(parents=True, exist_ok=True)
    cells.write_parquet(OUT / "state_month_cells.parquet")
    ratio.write_parquet(OUT / "state_ratio.parquet")
    coverage.write_parquet(OUT / "coverage.parquet")

    primary = coverage.filter(pl.col("variant") == "primary")
    below = primary.filter(pl.col("coverage") < ss.MIN_COVERAGE)
    print(f"\nmonths {ss.month_label(months[0])} .. {ss.month_label(months[-1])} ({len(months)}); cells {cells.height:,} rows")
    print(f"states at {ss.MIN_COVERAGE:.0%} coverage or more (primary): {primary.height - below.height} of {primary.height}")
    print("below it: " + (", ".join(f"{r['state']} {r['coverage']:.0%}" for r in below.iter_rows(named=True)) or "none"))
    print(f"wrote {OUT / 'state_month_cells.parquet'}, state_ratio.parquet, coverage.parquet")


if __name__ == "__main__":
    main()
