"""Exports a browsable catalog of real loans (origination features only, matching LoanFeatures /
the PD model's 16-field input) so an analyst can look up and score a real loan by its actual
loan_sequence_number instead of hand-typing feature values into a form every time.

Same category as export_segment_correlation.py / export_hmm.py: a one-time (or occasionally
re-run) export producing a small artifact risk-engine reads at startup, not something the running
service regenerates itself.

Writes risk-engine/src/main/resources/loan_catalog.json: a JSON array of objects shaped exactly
like the LoanFeatures request body (camelCase, loanId included), so the Java side can both list
these for browsing/search and hand one straight to ModelService.score() with no transformation.
"""
import json
import os

import polars as pl

CATALOG_SIZE = 400

FEATURE_COLUMNS = [
    "loan_sequence_number", "credit_score", "original_dti", "original_upb", "original_cltv",
    "original_ltv", "original_interest_rate", "original_loan_term", "number_of_borrowers",
    "number_of_units", "mi_percent", "occupancy_status", "property_type", "loan_purpose",
    "channel", "first_time_homebuyer_flag", "property_state",
]


def to_loan_features(row: dict) -> dict:
    return {
        "loanId": row["loan_sequence_number"],
        "creditScore": int(row["credit_score"]),
        "originalDti": float(row["original_dti"]),
        "originalUpb": float(row["original_upb"]),
        "originalCltv": float(row["original_cltv"]),
        "originalLtv": float(row["original_ltv"]),
        "originalInterestRate": float(row["original_interest_rate"]),
        "originalLoanTerm": int(row["original_loan_term"]),
        "numberOfBorrowers": int(row["number_of_borrowers"]),
        "numberOfUnits": int(row["number_of_units"]),
        "miPercent": float(row["mi_percent"]),
        "occupancyStatus": row["occupancy_status"],
        "propertyType": row["property_type"],
        "loanPurpose": row["loan_purpose"],
        "channel": row["channel"],
        "firstTimeHomebuyerFlag": row["first_time_homebuyer_flag"],
        "propertyState": row["property_state"],
    }


def main() -> None:
    loan_level_glob = "../data/processed/loan_level/orig_year=*/orig_quarter=*/*.parquet"
    print("Loading candidate loans from data/processed/loan_level...")
    df = (
        pl.scan_parquet(loan_level_glob, extra_columns="ignore")
        .select(FEATURE_COLUMNS)
        .drop_nulls()
        .collect()
    )
    print(f"{df.height:,} candidate loans available")

    # A spread across credit-score deciles rather than a plain random/head sample, so the
    # catalog itself demonstrates real variety (an analyst searching it sees the full risk
    # spectrum, not whatever a handful of top rows happened to look like).
    df = df.with_columns(pl.col("credit_score").rank(method="ordinal").alias("_rank"))
    step = max(1, df.height // CATALOG_SIZE)
    sample = df.sort("_rank").gather_every(step).head(CATALOG_SIZE).drop("_rank")

    catalog = [to_loan_features(row) for row in sample.to_dicts()]

    resources_dir = "risk-engine/src/main/resources"
    os.makedirs(resources_dir, exist_ok=True)
    out_path = f"{resources_dir}/loan_catalog.json"
    with open(out_path, "w") as f:
        json.dump(catalog, f, indent=2)

    states = sorted({loan["propertyState"] for loan in catalog})
    print(f"Saved {out_path}: {len(catalog)} loans across {len(states)} states "
          f"(credit scores {min(l['creditScore'] for l in catalog)}-{max(l['creditScore'] for l in catalog)})")


if __name__ == "__main__":
    main()
