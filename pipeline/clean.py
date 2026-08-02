"""Stage 3: normalize sentinels to null, enforce types (incl. date parsing),
deduplicate, and report a per-quarter cleaning summary.

Origination tables (<=300K rows/quarter) are cleaned in a single pass -- this
has proven safe. Performance tables can be tens of millions of rows/quarter
(2020Q2 alone is ~43M rows); Polars' `.unique()` -- even with the streaming
engine -- was observed pulling 6.78GB+ resident on a 16GB machine trying to
dedup that table, so performance tables are processed in bounded row chunks
via pipeline.chunked instead, with each chunk written as its own part file.

Dedup is performed within each chunk. Freddie Mac's published files keep
each loan's records contiguous, and this dataset has shown zero duplicate
rows in every quarter sampled during development, so an exact global dedup
(which would require holding every key in memory) is not worth the memory
risk it reintroduces; chunk-wise dedup remains an effective safety net
against accidental exact repeats, and a real cross-chunk duplicate would
have to land exactly on a chunk boundary to be missed.
"""

from __future__ import annotations

import argparse
import json

import polars as pl

from pipeline import chunked, config, schema
from pipeline.readable import _KIND_TO_DTYPE  # int/float/date_yyyymm -> dtype

SUMMARY_PATH = config.DOCS_DIR / "cleaning_summary.jsonl"


def _sentinel_replace_expr(col: schema.ColumnSpec) -> pl.Expr | None:
    if not col.sentinels:
        return None
    if col.kind == "string":
        values = [s for s in col.sentinels if s != ""]
        if not values:
            return None
        return (
            pl.when(pl.col(col.name).is_in(values))
            .then(None)
            .otherwise(pl.col(col.name))
            .alias(col.name)
        )
    dtype = _KIND_TO_DTYPE[col.kind]
    caster = int if dtype in (pl.Int64, pl.Int32) else float
    values = [caster(s) for s in col.sentinels if s != ""]
    if not values:
        return None
    return (
        pl.when(pl.col(col.name).is_in(values))
        .then(None)
        .otherwise(pl.col(col.name))
        .alias(col.name)
    )


def _date_parse_expr(name: str) -> pl.Expr:
    year = pl.col(name) // 100
    month = pl.col(name) % 100
    return (
        pl.when(pl.col(name).is_not_null() & (month >= 1) & (month <= 12))
        .then(pl.date(year, month, 1))
        .otherwise(None)
        .alias(name)
    )


def _row_and_null_stats(lf: pl.LazyFrame, sentinel_cols: list[schema.ColumnSpec]) -> tuple[int, dict[str, int]]:
    exprs = [pl.len().alias("__len__")] + [pl.col(c.name).null_count().alias(c.name) for c in sentinel_cols]
    row = lf.select(exprs).collect()
    n = row["__len__"][0]
    nulls = {c.name: row[c.name][0] for c in sentinel_cols}
    return n, nulls


def _build_clean_lazyframe(
    lf: pl.LazyFrame, columns: list[schema.ColumnSpec], dedup_keys: list[str]
) -> pl.LazyFrame:
    sentinel_exprs = [e for e in (_sentinel_replace_expr(c) for c in columns) if e is not None]
    if sentinel_exprs:
        lf = lf.with_columns(sentinel_exprs)

    date_exprs = [_date_parse_expr(name) for name in schema.date_columns(columns)]
    if date_exprs:
        lf = lf.with_columns(date_exprs)

    return lf.unique(subset=dedup_keys, keep="first", maintain_order=False)


def clean_origination_to_parquet(source_path, dest_path, columns: list[schema.ColumnSpec]) -> dict:
    sentinel_cols = [c for c in columns if c.sentinels]

    source_lf = pl.scan_parquet(source_path)
    input_rows, null_before = _row_and_null_stats(source_lf, sentinel_cols)

    clean_lf = _build_clean_lazyframe(source_lf, columns, ["loan_sequence_number"])
    clean_lf.sink_parquet(dest_path)

    dest_lf = pl.scan_parquet(dest_path)
    output_rows, null_after = _row_and_null_stats(dest_lf, sentinel_cols)

    sentinel_nulls_introduced = sum(null_after[c.name] - null_before[c.name] for c in sentinel_cols)
    return {
        "input_rows": input_rows,
        "output_rows": output_rows,
        "duplicates_removed": input_rows - output_rows,
        "sentinel_nulls_introduced": int(sentinel_nulls_introduced),
    }


def clean_performance_to_parts(source_path, dest_dir, columns: list[schema.ColumnSpec]) -> dict:
    sentinel_cols = [c for c in columns if c.sentinels]
    sentinel_exprs = [e for e in (_sentinel_replace_expr(c) for c in columns) if e is not None]
    date_exprs = [_date_parse_expr(name) for name in schema.date_columns(columns)]
    dedup_keys = ["loan_sequence_number", "monthly_reporting_period"]

    input_rows = 0
    output_rows = 0
    null_before = {c.name: 0 for c in sentinel_cols}
    null_after = {c.name: 0 for c in sentinel_cols}

    def _process() -> list[pl.DataFrame]:
        nonlocal input_rows, output_rows
        for chunk in chunked.iter_row_slices(source_path):
            input_rows += chunk.height
            for c in sentinel_cols:
                null_before[c.name] += chunk[c.name].null_count()

            if sentinel_exprs:
                chunk = chunk.with_columns(sentinel_exprs)
            for c in sentinel_cols:
                null_after[c.name] += chunk[c.name].null_count()

            if date_exprs:
                chunk = chunk.with_columns(date_exprs)

            chunk = chunk.unique(subset=dedup_keys, keep="first", maintain_order=True)
            output_rows += chunk.height
            yield chunk

    chunked.write_parts(_process(), dest_dir)

    sentinel_nulls_introduced = sum(null_after[c.name] - null_before[c.name] for c in sentinel_cols)
    return {
        "input_rows": input_rows,
        "output_rows": output_rows,
        "duplicates_removed": input_rows - output_rows,
        "sentinel_nulls_introduced": int(sentinel_nulls_introduced),
    }


def _append_summary(entry: dict) -> None:
    config.ensure_output_dirs()
    existing = []
    if SUMMARY_PATH.exists():
        with open(SUMMARY_PATH, "r", encoding="utf-8") as f:
            existing = [json.loads(line) for line in f if line.strip()]
    existing = [e for e in existing if not (e["quarter"] == entry["quarter"] and e["table"] == entry["table"])]
    existing.append(entry)
    with open(SUMMARY_PATH, "w", encoding="utf-8") as f:
        for e in existing:
            f.write(json.dumps(e) + "\n")


def clean_quarter(q: config.Quarter, force: bool = False) -> bool:
    if not (q.readable_origination_path.exists() and q.readable_performance_path.exists()):
        print(f"[skip] {q.label}: readable files not found, run readable conversion first")
        return False

    perf_done = q.clean_performance_dir.exists() and any(q.clean_performance_dir.glob("part-*.parquet"))
    already_done = q.clean_origination_path.exists() and perf_done
    if already_done and not force:
        print(f"[skip] {q.label}: already cleaned")
        return False

    q.clean_origination_path.parent.mkdir(parents=True, exist_ok=True)

    orig_summary = clean_origination_to_parquet(
        q.readable_origination_path, q.clean_origination_path, schema.ORIGINATION_COLUMNS
    )
    orig_summary.update(quarter=q.label, table="origination")
    _append_summary(orig_summary)
    print(f"[ok]   {q.label}: origination {orig_summary}")

    perf_summary = clean_performance_to_parts(
        q.readable_performance_path, q.clean_performance_dir, schema.PERFORMANCE_COLUMNS
    )
    perf_summary.update(quarter=q.label, table="performance")
    _append_summary(perf_summary)
    print(f"[ok]   {q.label}: performance {perf_summary}")

    return True


def clean_all(quarters: list[config.Quarter], force: bool = False) -> None:
    config.ensure_output_dirs()
    for q in quarters:
        clean_quarter(q, force=force)


def main() -> None:
    parser = argparse.ArgumentParser(description="Clean readable Parquet tables.")
    parser.add_argument("--quarter", action="append", help="Specific quarter label (e.g. 2025Q3); repeatable")
    parser.add_argument("--force", action="store_true", help="Re-clean even if clean files already exist")
    args = parser.parse_args()

    if args.quarter:
        quarters = [config.parse_quarter(q) for q in args.quarter]
    else:
        quarters = config.all_quarters()

    clean_all(quarters, force=args.force)


if __name__ == "__main__":
    main()
