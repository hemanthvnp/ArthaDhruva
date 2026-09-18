"""Stage 1: extract quarterly ZIPs into data/raw/<quarter>/."""

from __future__ import annotations

import argparse
import zipfile

from pipeline import config, schema


class ExtractionError(RuntimeError):
    pass


def _validate_schema_version(q: config.Quarter) -> None:
    """Confirms this quarter's raw column counts match a known schema layout -- selecting
    the layout IS the validation (schema.origination_columns_for/performance_columns_for
    raise SchemaVersionError for anything unrecognized)."""
    try:
        schema.origination_columns_for(q)
        schema.performance_columns_for(q)
    except schema.SchemaVersionError as e:
        raise ExtractionError(str(e)) from e


def _resolve_member(zip_path, names: set[str], canonical: str, alt: str) -> str:
    """Freddie Mac changed its internal filename convention at some point (orig_<label>.txt/
    perf_<label>.txt instead of historical_data_<label>.txt/historical_data_time_<label>.txt).
    Like the column-layout change (see schema.py's module docstring), this turned out to be
    a property of download time, not origination vintage -- freshly-downloaded archives use
    the new names regardless of quarter, older ones on disk still use the old names -- so
    accept either and normalize below, rather than branching on the quarter."""
    if canonical in names:
        return canonical
    if alt in names:
        return alt
    raise ExtractionError(f"{zip_path}: expected member '{canonical}' (or '{alt}') not found in archive")


def extract_quarter(q: config.Quarter, force: bool = False) -> bool:
    """Extract one quarter's ZIP. Returns True if extraction ran, False if skipped."""
    if not q.zip_path.exists():
        print(f"[skip] {q.label}: no ZIP found at {q.zip_path}")
        return False

    already_done = q.raw_origination_path.exists() and q.raw_performance_path.exists()
    if already_done and not force:
        print(f"[skip] {q.label}: already extracted")
        return False

    out_dir = q.raw_origination_path.parent
    out_dir.mkdir(parents=True, exist_ok=True)

    with zipfile.ZipFile(q.zip_path) as zf:
        names = set(zf.namelist())
        origination_member = _resolve_member(q.zip_path, names, q.origination_member, f"orig_{q.label}.txt")
        performance_member = _resolve_member(q.zip_path, names, q.performance_member, f"perf_{q.label}.txt")
        zf.extract(origination_member, path=out_dir)
        zf.extract(performance_member, path=out_dir)

    # Normalize to the canonical filename if this quarter's ZIP used the alternate naming --
    # raw_origination_path/raw_performance_path (what every later stage reads) are fixed
    # regardless of which convention this particular archive used.
    if origination_member != q.origination_member:
        (out_dir / origination_member).replace(q.raw_origination_path)
    if performance_member != q.performance_member:
        (out_dir / performance_member).replace(q.raw_performance_path)

    _validate_schema_version(q)

    print(f"[ok]   {q.label}: extracted to {out_dir}")
    return True


def extract_all(quarters: list[config.Quarter], force: bool = False) -> None:
    config.ensure_output_dirs()
    for q in quarters:
        extract_quarter(q, force=force)


def main() -> None:
    parser = argparse.ArgumentParser(description="Extract quarterly ZIP archives.")
    parser.add_argument("--quarter", action="append", help="Specific quarter label (e.g. 2025Q3); repeatable")
    parser.add_argument("--force", action="store_true", help="Re-extract even if raw files already exist")
    args = parser.parse_args()

    if args.quarter:
        quarters = [config.parse_quarter(q) for q in args.quarter]
    else:
        quarters = config.all_quarters()

    extract_all(quarters, force=args.force)


if __name__ == "__main__":
    main()
