"""Builds the competing-risks (default vs. prepayment) training data for the survival model.

For a stratified random sample of loans per origination quarter, walks each loan's full monthly
performance history and records the first event:
  * default  -- first month 90+ days past due, REO acquisition ("RA"), or an adverse zero-balance
                termination (short sale, foreclosure, REO, note sale, ...)
  * prepay   -- clean zero-balance payoff (code 01)
  * censored -- still active at the data cut-off, or removed for a non-credit reason (e.g. repurchase)

Unlike a fixed "observed at least N months" filter, right-censoring is handled by the survival
framework itself, so early defaults and recent vintages both stay in the data.

Outputs (per origination quarter, resumable):
  ../data/processed/survival/rows/<quarter>.parquet   loan-month rows up to and including the event
  ../data/processed/survival/loans/<quarter>.parquet  one row per sampled loan: features + outcome

Loan-month rows with no event are subsampled (CONTINUE_KEEP) and carry a compensating weight, the
standard case-control correction for rare-event hazard models: estimates stay unbiased while the
training set shrinks about eightfold.
"""
from __future__ import annotations

import glob
import sys
import time
from pathlib import Path

import polars as pl

DATA = Path("../data/processed")
OUT = DATA / "survival"
EXTERNAL = Path("../data/external")

SAMPLE_PER_QUARTER = 40_000
CONTINUE_KEEP_PER_MILLE = 100  # keep 10% of no-event loan-months
SEED = 20260930

ADVERSE_ZB = ["02", "03", "09", "15", "16", "96", "97", "98"]
PREPAID_ZB = ["01"]

NUMERIC = ["credit_score", "original_dti", "original_upb", "original_cltv", "original_ltv",
           "original_interest_rate", "original_loan_term", "number_of_borrowers", "number_of_units", "mi_percent"]
CATEGORICAL = ["occupancy_status", "property_type", "loan_purpose", "channel",
               "first_time_homebuyer_flag", "property_state"]
PANEL_COLS = ["loan_sequence_number", "monthly_reporting_period", "current_loan_delinquency_status",
              "loan_age", "zero_balance_code"]


def month_idx(col: str) -> pl.Expr:
    return pl.col(col).dt.year().cast(pl.Int32) * 12 + pl.col(col).dt.month().cast(pl.Int32) - 1


def quarters() -> list[tuple[int, str]]:
    found = []
    for path in sorted(glob.glob(str(DATA / "loan_level" / "orig_year=*" / "orig_quarter=*" / "*.parquet"))):
        p = Path(path)
        year = int(p.parent.parent.name.split("=")[1])
        q = p.parent.name.split("=")[1]
        found.append((year, q))
    return found


def process_quarter(year: int, q: str) -> None:
    label = f"{year}{q}"
    rows_out = OUT / "rows" / f"{label}.parquet"
    loans_out = OUT / "loans" / f"{label}.parquet"
    if rows_out.exists() and loans_out.exists():
        print(f"[skip] {label}", flush=True)
        return
    started = time.time()

    loans = (
        pl.scan_parquet(DATA / "loan_level" / f"orig_year={year}" / f"orig_quarter={q}" / f"{label}.parquet")
        .select(["loan_sequence_number", *NUMERIC, *CATEGORICAL])
        .collect()
    )
    qnum = int(q[1])
    sampled = loans.sample(n=min(SAMPLE_PER_QUARTER, loans.height), seed=SEED + year * 10 + qnum)
    ids = sampled["loan_sequence_number"]

    parts = sorted(glob.glob(str(DATA / "monthly_panel" / f"orig_year={year}" / f"orig_quarter={q}" / label / "part-*.parquet")))
    if not parts:
        print(f"[warn] {label}: no performance panel, skipped", flush=True)
        return
    panel = pl.concat([
        pl.scan_parquet(p).select(PANEL_COLS).filter(pl.col("loan_sequence_number").is_in(ids.implode())).collect()
        for p in parts
    ])

    status = pl.col("current_loan_delinquency_status")
    status_num = status.cast(pl.Int32, strict=False)
    zb = pl.col("zero_balance_code")
    panel = panel.with_columns(
        month=month_idx("monthly_reporting_period"),
        is_default=((status_num >= 3) | (status == "RA") | zb.is_in(ADVERSE_ZB)).fill_null(False),
        is_prepay=zb.is_in(PREPAID_ZB).fill_null(False),
        is_other_exit=(zb.is_not_null() & ~zb.is_in(ADVERSE_ZB + PREPAID_ZB)).fill_null(False),
    )

    summary = panel.group_by("loan_sequence_number").agg(
        first_age=pl.col("loan_age").min(),
        last_age=pl.col("loan_age").max(),
        default_age=pl.col("loan_age").filter(pl.col("is_default")).min(),
        prepay_age=pl.col("loan_age").filter(pl.col("is_prepay")).min(),
        other_exit_age=pl.col("loan_age").filter(pl.col("is_other_exit")).min(),
        orig_month=(pl.col("month") - pl.col("loan_age").cast(pl.Int32)).min(),
    )
    big = 10_000
    d = pl.col("default_age").fill_null(big)
    p_ = pl.col("prepay_age").fill_null(big)
    o = pl.col("other_exit_age").fill_null(big)
    summary = summary.with_columns(
        event=pl.when((d < big) & (d <= p_) & (d <= o)).then(pl.lit("default"))
        .when((p_ < big) & (p_ < d) & (p_ <= o)).then(pl.lit("prepay"))
        .otherwise(pl.lit("censored")),
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
        orig_year=pl.lit(year, pl.Int32), orig_quarter=pl.lit(q),
    )

    loans_summary = sampled.join(summary, on="loan_sequence_number", how="inner").with_columns(
        orig_year=pl.lit(year, pl.Int32), orig_quarter=pl.lit(q),
    )

    (OUT / "rows").mkdir(parents=True, exist_ok=True)
    (OUT / "loans").mkdir(parents=True, exist_ok=True)
    rows.write_parquet(rows_out)
    loans_summary.write_parquet(loans_out)
    counts = loans_summary["event"].value_counts().sort("event")
    print(f"[ok]   {label}: {loans_summary.height} loans, {rows.height} rows, "
          f"{dict(zip(counts['event'].to_list(), counts['count'].to_list()))}, {time.time() - started:.1f}s", flush=True)


def write_macro_series() -> None:
    """Monthly regime (0 calm / 1 stressed) and 30-year mortgage rate, indexed by month (year*12+month-1)."""
    regime = pl.read_parquet(DATA / "hmm_regime_lookup.parquet").with_columns(
        month=month_idx("monthly_reporting_period"),
        stressed=(pl.col("hmm_regime") == "stressed").cast(pl.Int8),
    ).select(["month", "stressed"])
    rate = (
        pl.read_csv(EXTERNAL / "MORTGAGE30US.csv", try_parse_dates=True)
        .rename({"observation_date": "date", "MORTGAGE30US": "rate"})
        .with_columns(month=month_idx("date"))
        .group_by("month").agg(pl.col("rate").mean())
    )
    first, last = 1995 * 12, 2027 * 12  # from the first month of the state macro panel (fetch_macro.py)
    grid = pl.DataFrame({"month": pl.int_range(first, last, eager=True).cast(pl.Int32)})
    macro = (
        grid.join(regime, on="month", how="left")
        .join(rate.with_columns(pl.col("month").cast(pl.Int32)), on="month", how="left")
        .sort("month")
        # Before the regime model's first month: calm. After its last: carry the last known regime.
        .with_columns(pl.col("stressed").forward_fill().fill_null(0), pl.col("rate").forward_fill().backward_fill())
    )
    OUT.mkdir(parents=True, exist_ok=True)
    macro.write_parquet(OUT / "macro.parquet")
    print(f"[ok]   macro series: {macro.height} months, rates through {rate['month'].max()}", flush=True)


def main() -> None:
    write_macro_series()
    selected = quarters()
    if len(sys.argv) > 1:
        selected = [yq for yq in selected if f"{yq[0]}{yq[1]}" in sys.argv[1:]]
    for year, q in selected:
        process_quarter(year, q)


if __name__ == "__main__":
    main()
