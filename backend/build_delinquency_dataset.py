"""Builds the loan-month transition data for the delinquency-aware (multi-state) model.

The survival dataset (build_survival_dataset.py) records only the first event of each sampled loan. This one
records every month's move between delinquency states, so a loan that is 30 days late can be followed through
cure, roll and default. It reads the same sampled loans (data/processed/survival/loans) and the same monthly
panel, and leaves the survival outputs untouched.

States of a loan-month: 0 current, 1 30 days past due, 2 60 days past due, 3 default, 4 prepaid.
Default and prepayment are absorbing, and default is the existing definition (build_survival_dataset.py: first
month 90+ days past due, REO acquisition, or an adverse zero-balance termination), reused from that module so
the two cannot drift apart. A removal for a non-credit reason ends the loan's rows (censored), as there.

One row per (loan, month t) for which the loan was active and reported in the month before:
  source_state   the state at t-1 (0, 1 or 2)
  label          the state at t (0 to 4)
  loan_age, month   of month t, like the survival rows (the engine reads macro drivers at the month it moves into)
  hist_*         the loan's history up to and including t-1 and nothing later:
    hist_episodes_12m        30+ day episodes that began in months t-12 to t-1
    hist_months_since_cure   months from the latest cure (a late loan back to current) to t-1; 13 = none in 12 months
    hist_max_state_12m       worst state (0 to 2) in months t-12 to t-1
    hist_months_observed     how many of those 12 months the loan has a record for

Rows that stay current (0 -> 0) are the no-event majority: 10% are kept at weight 10, the same case-control
correction as the survival rows. Every other row (any move, and every row from a delinquent state) is kept at
weight 1.

Output (per origination quarter, resumable):
  ../data/processed/delinquency/rows/<quarter>.parquet
Run from backend/ (owner-run: reads the 5.8 GB monthly panel).
"""
from __future__ import annotations

import glob
import sys
import time
from pathlib import Path

import polars as pl

from build_survival_dataset import (ADVERSE_ZB, CATEGORICAL, CONTINUE_KEEP_PER_MILLE, DATA, NUMERIC, PANEL_COLS,
                                    PREPAID_ZB, SEED, month_idx)

OUT = DATA / "delinquency"
CURRENT, D30, D60, DEFAULT, PREPAID = 0, 1, 2, 3, 4
EXIT = -1                       # removal for a non-credit reason: the loan is censored, no row
NO_CURE = 13                    # hist_months_since_cure when there was no cure in the last 12 months
WINDOW = 12
KEY = "loan_sequence_number"


def record_states(panel: pl.DataFrame) -> pl.DataFrame:
    """One state per reported month. `panel` carries PANEL_COLS; the result is sorted by loan and month and has
    `state` (0-4, EXIT, or null when the status is not reported), `month` and `loan_age`."""
    status = pl.col("current_loan_delinquency_status")
    status_num = status.cast(pl.Int32, strict=False)
    zb = pl.col("zero_balance_code")
    is_default = ((status_num >= 3) | (status == "RA") | zb.is_in(ADVERSE_ZB)).fill_null(False)
    is_prepay = zb.is_in(PREPAID_ZB).fill_null(False)
    is_exit = (zb.is_not_null() & ~zb.is_in(ADVERSE_ZB + PREPAID_ZB)).fill_null(False)
    state = (pl.when(is_default).then(pl.lit(DEFAULT))
             .when(is_prepay).then(pl.lit(PREPAID))
             .when(is_exit).then(pl.lit(EXIT))
             .when(status_num.is_in([CURRENT, D30, D60])).then(status_num)
             .otherwise(None))
    return (panel.select([KEY, "loan_age", month_idx("monthly_reporting_period").alias("month"), state.cast(pl.Int8).alias("state")])
            .sort([KEY, "month"]))


def transition_rows(panel: pl.DataFrame) -> pl.DataFrame:
    """Every loan-month transition with its history features, before any subsampling. Pure: depends only on
    `panel`, and a month's features depend only on earlier months (tests/test_delinquency_dataset.py)."""
    s = record_states(panel)
    by_loan = pl.col(KEY)
    prev_state = pl.col("state").shift(1).over(by_loan)
    consecutive = ((pl.col("month") - pl.col("month").shift(1).over(by_loan)) == 1).fill_null(False)
    # a transition is only observed between consecutive reported months from an active state; the row after a gap
    # in the record is not used (it would pair a state with one from several months earlier)
    prev_ok = prev_state.is_in([CURRENT, D30, D60]).fill_null(False) & consecutive
    late = pl.col("state").is_in([D30, D60]).fill_null(False)
    prev_late = prev_state.is_in([D30, D60]).fill_null(False) & consecutive
    # A loan's rows end at its first default, prepayment or removal (absorbing: a loan that cures after a 90+ month
    # does not re-enter), and at the first record whose loan_age does not advance with the calendar (a re-aged
    # loan: its age is no longer the time since origination, so the rest of its record is censored).
    terminal = pl.col("state").is_in([DEFAULT, PREPAID, EXIT]).fill_null(False)
    off_calendar = ((pl.col("loan_age") - pl.col("loan_age").shift(1).over(by_loan))
                    != (pl.col("month") - pl.col("month").shift(1).over(by_loan))).fill_null(False)
    s = s.with_columns(_idx=pl.int_range(pl.len()).over(by_loan), _off=off_calendar.cum_max().over(by_loan),
                       _ended=terminal.cum_max().over(by_loan))
    s = s.with_columns(_ended_before=pl.col("_ended").shift(1).over(by_loan).fill_null(False))
    s = s.with_columns(
        _late=late.cast(pl.Int8),
        _start=(late & ~prev_late).cast(pl.Int8),
        _cure=((pl.col("state") == CURRENT) & prev_late).cast(pl.Int8),
        _act=pl.when(pl.col("state").is_in([CURRENT, D30, D60])).then(pl.col("state")).otherwise(0).cast(pl.Int8),
    )
    s = s.with_columns(
        _last_cure=pl.when(pl.col("_cure") == 1).then(pl.col("_idx")).otherwise(None).forward_fill().over(by_loan),
        _episodes=pl.col("_start").rolling_sum(WINDOW, min_samples=1).over(by_loan),
        _max=pl.col("_act").rolling_max(WINDOW, min_samples=1).over(by_loan),
        _seen=(pl.col("_idx") + 1).clip(upper_bound=WINDOW),
    )
    since = pl.col("_idx") - pl.col("_last_cure")
    s = s.with_columns(_since=pl.when(pl.col("_last_cure").is_null() | (since >= WINDOW)).then(pl.lit(NO_CURE)).otherwise(since))
    # features of month t are those of the record before it
    shifted = [pl.col(c).shift(1).over(by_loan).alias(a) for c, a in [
        ("_episodes", "hist_episodes_12m"), ("_since", "hist_months_since_cure"),
        ("_max", "hist_max_state_12m"), ("_seen", "hist_months_observed")]]
    out = s.with_columns(*shifted, source_state=prev_state).filter(
        prev_ok & ~pl.col("_ended_before") & ~pl.col("_off") & pl.col("state").is_in([CURRENT, D30, D60, DEFAULT, PREPAID]))
    return out.select([KEY, "loan_age", "month", "source_state", pl.col("state").alias("label"),
                       pl.col("hist_episodes_12m").cast(pl.Int8), pl.col("hist_months_since_cure").cast(pl.Int8),
                       pl.col("hist_max_state_12m").cast(pl.Int8), pl.col("hist_months_observed").cast(pl.Int8)])


def subsample(rows: pl.DataFrame) -> pl.DataFrame:
    """Keeps every row except 90% of the stay-current ones, which carry weight 10 instead."""
    stay = (pl.col("source_state") == CURRENT) & (pl.col("label") == CURRENT)
    bucket = pl.struct([KEY, "loan_age"]).hash(seed=SEED) % 1000
    return (rows.filter(~stay | (bucket < CONTINUE_KEEP_PER_MILLE))
            .with_columns(weight=pl.when(stay).then(1000.0 / CONTINUE_KEEP_PER_MILLE).otherwise(1.0).cast(pl.Float32)))


def check_quarter(label: str, panel: pl.DataFrame, loans: pl.DataFrame, rows: pl.DataFrame) -> dict:
    """Dataset checks (tasks 2.5): the default definition is the survival dataset's, the states are exclusive, no
    loan has rows after an absorbing state, and every row's loan is one of the survival sample."""
    states = record_states(panel)
    mine = (states.filter(pl.col("state") == DEFAULT).group_by(KEY).agg(first_default=pl.col("loan_age").min()))
    theirs = loans.select([KEY, "default_age"]).filter(pl.col("default_age").is_not_null())
    joined = theirs.join(mine, on=KEY, how="full", coalesce=True)
    mismatched = joined.filter(pl.col("default_age").fill_null(-1) != pl.col("first_default").fill_null(-1)).height
    after_end = (rows.group_by(KEY).agg(last=pl.col("loan_age").max())
                 .join(rows.filter(pl.col("label").is_in([DEFAULT, PREPAID])).group_by(KEY).agg(end=pl.col("loan_age").min()),
                       on=KEY).filter(pl.col("last") > pl.col("end")).height)
    bad_states = rows.filter(~pl.col("source_state").is_in([CURRENT, D30, D60]) | ~pl.col("label").is_in(
        [CURRENT, D30, D60, DEFAULT, PREPAID])).height
    outside_sample = rows.join(loans.select(KEY), on=KEY, how="anti").height
    return {"default_age_mismatches": mismatched, "rows_after_absorbing_state": after_end, "invalid_states": bad_states,
            "loans_outside_the_survival_sample": outside_sample}


def quarters() -> list[tuple[int, str]]:
    found = []
    for path in sorted(glob.glob(str(DATA / "survival" / "loans" / "*.parquet"))):
        label = Path(path).stem
        found.append((int(label[:4]), label[4:]))
    return found


def process_quarter(year: int, q: str) -> None:
    label = f"{year}{q}"
    out = OUT / "rows" / f"{label}.parquet"
    if out.exists():
        print(f"[skip] {label}", flush=True)
        return
    started = time.time()
    loans = pl.read_parquet(DATA / "survival" / "loans" / f"{label}.parquet")
    ids = loans[KEY]
    parts = sorted(glob.glob(str(DATA / "monthly_panel" / f"orig_year={year}" / f"orig_quarter={q}" / label / "part-*.parquet")))
    if not parts:
        print(f"[warn] {label}: no performance panel, skipped", flush=True)
        return
    panel = pl.concat([
        pl.scan_parquet(p).select(PANEL_COLS).filter(pl.col(KEY).is_in(ids.implode())).collect() for p in parts])
    transitions = transition_rows(panel)
    problems = check_quarter(label, panel, loans, transitions)
    if any(problems.values()):
        raise SystemExit(f"{label}: dataset check failed: {problems}")
    rows = subsample(transitions).join(loans.select([KEY, *NUMERIC, *CATEGORICAL, "orig_month"]), on=KEY).with_columns(
        orig_year=pl.lit(year, pl.Int32), orig_quarter=pl.lit(q)).sort([KEY, "loan_age"])
    (OUT / "rows").mkdir(parents=True, exist_ok=True)
    rows.write_parquet(out)
    from_late = transitions.filter(pl.col("source_state") > 0).height
    print(f"[ok]   {label}: {loans.height} loans, {transitions.height} transitions ({from_late} from a late state) -> "
          f"{rows.height} rows, {time.time() - started:.1f}s", flush=True)


def main() -> None:
    selected = quarters()
    if len(sys.argv) > 1:
        selected = [yq for yq in selected if f"{yq[0]}{yq[1]}" in sys.argv[1:]]
    for year, q in selected:
        process_quarter(year, q)


if __name__ == "__main__":
    main()
