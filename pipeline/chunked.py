"""Bounded row-chunk iteration over Parquet files using Polars' native
reader only (no PyArrow -- PyArrow's native Parquet extension is blocked by
Application Control policy on this machine).

Polars' own out-of-core `.unique()`/`group_by()` still materializes far more
than expected on this machine (observed 6.78GB+ resident for a single
`.unique()` over a 43M-row table with only ~16GB total RAM), so instead of
trusting any engine's internal streaming, we read and process the largest
tables (performance data can be 40M+ rows/quarter) in small, bounded slices
and write each slice out as its own part file.
"""

from __future__ import annotations

from collections.abc import Iterator
from pathlib import Path

import polars as pl

BATCH_ROWS = 300_000


def iter_row_slices(path, batch_rows: int = BATCH_ROWS) -> Iterator[pl.DataFrame]:
    total = pl.scan_parquet(path).select(pl.len()).collect().item()
    offset = 0
    while offset < total:
        yield pl.scan_parquet(path).slice(offset, batch_rows).collect()
        offset += batch_rows


def write_parts(frames: Iterator[pl.DataFrame], dest_dir: Path) -> int:
    """Write each frame as its own part file under dest_dir. Returns row count written."""
    dest_dir.mkdir(parents=True, exist_ok=True)
    for old in dest_dir.glob("part-*.parquet"):
        old.unlink()
    total_rows = 0
    for i, df in enumerate(frames):
        df.write_parquet(dest_dir / f"part-{i:05d}.parquet")
        total_rows += df.height
    return total_rows


def scan_parts(dest_dir: Path) -> pl.LazyFrame:
    return pl.scan_parquet(dest_dir / "part-*.parquet")
