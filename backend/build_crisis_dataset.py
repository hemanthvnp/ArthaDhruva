"""Builds the 2006-2008 vintages' loan-month rows: the housing downturn the 2017-2026 sample does not contain.

The same sample and the same first-event rule as build_survival_dataset.py (40,000 fixed-rate loans a quarter;
default = first month 90+ days past due, REO acquisition or an adverse zero-balance code; prepay = code 01;
no-event months kept at 10% with weight 10), read straight from the Freddie Mac archives
historical_data_<year>/historical_data_<year>Q<n>.zip.

Written to data/processed/survival_crisis/, not through the pipeline into loan_level / monthly_panel: the PD
model, the regime series and the loan catalogue glob those and are fitted on 2017-2026 only.
export_survival_model.py reads this directory and uses these loans' months up to 2016-12 (they stop before the
2017-2026 sample's months start).

Each archive's performance file (about 2.4 GB) is extracted to a temporary directory outside the repository,
filtered to the sampled loans and deleted.

Run from backend/:  python build_crisis_dataset.py [2006Q1 ...]      (resumable)
"""
from __future__ import annotations

import sys
import tempfile
import time
import zipfile
from pathlib import Path

import polars as pl

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "data" / "processed" / "survival_crisis"
QUARTERS = [f"{year}Q{q}" for year in (2006, 2007, 2008) for q in (1, 2, 3, 4)]

SAMPLE_PER_QUARTER = 40_000
CONTINUE_KEEP_PER_MILLE = 100
SEED = 20260930
ADVERSE_ZB = ["02", "03", "09", "15", "16", "96", "97", "98"]
PREPAID_ZB = ["01"]

NUMERIC = ["credit_score", "original_dti", "original_upb", "original_cltv", "original_ltv",
           "original_interest_rate", "original_loan_term", "number_of_borrowers", "number_of_units", "mi_percent"]
CATEGORICAL = ["occupancy_status", "property_type", "loan_purpose", "channel", "first_time_homebuyer_flag", "property_state"]
# position (1-based) in the 31-column origination layout, and the raw value that means "not available"
ORIGINATION = {"credit_score": (1, "9999"), "first_time_homebuyer_flag": (3, "9"), "mi_percent": (6, "999"),
               "number_of_units": (7, "99"), "occupancy_status": (8, "9"), "original_cltv": (9, "999"),
               "original_dti": (10, "999"), "original_upb": (11, None), "original_ltv": (12, "999"),
               "original_interest_rate": (13, None), "channel": (14, "9"), "amortization_type": (16, None),
               "property_state": (17, None), "property_type": (18, "99"), "loan_sequence_number": (20, None),
               "loan_purpose": (21, "9"), "original_loan_term": (22, None), "number_of_borrowers": (23, "99")}
# position in the 35-column performance layout
PERFORMANCE = {"loan_sequence_number": 1, "monthly_reporting_period": 2, "current_loan_delinquency_status": 4,
               "loan_age": 5, "zero_balance_code": 9}
INTEGER = {"credit_score", "number_of_units", "original_loan_term", "number_of_borrowers"}


def read_origination(archive: zipfile.ZipFile, label: str) -> pl.DataFrame:
    with archive.open(f"orig_{label}.txt") as f:
        raw = pl.read_csv(f.read(), separator="|", has_header=False, infer_schema_length=0, quote_char=None)
    if raw.width != 31:
        raise SystemExit(f"{label}: origination file has {raw.width} columns, expected the 31-column layout")
    exprs = []
    for name, (position, sentinel) in ORIGINATION.items():
        col = pl.col(f"column_{position}")
        if sentinel is not None:
            col = pl.when(col == sentinel).then(None).otherwise(col)
        if name in NUMERIC:
            col = col.cast(pl.Int64 if name in INTEGER else pl.Float64, strict=False)
        exprs.append(col.alias(name))
    return raw.select(exprs).unique("loan_sequence_number", keep="first", maintain_order=True)


def read_performance(archive: zipfile.ZipFile, label: str, ids: pl.Series, tmp: Path) -> pl.DataFrame:
    path = Path(archive.extract(f"perf_{label}.txt", tmp))
    try:
        with open(path, "r", encoding="utf-8") as f:
            width = f.readline().count("|") + 1
        if width != 35:
            raise SystemExit(f"{label}: performance file has {width} columns, expected the 35-column layout")
        panel = (pl.scan_csv(path, separator="|", has_header=False, infer_schema_length=0, quote_char=None)
                 .select([pl.col(f"column_{p}").alias(n) for n, p in PERFORMANCE.items()])
                 .filter(pl.col("loan_sequence_number").is_in(ids.implode()))
                 .collect(engine="streaming"))
    finally:
        path.unlink(missing_ok=True)
    period = pl.col("monthly_reporting_period").cast(pl.Int32)
    return panel.with_columns(month=(period // 100) * 12 + period % 100 - 1, loan_age=pl.col("loan_age").cast(pl.Int64),
                              zero_balance_code=pl.when(pl.col("zero_balance_code") == "").then(None)
                              .otherwise(pl.col("zero_balance_code").str.zfill(2))).drop("monthly_reporting_period")


def process(label: str, tmp: Path) -> None:
    rows_out, loans_out = OUT / "rows" / f"{label}.parquet", OUT / "loans" / f"{label}.parquet"
    if rows_out.exists() and loans_out.exists():
        print(f"[skip] {label}", flush=True)
        return
    started = time.time()
    year, qnum = int(label[:4]), int(label[5])
    with zipfile.ZipFile(ROOT / f"historical_data_{year}" / f"historical_data_{label}.zip") as archive:
        loans = read_origination(archive, label)
        loans = loans.filter(pl.col("amortization_type") == "FRM").select(["loan_sequence_number", *NUMERIC, *CATEGORICAL])
        sampled = loans.sample(n=min(SAMPLE_PER_QUARTER, loans.height), seed=SEED + year * 10 + qnum)
        panel = read_performance(archive, label, sampled["loan_sequence_number"], tmp)

    # from here on: build_survival_dataset.process_quarter, unchanged
    status = pl.col("current_loan_delinquency_status")
    status_num = status.cast(pl.Int32, strict=False)
    zb = pl.col("zero_balance_code")
    panel = panel.with_columns(
        is_default=((status_num >= 3) | (status == "RA") | zb.is_in(ADVERSE_ZB)).fill_null(False),
        is_prepay=zb.is_in(PREPAID_ZB).fill_null(False),
        is_other_exit=(zb.is_not_null() & ~zb.is_in(ADVERSE_ZB + PREPAID_ZB)).fill_null(False),
    )
    summary = panel.group_by("loan_sequence_number").agg(
        first_age=pl.col("loan_age").min(), last_age=pl.col("loan_age").max(),
        default_age=pl.col("loan_age").filter(pl.col("is_default")).min(),
        prepay_age=pl.col("loan_age").filter(pl.col("is_prepay")).min(),
        other_exit_age=pl.col("loan_age").filter(pl.col("is_other_exit")).min(),
        orig_month=(pl.col("month") - pl.col("loan_age").cast(pl.Int32)).min(),
    )
    big = 10_000
    d, p_, o = pl.col("default_age").fill_null(big), pl.col("prepay_age").fill_null(big), pl.col("other_exit_age").fill_null(big)
    summary = summary.with_columns(
        event=pl.when((d < big) & (d <= p_) & (d <= o)).then(pl.lit("default"))
        .when((p_ < big) & (p_ < d) & (p_ <= o)).then(pl.lit("prepay")).otherwise(pl.lit("censored")),
    ).with_columns(
        exit_age=pl.when(pl.col("event") == "default").then(pl.col("default_age"))
        .when(pl.col("event") == "prepay").then(pl.col("prepay_age"))
        .otherwise(pl.min_horizontal(pl.col("other_exit_age").fill_null(big), pl.col("last_age"))),
    )
    rows = (
        panel.select(["loan_sequence_number", "loan_age", "month"])
        .join(summary.select(["loan_sequence_number", "event", "exit_age"]), on="loan_sequence_number")
        .filter(pl.col("loan_age") <= pl.col("exit_age"))
        .with_columns(
            label=pl.when((pl.col("loan_age") == pl.col("exit_age")) & (pl.col("event") == "default")).then(1)
            .when((pl.col("loan_age") == pl.col("exit_age")) & (pl.col("event") == "prepay")).then(2)
            .otherwise(0).cast(pl.Int8),
        )
        .unique(subset=["loan_sequence_number", "loan_age"], keep="first")
    )
    keep_bucket = pl.struct(["loan_sequence_number", "loan_age"]).hash(seed=SEED) % 1000
    rows = rows.filter((pl.col("label") != 0) | (keep_bucket < CONTINUE_KEEP_PER_MILLE)).with_columns(
        weight=pl.when(pl.col("label") == 0).then(1000.0 / CONTINUE_KEEP_PER_MILLE).otherwise(1.0).cast(pl.Float32),
    ).drop(["event", "exit_age"])
    rows = rows.join(sampled, on="loan_sequence_number").with_columns(
        month=pl.col("month").cast(pl.Int32), orig_year=pl.lit(year, pl.Int32), orig_quarter=pl.lit(f"Q{qnum}"))
    loans_summary = sampled.join(summary, on="loan_sequence_number", how="inner").with_columns(
        orig_year=pl.lit(year, pl.Int32), orig_quarter=pl.lit(f"Q{qnum}"))

    (OUT / "rows").mkdir(parents=True, exist_ok=True)
    (OUT / "loans").mkdir(parents=True, exist_ok=True)
    rows.write_parquet(rows_out)
    loans_summary.write_parquet(loans_out)
    counts = loans_summary["event"].value_counts().sort("event")
    print(f"[ok]   {label}: {loans.height} fixed-rate loans, {loans_summary.height} sampled, {rows.height} rows, "
          f"{dict(zip(counts['event'].to_list(), counts['count'].to_list()))}, {time.time() - started:.1f}s", flush=True)


def main() -> None:
    selected = [q for q in QUARTERS if q in sys.argv[1:]] if len(sys.argv) > 1 else QUARTERS
    with tempfile.TemporaryDirectory(prefix="crisis_perf_") as tmp:
        for label in selected:
            process(label, Path(tmp))


if __name__ == "__main__":
    main()
