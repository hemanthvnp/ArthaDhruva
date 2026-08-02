"""Task 7.3/7.4: spot-check value distributions across stages and record
final dataset stats (row counts, partitions, default rate, disk size)."""

from __future__ import annotations

import json

import polars as pl

from pipeline import config


def _dir_size_bytes(path) -> int:
    if not path.exists():
        return 0
    return sum(f.stat().st_size for f in path.rglob("*") if f.is_file())


def _fmt_gb(num_bytes: int) -> str:
    return f"{num_bytes / (1024**3):.2f} GB"


def spot_check_quarter(label: str) -> dict:
    """Compare a few key column distributions across readable -> clean -> processed
    for one quarter, to confirm cleaning/preprocessing didn't silently corrupt data."""
    q = config.parse_quarter(label)
    checks = {}

    readable_df = pl.read_parquet(q.readable_origination_path)
    clean_df = pl.read_parquet(q.clean_origination_path)

    checks["credit_score_mean_readable"] = readable_df.filter(
        pl.col("credit_score") != 9999
    )["credit_score"].mean()
    checks["credit_score_mean_clean"] = clean_df["credit_score"].mean()

    checks["readable_row_count"] = readable_df.height
    checks["clean_row_count"] = clean_df.height

    processed_path = (
        config.LOAN_LEVEL_DIR / f"orig_year={q.year}" / f"orig_quarter={q.quarter}" / f"{q.label}.parquet"
    )
    if processed_path.exists():
        processed_df = pl.read_parquet(processed_path)
        checks["processed_row_count"] = processed_df.height
        checks["default_rate"] = (
            processed_df["default_target"].mean() if "default_target" in processed_df.columns else None
        )
        checks["credit_score_mean_processed"] = processed_df["credit_score"].mean()

    return checks


def final_dataset_stats() -> dict:
    quarters = config.all_quarters()
    per_quarter = []
    total_loans = 0
    total_defaults = 0
    total_panel_rows = 0

    for q in quarters:
        path = config.LOAN_LEVEL_DIR / f"orig_year={q.year}" / f"orig_quarter={q.quarter}" / f"{q.label}.parquet"
        panel_dir = config.MONTHLY_PANEL_DIR / f"orig_year={q.year}" / f"orig_quarter={q.quarter}" / q.label
        if not path.exists():
            continue
        df = pl.scan_parquet(path)
        n = df.select(pl.len()).collect().item()
        n_default = df.filter(pl.col("default_target") == 1).select(pl.len()).collect().item()
        panel_n = (
            pl.scan_parquet(panel_dir / "part-*.parquet").select(pl.len()).collect().item()
            if panel_dir.exists() and any(panel_dir.glob("part-*.parquet"))
            else 0
        )

        total_loans += n
        total_defaults += n_default
        total_panel_rows += panel_n
        per_quarter.append(
            {"quarter": q.label, "loans": n, "defaults": n_default, "monthly_panel_rows": panel_n}
        )

    stats = {
        "quarters_processed": len(per_quarter),
        "total_loans": total_loans,
        "total_defaults": total_defaults,
        "overall_default_rate": (total_defaults / total_loans) if total_loans else None,
        "total_monthly_panel_rows": total_panel_rows,
        "per_quarter": per_quarter,
        "disk_usage": {
            "raw": _fmt_gb(_dir_size_bytes(config.RAW_DIR)),
            "readable": _fmt_gb(_dir_size_bytes(config.READABLE_DIR)),
            "clean": _fmt_gb(_dir_size_bytes(config.CLEAN_DIR)),
            "processed": _fmt_gb(_dir_size_bytes(config.PROCESSED_DIR)),
        },
    }
    return stats


def write_summary_readme() -> None:
    config.ensure_output_dirs()
    stats = final_dataset_stats()
    lines = [
        "# Mortgage Dataset Pipeline - Final Summary\n",
        f"Quarters processed: {stats['quarters_processed']}",
        f"Total loans (loan-level modeling table): {stats['total_loans']:,}",
        f"Total loans flagged default: {stats['total_defaults']:,}",
        f"Overall default rate: {stats['overall_default_rate']:.4%}" if stats["overall_default_rate"] is not None else "Overall default rate: n/a",
        f"Total monthly performance panel rows: {stats['total_monthly_panel_rows']:,}\n",
        "## Disk usage by stage\n",
    ]
    for stage, size in stats["disk_usage"].items():
        lines.append(f"- `{stage}`: {size}")
    lines.append("\n## Per-quarter breakdown\n")
    lines.append("| Quarter | Loans | Defaults | Monthly panel rows |")
    lines.append("|---|---|---|---|")
    for row in stats["per_quarter"]:
        lines.append(f"| {row['quarter']} | {row['loans']:,} | {row['defaults']:,} | {row['monthly_panel_rows']:,} |")

    out_path = config.DOCS_DIR / "final_summary.md"
    out_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"Wrote final summary to {out_path}")

    json_path = config.DOCS_DIR / "final_summary.json"
    json_path.write_text(json.dumps(stats, indent=2), encoding="utf-8")
    print(f"Wrote final summary JSON to {json_path}")


if __name__ == "__main__":
    write_summary_readme()
