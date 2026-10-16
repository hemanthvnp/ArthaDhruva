"""Build data/docs/segment_monthly_series.parquet: monthly delinquency rate per state.

Method (as documented in notebooks/segment_correlation_graph.ipynb): the monthly performance panel has no
property state, so each panel part file is joined to the loan-level table on loan_sequence_number and
reduced to (state, month) counts. Counts are additive over loan-months, so one part file at a time is
enough and a loan that straddles two part files does no harm. Memory stays bounded for the same reason
pipeline/chunked.py gives: full-file group-bys on this dataset's largest tables do not fit in 16 GB.

Columns written: property_state, monthly_reporting_period, n_active, n_delinquent, delinquency_rate
(= n_delinquent / n_active). Months are limited to 2020-01 .. 2025-09 and states to the TOP_STATES most
common in the loan-level table, unless overridden.

Delinquent means --min-status or worse (default 3 = 90+ days past due, plus REO acquisition "RA"), the same
default event the rest of the project uses. Active means the loan reports a positive actual UPB.

NOT A BIT-FOR-BIT REPRODUCTION of the committed file. That file was produced by an earlier script that was
never committed, from an earlier panel build (1,592 part files; today's panel has 2,264). Its counts do not
match today's panel under any status cut-off I tried (e.g. California, 2025-09: 1,130,874 active and
14,111 delinquent there; 1,229,118 active and 5,230 at 90+ days here), so its exact definitions are
unknown. The default output path is therefore a new file; pass --out to replace the committed one on purpose.

Run from backend/:  python build_segment_series.py [--min-status 3] [--states CA,FL,...] [--out PATH]
Resumable: per-file partial results are cached under data/processed/segment_series_parts/ and reused.
"""
from __future__ import annotations

import argparse
import datetime as dt
import glob
from pathlib import Path

import polars as pl

ROOT = Path(__file__).resolve().parents[1]
PANEL = str(ROOT / "data" / "processed" / "monthly_panel" / "orig_year=*" / "orig_quarter=*" / "*" / "part-*.parquet")
LOAN_LEVEL = str(ROOT / "data" / "processed" / "loan_level" / "orig_year=*" / "orig_quarter=*" / "*.parquet")
CACHE = ROOT / "data" / "processed" / "segment_series_parts"
DEFAULT_OUT = ROOT / "data" / "docs" / "segment_monthly_series_rebuilt.parquet"

FIRST_MONTH, LAST_MONTH = dt.date(2020, 1, 1), dt.date(2025, 9, 1)
TOP_STATES = 15


def load_state_map() -> pl.DataFrame:
    # Read file by file: the loan-level files do not all carry the same columns, so one glob scan fails.
    parts = [pl.read_parquet(p, columns=["loan_sequence_number", "property_state"]) for p in sorted(glob.glob(LOAN_LEVEL))]
    return pl.concat(parts).unique("loan_sequence_number").drop_nulls("property_state")


def is_delinquent(min_status: int) -> pl.Expr:
    status = pl.col("current_loan_delinquency_status")
    n = status.cast(pl.Int32, strict=False)
    return ((n >= min_status) | ((status == "RA") & (min_status <= 3))).fill_null(False)


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--min-status", type=int, default=3, help="delinquency status counted as delinquent (3 = 90+ days)")
    ap.add_argument("--states", help="comma-separated state codes; default is the most common in the loan-level table")
    ap.add_argument("--out", type=Path, default=DEFAULT_OUT)
    args = ap.parse_args()

    state_map = load_state_map()
    if args.states:
        states = [s.strip().upper() for s in args.states.split(",")]
    else:
        states = state_map.group_by("property_state").len().sort("len", "property_state", descending=[True, False]).head(TOP_STATES)["property_state"].to_list()
    state_map = state_map.filter(pl.col("property_state").is_in(states))
    print(f"states ({len(states)}): {sorted(states)}  loans mapped: {state_map.height:,}", flush=True)

    CACHE.mkdir(parents=True, exist_ok=True)
    tag = f"s{args.min_status}_{'-'.join(sorted(states))}"
    files = sorted(glob.glob(PANEL))
    partials = []
    for i, path in enumerate(files):
        cache = CACHE / f"{tag}_{i:05d}.parquet"
        if cache.exists():
            partials.append(cache)
            continue
        part = (
            pl.read_parquet(path, columns=["loan_sequence_number", "monthly_reporting_period", "current_actual_upb", "current_loan_delinquency_status"])
            .filter(pl.col("monthly_reporting_period").is_between(FIRST_MONTH, LAST_MONTH) & (pl.col("current_actual_upb") > 0))
            .join(state_map, on="loan_sequence_number", how="inner")
            .group_by("property_state", "monthly_reporting_period")
            .agg(n_active=pl.len().cast(pl.UInt32), n_delinquent=is_delinquent(args.min_status).sum().cast(pl.UInt32))
        )
        part.write_parquet(cache)
        partials.append(cache)
        if i % 100 == 0:
            print(f"{i}/{len(files)}", flush=True)

    out = (
        pl.concat([pl.read_parquet(p) for p in partials])
        .group_by("property_state", "monthly_reporting_period")
        .agg(pl.col("n_active").sum().cast(pl.UInt32), pl.col("n_delinquent").sum().cast(pl.UInt32))
        .with_columns(delinquency_rate=pl.col("n_delinquent") / pl.col("n_active"))
        .sort("property_state", "monthly_reporting_period")
    )
    args.out.parent.mkdir(parents=True, exist_ok=True)
    out.write_parquet(args.out)
    print(f"wrote {args.out}  {out.shape}  ({out['property_state'].n_unique()} states x {out['monthly_reporting_period'].n_unique()} months)")


if __name__ == "__main__":
    main()
