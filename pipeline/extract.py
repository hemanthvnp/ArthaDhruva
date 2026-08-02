"""Stage 1: extract quarterly ZIPs into data/raw/<quarter>/."""

from __future__ import annotations

import argparse
import zipfile

from pipeline import config


class ExtractionError(RuntimeError):
    pass


def _validate_column_count(path, expected: int = config.EXPECTED_COLUMN_COUNT) -> None:
    with open(path, "r", encoding="utf-8", errors="strict") as f:
        first_line = f.readline().rstrip("\n").rstrip("\r")
    actual = first_line.count("|") + 1
    if actual != expected:
        raise ExtractionError(
            f"{path}: expected {expected} pipe-delimited columns, found {actual}"
        )


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
        for member in (q.origination_member, q.performance_member):
            if member not in names:
                raise ExtractionError(f"{q.zip_path}: expected member '{member}' not found in archive")
        zf.extract(q.origination_member, path=out_dir)
        zf.extract(q.performance_member, path=out_dir)

    _validate_column_count(q.raw_origination_path)
    _validate_column_count(q.raw_performance_path)

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
