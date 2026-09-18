"""Exports a browsable catalog of real loans' actual first-12-months performance (matching
TrajectoryRequest) so an analyst can look up and score one by its real loan_sequence_number
instead of hand-typing a month-by-month delinquency/UPB/modification history -- same purpose as
export_loan_catalog.py, for the LSTM trajectory model instead of the PD model.

Scoped to a single origination quarter (2020Q1) for both the loan sample and its monthly history,
so this stays a bounded, single-partition read instead of scanning the full ~474M-row panel.
"""
import json
import os

import polars as pl

ORIG_YEAR = 2020
ORIG_QUARTER = "Q1"
NUM_LOANS = 120
MAX_MONTHS = 12


def main() -> None:
    loan_level_path = f"../data/processed/loan_level/orig_year={ORIG_YEAR}/orig_quarter={ORIG_QUARTER}/*.parquet"
    panel_path = f"../data/processed/monthly_panel/orig_year={ORIG_YEAR}/orig_quarter={ORIG_QUARTER}/{ORIG_YEAR}{ORIG_QUARTER}/*.parquet"

    print(f"Sampling {NUM_LOANS} loans from {ORIG_YEAR}{ORIG_QUARTER}...")
    loans = (
        pl.scan_parquet(loan_level_path, extra_columns="ignore")
        .select(["loan_sequence_number", "original_upb", "credit_score"])
        .drop_nulls()
        .collect()
    )
    # Mixed risk (by credit score), same spirit as the other catalogs' sampling.
    loans = loans.with_columns(pl.col("credit_score").rank(method="ordinal").alias("_rank"))
    step = max(1, loans.height // NUM_LOANS)
    sample_loans = loans.sort("_rank").gather_every(step).head(NUM_LOANS).drop("_rank")
    loan_ids = sample_loans["loan_sequence_number"].to_list()
    upb_by_loan = dict(zip(sample_loans["loan_sequence_number"], sample_loans["original_upb"]))

    print(f"Reading actual monthly history for those {len(loan_ids)} loans from {ORIG_YEAR}{ORIG_QUARTER}'s panel...")
    panel = (
        pl.scan_parquet(panel_path, extra_columns="ignore")
        .filter(pl.col("loan_sequence_number").is_in(loan_ids))
        .filter(pl.col("current_actual_upb") > 0)  # excludes paid-off/matured/degenerate months
        .select(["loan_sequence_number", "loan_age", "current_actual_upb",
                  "current_loan_delinquency_status", "modification_flag"])
        .drop_nulls(subset=["current_actual_upb", "current_loan_delinquency_status"])
        .sort(["loan_sequence_number", "loan_age"])
        .collect()
    )

    MIN_MONTHS = 3
    catalog = []
    for loan_id, group in panel.group_by("loan_sequence_number", maintain_order=True):
        loan_id = loan_id[0]
        months = group.head(MAX_MONTHS)
        if months.height < MIN_MONTHS:
            continue
        record_list = [
            {
                "currentLoanDelinquencyStatus": row["current_loan_delinquency_status"],
                "currentActualUpb": float(row["current_actual_upb"]),
                "modificationFlag": row["modification_flag"] or "N",
            }
            for row in months.to_dicts()
        ]
        catalog.append({
            "label": f"{loan_id} ({months.height} months observed)",
            "request": {
                "originalUpb": float(upb_by_loan[loan_id]),
                "months": record_list,
            },
        })

    resources_dir = "risk-engine/src/main/resources"
    os.makedirs(resources_dir, exist_ok=True)
    out_path = f"{resources_dir}/trajectory_catalog.json"
    with open(out_path, "w") as f:
        json.dump(catalog, f, indent=2)

    print(f"Saved {out_path}: {len(catalog)} loans with real observed trajectories")


if __name__ == "__main__":
    main()
