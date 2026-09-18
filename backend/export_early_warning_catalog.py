"""Exports a browsable catalog of real currently-current-loan snapshots (matching
EarlyWarningFeatures) so an analyst can look up and score one by an actual loan/month label
instead of hand-typing 31 feature values into a form -- same purpose as export_loan_catalog.py,
for the early-warning delinquency model instead of the PD model.

Sampled from the natural-rate calibration holdout (early_warning_calibration_holdout_v3.parquet)
rather than the training set, specifically because it's genuinely representative (not
downsampled) and -- unlike the training set -- was built with the real, later-observed outcome
attached. That outcome (did this loan actually go 30+ days late in the next 3 months?) is
included in the catalog purely for the demo/analyst-facing value of showing a real prediction next
to its real answer; the early-warning model itself never sees it.

Writes risk-engine/src/main/resources/early_warning_catalog.json: a JSON array of
{label, actuallyWentDelinquent, features} objects, where `features` is shaped exactly like an
/early-warning-score request body.
"""
import json
import os

import polars as pl

NUM_NEGATIVE = 150
NUM_POSITIVE = 150

NUMERIC_FEATURES = [
    "credit_score", "original_dti", "original_upb", "original_cltv", "original_ltv",
    "original_interest_rate", "original_loan_term", "number_of_borrowers", "number_of_units", "mi_percent",
    "loan_age", "eltv", "current_interest_rate", "upb_paydown_ratio", "rate_lock_severity",
    "eltv_change_3m", "upb_paydown_change_3m", "rate_lock_severity_change_3m",
    "eltv_change_6m", "upb_paydown_change_6m", "rate_lock_severity_change_6m",
]
CATEGORICAL_FEATURES = [
    "occupancy_status", "property_type", "loan_purpose", "channel",
    "first_time_homebuyer_flag", "property_state", "hmm_regime",
]
BOOLEAN_FEATURES = ["prior_assistance", "prior_modification", "prior_disaster", "upb_stalled"]
ALL_COLUMNS = (["loan_sequence_number", "monthly_reporting_period", "target"]
               + NUMERIC_FEATURES + CATEGORICAL_FEATURES + BOOLEAN_FEATURES)


def to_features(row: dict) -> dict:
    def num(name):
        return float(row[name]) if row[name] is not None else 0.0

    return {
        "creditScore": int(row["credit_score"]),
        "originalDti": num("original_dti"),
        "originalUpb": num("original_upb"),
        "originalCltv": num("original_cltv"),
        "originalLtv": num("original_ltv"),
        "originalInterestRate": num("original_interest_rate"),
        "originalLoanTerm": int(row["original_loan_term"]),
        "numberOfBorrowers": int(row["number_of_borrowers"]),
        "numberOfUnits": int(row["number_of_units"]),
        "miPercent": num("mi_percent"),
        "loanAge": int(row["loan_age"]),
        "eltv": num("eltv"),
        "currentInterestRate": num("current_interest_rate"),
        "upbPaydownRatio": num("upb_paydown_ratio"),
        "rateLockSeverity": num("rate_lock_severity"),
        "eltvChange3m": num("eltv_change_3m"),
        "upbPaydownChange3m": num("upb_paydown_change_3m"),
        "rateLockSeverityChange3m": num("rate_lock_severity_change_3m"),
        "eltvChange6m": num("eltv_change_6m"),
        "upbPaydownChange6m": num("upb_paydown_change_6m"),
        "rateLockSeverityChange6m": num("rate_lock_severity_change_6m"),
        "occupancyStatus": row["occupancy_status"],
        "propertyType": row["property_type"],
        "loanPurpose": row["loan_purpose"],
        "channel": row["channel"],
        "firstTimeHomebuyerFlag": row["first_time_homebuyer_flag"],
        "propertyState": row["property_state"],
        "hmmRegime": row["hmm_regime"],
        "priorAssistance": bool(row["prior_assistance"]) if row["prior_assistance"] is not None else False,
        "priorModification": bool(row["prior_modification"]) if row["prior_modification"] is not None else False,
        "priorDisaster": bool(row["prior_disaster"]) if row["prior_disaster"] is not None else False,
        "upbStalled": bool(row["upb_stalled"]) if row["upb_stalled"] is not None else False,
    }


def to_entry(row: dict) -> dict:
    label = f"{row['loan_sequence_number']} @ {row['monthly_reporting_period']}"
    return {
        "label": label,
        "actuallyWentDelinquent": bool(row["target"]),
        "features": to_features(row),
    }


def main() -> None:
    print("Loading natural-rate calibration holdout...")
    df = pl.read_parquet("../data/processed/early_warning_calibration_holdout_v3.parquet").select(ALL_COLUMNS)

    # eltv_change_6m etc. can be null for a loan's first eligible month (no 6-month comparison
    # point yet) -- fine for training (handled via fillna there) but a confusing "null" to show
    # an analyst in a picker label/result, so the catalog only draws from rows with every field
    # populated.
    df = df.drop_nulls()

    negatives = df.filter(~pl.col("target")).sample(n=min(NUM_NEGATIVE, df.filter(~pl.col("target")).height), seed=42)
    positives = df.filter(pl.col("target")).sample(n=min(NUM_POSITIVE, df.filter(pl.col("target")).height), seed=42)
    sample = pl.concat([negatives, positives])

    catalog = [to_entry(row) for row in sample.to_dicts()]

    resources_dir = "risk-engine/src/main/resources"
    os.makedirs(resources_dir, exist_ok=True)
    out_path = f"{resources_dir}/early_warning_catalog.json"
    with open(out_path, "w") as f:
        json.dump(catalog, f, indent=2, default=str)

    n_positive = sum(1 for e in catalog if e["actuallyWentDelinquent"])
    print(f"Saved {out_path}: {len(catalog)} snapshots ({n_positive} that actually went delinquent, "
          f"{len(catalog) - n_positive} that didn't)")


if __name__ == "__main__":
    main()
