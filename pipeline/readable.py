"""Stage 2: apply the Freddie Mac schema to raw pipe-delimited files and
write readable, headered, typed Parquet tables (no sentinel cleaning yet)."""

from __future__ import annotations

import argparse

import polars as pl

from pipeline import config, schema

_KIND_TO_DTYPE = {
    "int": pl.Int64,
    "float": pl.Float64,
    "date_yyyymm": pl.Int32,
    # "string" columns are left as Utf8 (already the scan dtype).
}


class ReadabilityError(RuntimeError):
    pass


def _raw_column_count(path) -> int:
    with open(path, "r", encoding="utf-8", errors="strict") as f:
        first_line = f.readline().rstrip("\n").rstrip("\r")
    return first_line.count("|") + 1


def _apply_schema(raw_path, columns: list[schema.ColumnSpec]) -> pl.LazyFrame:
    expected = config.EXPECTED_COLUMN_COUNT
    actual = _raw_column_count(raw_path)
    if actual != expected:
        raise ReadabilityError(
            f"{raw_path}: expected {expected} columns, found {actual}; refusing to apply schema"
        )

    lf = pl.scan_csv(
        raw_path,
        separator="|",
        has_header=False,
        infer_schema_length=0,  # read every column as Utf8; we cast explicitly below
        quote_char=None,
    )

    raw_names = lf.collect_schema().names()
    if len(raw_names) != len(columns):
        raise ReadabilityError(
            f"{raw_path}: schema has {len(columns)} columns but file has {len(raw_names)}"
        )

    rename_map = dict(zip(raw_names, schema.column_names(columns)))
    lf = lf.rename(rename_map)

    cast_exprs = []
    for col in columns:
        dtype = _KIND_TO_DTYPE.get(col.kind)
        if dtype is None:
            continue  # string columns: no cast needed
        cast_exprs.append(pl.col(col.name).cast(dtype, strict=False).alias(col.name))
    if cast_exprs:
        lf = lf.with_columns(cast_exprs)

    return lf


def _count_lines(path) -> int:
    count = 0
    with open(path, "r", encoding="utf-8", errors="strict") as f:
        for _ in f:
            count += 1
    return count


def convert_quarter(q: config.Quarter, force: bool = False) -> bool:
    """Convert one quarter's raw files to readable Parquet. Returns True if it ran."""
    if not (q.raw_origination_path.exists() and q.raw_performance_path.exists()):
        print(f"[skip] {q.label}: raw files not found, run extraction first")
        return False

    already_done = q.readable_origination_path.exists() and q.readable_performance_path.exists()
    if already_done and not force:
        print(f"[skip] {q.label}: already converted to readable")
        return False

    q.readable_origination_path.parent.mkdir(parents=True, exist_ok=True)
    q.readable_performance_path.parent.mkdir(parents=True, exist_ok=True)

    orig_lf = _apply_schema(q.raw_origination_path, schema.ORIGINATION_COLUMNS)
    orig_lf.sink_parquet(q.readable_origination_path)

    perf_lf = _apply_schema(q.raw_performance_path, schema.PERFORMANCE_COLUMNS)
    perf_lf.sink_parquet(q.readable_performance_path)

    raw_orig_lines = _count_lines(q.raw_origination_path)
    readable_orig_rows = pl.scan_parquet(q.readable_origination_path).select(pl.len()).collect().item()
    if raw_orig_lines != readable_orig_rows:
        raise ReadabilityError(
            f"{q.label}: origination row count mismatch (raw lines={raw_orig_lines}, "
            f"readable rows={readable_orig_rows})"
        )

    raw_perf_lines = _count_lines(q.raw_performance_path)
    readable_perf_rows = pl.scan_parquet(q.readable_performance_path).select(pl.len()).collect().item()
    if raw_perf_lines != readable_perf_rows:
        raise ReadabilityError(
            f"{q.label}: performance row count mismatch (raw lines={raw_perf_lines}, "
            f"readable rows={readable_perf_rows})"
        )

    print(
        f"[ok]   {q.label}: readable origination rows={readable_orig_rows}, "
        f"performance rows={readable_perf_rows}"
    )
    return True


def convert_all(quarters: list[config.Quarter], force: bool = False) -> None:
    config.ensure_output_dirs()
    for q in quarters:
        convert_quarter(q, force=force)


def main() -> None:
    parser = argparse.ArgumentParser(description="Convert raw files to readable Parquet.")
    parser.add_argument("--quarter", action="append", help="Specific quarter label (e.g. 2025Q3); repeatable")
    parser.add_argument("--force", action="store_true", help="Re-convert even if readable files already exist")
    args = parser.parse_args()

    if args.quarter:
        quarters = [config.parse_quarter(q) for q in args.quarter]
    else:
        quarters = config.all_quarters()

    convert_all(quarters, force=args.force)


if __name__ == "__main__":
    main()
