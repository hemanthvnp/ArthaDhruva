"""Stage 4: join origination to performance history, derive a loan-level
default/delinquency target, engineer risk features, and write the final
partitioned modeling dataset plus the cleaned monthly performance panel.

Each quarterly vintage is self-contained in this dataset (a quarter's
performance file holds the full history for loans originated that quarter),
so the join happens per origination quarter rather than across quarters.

Performance tables can be tens of millions of rows/quarter, so the per-loan
feature aggregation is done as a bounded two-level map-reduce over the
cleaned performance chunks (pipeline.clean already split these into
part-*.parquet files): each chunk is aggregated to per-loan partial results
(cheap -- a chunk is small), then the small partial-result frames are
concatenated and aggregated again to merge across chunks. This avoids a
single full-file group_by, which was observed to use 6.78GB+ resident on a
16GB machine for this dataset's largest quarters.
"""

from __future__ import annotations

import argparse
import json

import polars as pl

from pipeline import chunked, config

SUMMARY_PATH = config.DOCS_DIR / "preprocessing_summary.jsonl"

# Zero-balance codes indicating the loan terminated adversely (foreclosure,
# short sale, REO disposition, note sale, or other resolution of a
# delinquency) rather than a clean prepayment/maturity payoff (code 01).
ADVERSE_ZERO_BALANCE_CODES = ["02", "03", "09", "15", "16", "96", "97", "98"]
DELINQUENT_90DPD_THRESHOLD = 3  # current_loan_delinquency_status >= 3 => 90+ days delinquent


def _partial_aggregate(chunk: pl.DataFrame) -> pl.DataFrame:
    numeric_status = pl.col("current_loan_delinquency_status").cast(pl.Int32, strict=False)
    is_severe_status_code = pl.col("current_loan_delinquency_status").is_in(["RA"])
    by_time = lambda col: pl.col(col).sort_by("monthly_reporting_period")

    return chunk.group_by("loan_sequence_number").agg(
        n_months_reported=pl.len(),
        worst_delinquency_status=numeric_status.max(),
        ever_severe_status_code=is_severe_status_code.any(),
        ever_90dpd=(numeric_status >= DELINQUENT_90DPD_THRESHOLD).any(),
        terminal_zero_balance_code=by_time("zero_balance_code").drop_nulls().last(),
        terminal_time=pl.col("monthly_reporting_period").max(),
        first_actual_upb=by_time("current_actual_upb").drop_nulls().first(),
        first_time=pl.col("monthly_reporting_period").min(),
        last_actual_upb=by_time("current_actual_upb").drop_nulls().last(),
        current_loan_age=pl.col("loan_age").max(),
        most_recent_reporting_period=pl.col("monthly_reporting_period").max(),
    )


def _merge_partials(partials: pl.DataFrame) -> pl.DataFrame:
    by_terminal_time = lambda col: pl.col(col).sort_by("terminal_time")
    by_first_time = lambda col: pl.col(col).sort_by("first_time")

    merged = partials.group_by("loan_sequence_number").agg(
        n_months_reported=pl.col("n_months_reported").sum(),
        worst_delinquency_status=pl.col("worst_delinquency_status").max(),
        ever_severe_status_code=pl.col("ever_severe_status_code").any(),
        ever_90dpd=pl.col("ever_90dpd").any(),
        terminal_zero_balance_code=by_terminal_time("terminal_zero_balance_code").drop_nulls().last(),
        first_actual_upb=by_first_time("first_actual_upb").drop_nulls().first(),
        last_actual_upb=by_terminal_time("last_actual_upb").drop_nulls().last(),
        current_loan_age=pl.col("current_loan_age").max(),
        most_recent_reporting_period=pl.col("most_recent_reporting_period").max(),
    )
    return merged.with_columns(
        upb_trend=(pl.col("last_actual_upb") - pl.col("first_actual_upb")),
        has_adverse_termination=pl.col("terminal_zero_balance_code").is_in(ADVERSE_ZERO_BALANCE_CODES),
    ).with_columns(
        default_target=pl.when(
            pl.col("ever_90dpd").fill_null(False)
            | pl.col("ever_severe_status_code").fill_null(False)
            | pl.col("has_adverse_termination").fill_null(False)
        )
        .then(1)
        .otherwise(0)
    )


def _loan_level_features(clean_performance_dir) -> pl.DataFrame:
    partial_frames = [
        _partial_aggregate(pl.scan_parquet(p).collect())
        for p in sorted(clean_performance_dir.glob("part-*.parquet"))
    ]
    partials = pl.concat(partial_frames) if partial_frames else pl.DataFrame()
    if partials.is_empty():
        return partials
    return _merge_partials(partials)


def preprocess_quarter(q: config.Quarter, force: bool = False) -> bool:
    perf_ready = q.clean_performance_dir.exists() and any(q.clean_performance_dir.glob("part-*.parquet"))
    if not (q.clean_origination_path.exists() and perf_ready):
        print(f"[skip] {q.label}: clean files not found, run cleaning first")
        return False

    partition_dir_loan = config.LOAN_LEVEL_DIR / f"orig_year={q.year}" / f"orig_quarter={q.quarter}"
    partition_dir_panel = config.MONTHLY_PANEL_DIR / f"orig_year={q.year}" / f"orig_quarter={q.quarter}" / q.label
    loan_level_out = partition_dir_loan / f"{q.label}.parquet"

    panel_done = partition_dir_panel.exists() and any(partition_dir_panel.glob("part-*.parquet"))
    if loan_level_out.exists() and panel_done and not force:
        print(f"[skip] {q.label}: already preprocessed")
        return False

    partition_dir_loan.mkdir(parents=True, exist_ok=True)

    orig_df = pl.read_parquet(q.clean_origination_path)
    features_df = _loan_level_features(q.clean_performance_dir)

    loan_level_df = orig_df.join(features_df, on="loan_sequence_number", how="left").with_columns(
        pl.lit(q.year).alias("orig_year"),
        pl.lit(q.quarter).alias("orig_quarter"),
    )
    loan_level_df.write_parquet(loan_level_out)

    def _panel_chunks():
        for p in sorted(q.clean_performance_dir.glob("part-*.parquet")):
            yield pl.read_parquet(p).with_columns(
                pl.lit(q.year).alias("orig_year"),
                pl.lit(q.quarter).alias("orig_quarter"),
            )

    chunked.write_parts(_panel_chunks(), partition_dir_panel)

    total_loans = loan_level_df.height
    unmatched = loan_level_df["n_months_reported"].is_null().sum()
    default_count = (loan_level_df["default_target"] == 1).sum()
    default_rate = default_count / (total_loans - unmatched) if (total_loans - unmatched) > 0 else None

    summary = {
        "quarter": q.label,
        "total_loans": total_loans,
        "loans_without_performance_history": int(unmatched),
        "loans_with_default_target": int(default_count),
        "default_rate_among_matched": default_rate,
    }
    _append_summary(summary)
    print(f"[ok]   {q.label}: {summary}")
    return True


def _append_summary(entry: dict) -> None:
    config.ensure_output_dirs()
    existing = []
    if SUMMARY_PATH.exists():
        with open(SUMMARY_PATH, "r", encoding="utf-8") as f:
            existing = [json.loads(line) for line in f if line.strip()]
    existing = [e for e in existing if e["quarter"] != entry["quarter"]]
    existing.append(entry)
    with open(SUMMARY_PATH, "w", encoding="utf-8") as f:
        for e in existing:
            f.write(json.dumps(e) + "\n")


def preprocess_all(quarters: list[config.Quarter], force: bool = False) -> None:
    config.ensure_output_dirs()
    for q in quarters:
        preprocess_quarter(q, force=force)


def main() -> None:
    parser = argparse.ArgumentParser(description="Join, label, and feature-engineer the modeling dataset.")
    parser.add_argument("--quarter", action="append", help="Specific quarter label (e.g. 2025Q3); repeatable")
    parser.add_argument("--force", action="store_true", help="Re-run even if processed files already exist")
    args = parser.parse_args()

    if args.quarter:
        quarters = [config.parse_quarter(q) for q in args.quarter]
    else:
        quarters = config.all_quarters()

    preprocess_all(quarters, force=args.force)


if __name__ == "__main__":
    main()
