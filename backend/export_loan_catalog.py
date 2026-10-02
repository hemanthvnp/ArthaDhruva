"""Exports the demo loan catalog: 400 real, currently active loans shaped exactly like the LoanFeatures
request body (camelCase, loanId and originationMonth included), so an analyst can pick a real loan
instead of typing 16 feature values, and a new tenant has a portfolio to explore before uploading its own.

The loans come from the survival sample (build_survival_dataset.py), restricted to loans still on the
book at the data cut-off: a loan that already prepaid or defaulted has no future to project. Each carries
its origination month, which the models need for the rate spread at origination and for re-marking the
property's value. The sample is spread evenly across the credit-score ranking so the catalog shows the
whole risk spectrum, not whatever the first rows happened to be.

Writes risk-engine/src/main/resources/loan_catalog.json. Run from backend/ after build_survival_dataset.py.
"""
from __future__ import annotations

import json

import polars as pl

from credit_common import CATEGORICAL, HARD_BOUNDS, NUMERIC, RESOURCES, SURVIVAL

CATALOG_SIZE = 400
SEED = 20260930


def to_loan_features(row: dict) -> dict:
    return {
        "loanId": row["loan_sequence_number"],
        "creditScore": int(row["credit_score"]),
        "originalDti": float(row["original_dti"]),
        "originalUpb": float(row["original_upb"]),
        "originalCltv": float(row["original_cltv"]),
        "originalLtv": float(row["original_ltv"]),
        "originalInterestRate": round(float(row["original_interest_rate"]), 3),
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
        "originationMonth": f"{row['orig_month'] // 12:04d}-{row['orig_month'] % 12 + 1:02d}",
    }


def main() -> None:
    loans = pl.read_parquet(str(SURVIVAL / "loans" / "*.parquet"))
    # Panels of different vintages end at different data releases, so "still reporting" is judged
    # against the end of the loan's own panel.
    data_end = loans.group_by(["orig_year", "orig_quarter"]).agg(
        (pl.col("orig_month") + pl.col("last_age")).max().alias("data_end"))
    loans = loans.join(data_end, on=["orig_year", "orig_quarter"])
    in_domain = pl.all_horizontal([pl.col(f).is_between(lo, hi) for f, (lo, hi) in HARD_BOUNDS.items()])
    active = (
        loans.filter((pl.col("event") == "censored") & (pl.col("orig_month") + pl.col("last_age") >= pl.col("data_end") - 1))
        .drop_nulls(NUMERIC + CATEGORICAL)
        .filter(in_domain)
        .filter(pl.col("original_interest_rate") > 0)
    )
    print(f"{active.height:,} active loans at the data cut-off")
    # Evenly spaced along the credit-score ranking (ties broken at random, reproducibly).
    ranked = active.sample(fraction=1.0, shuffle=True, seed=SEED).sort("credit_score", maintain_order=True)
    step = ranked.height / CATALOG_SIZE
    sample = ranked[[int(i * step + step / 2) for i in range(CATALOG_SIZE)]]

    catalog = [to_loan_features(row) for row in sample.to_dicts()]
    out_path = RESOURCES / "loan_catalog.json"
    out_path.write_text(json.dumps(catalog, indent=2) + "\n", encoding="utf-8")
    vintages = sorted({loan["originationMonth"][:4] for loan in catalog})
    print(f"wrote {out_path}: {len(catalog)} loans, {len({l['propertyState'] for l in catalog})} states, "
          f"vintages {vintages[0]}-{vintages[-1]}, credit scores "
          f"{min(l['creditScore'] for l in catalog)}-{max(l['creditScore'] for l in catalog)}")


if __name__ == "__main__":
    main()
