"""The multi-state dataset (build_delinquency_dataset.py) on synthetic loans with known answers: states and
transitions, the history features against a plain-loop reference, and the leakage rule (a month's features do not
change when records from that month on are altered). No raw data is read."""
import random
from datetime import date

import polars as pl
import pytest

import build_delinquency_dataset as dd


def panel(loans: dict[str, list[tuple[str, str | None]]], first_month: tuple[int, int] = (2020, 1), skip: dict | None = None,
          ages: dict[str, list[int]] | None = None) -> pl.DataFrame:
    """loans: id -> [(status, zero_balance_code), ...] for consecutive months starting at first_month. `skip` maps
    an id to month offsets left out of the record, as a gap in a servicer's reporting; `ages` sets a loan's loan_age
    per record (default 0, 1, 2, ...)."""
    records = []
    for loan, months in loans.items():
        for i, (status, zb) in enumerate(months):
            if i in (skip or {}).get(loan, ()):
                continue
            y, m = divmod(first_month[0] * 12 + first_month[1] - 1 + i, 12)
            records.append({"loan_sequence_number": loan, "monthly_reporting_period": date(y, m + 1, 1),
                            "current_loan_delinquency_status": status, "loan_age": (ages or {}).get(loan, range(len(months)))[i],
                            "zero_balance_code": zb})
    return pl.DataFrame(records, schema={"loan_sequence_number": pl.String, "monthly_reporting_period": pl.Date,
                                         "current_loan_delinquency_status": pl.String, "loan_age": pl.Int64,
                                         "zero_balance_code": pl.String})


def seq(codes: str, end_zb: str | None = None) -> list[tuple[str, str | None]]:
    """'0012' -> statuses 00, 00, 01, 02; `R` is the REO status RA, `X` an unreported one (XX); `end_zb` goes on the
    last record."""
    out = [(f"{int(c):02d}" if c.isdigit() else {"R": "RA", "X": "XX"}[c], None) for c in codes]
    if end_zb:
        out[-1] = (out[-1][0], end_zb)
    return out


def reference(states: list[int], i: int) -> tuple[int, int, int, int]:
    """History before record i from a plain loop over the records, per the module's definition: episodes begun,
    months since the latest cure, worst state and months seen in the 12 records before i."""
    window = range(max(0, i - 12), i)

    def late(j):
        return j >= 0 and states[j] in (1, 2)

    episodes = sum(1 for j in window if late(j) and not late(j - 1))
    cures = [j for j in range(i) if states[j] == 0 and late(j - 1)]
    since = i - 1 - cures[-1] if cures else dd.NO_CURE
    since = dd.NO_CURE if since >= 12 else since
    worst = max((states[j] for j in window), default=0)
    return episodes, since, worst, len(window)


def features(rows: pl.DataFrame, loan: str, age: int) -> tuple:
    r = rows.filter((pl.col("loan_sequence_number") == loan) & (pl.col("loan_age") == age))
    assert r.height == 1, f"{loan} age {age}: {r.height} rows"
    row = r.row(0, named=True)
    return (row["source_state"], row["hist_episodes_12m"], row["hist_months_since_cure"], row["hist_max_state_12m"],
            row["hist_months_observed"])


def test_transitions_and_cure():
    rows = dd.transition_rows(panel({"A": seq("00120010", end_zb="01")}))
    got = [(r["loan_age"], r["source_state"], r["label"]) for r in rows.sort("loan_age").iter_rows(named=True)]
    # statuses 0,0,1,2,0,0,1,0 then a payoff on the last record: every move between consecutive months
    assert got == [(1, 0, 0), (2, 0, 1), (3, 1, 2), (4, 2, 0), (5, 0, 0), (6, 0, 1), (7, 1, 4)]
    # month 7 is the payoff (status 0, zero-balance 01): prepaid, not current
    assert rows.filter(pl.col("loan_age") == 7)["label"].to_list() == [dd.PREPAID]


def test_default_definition_and_absorbing_states():
    rows = dd.transition_rows(panel({
        "N90": seq("0012" + "3" + "4"),                 # 90+ days past due is default; nothing after it
        "REO": seq("001R9"),                           # REO acquisition
        "ZB": seq("0000", end_zb="09"),                 # adverse zero-balance termination from a current loan
        "PAY": seq("0001", end_zb="01"),                # payoff
        "OUT": seq("0000", end_zb="06"),                # removal for a non-credit reason: censored, no row for it
    }))
    last = {k: v for k, v in rows.sort("loan_age").group_by("loan_sequence_number").agg(
        pl.col("loan_age").last(), pl.col("label").last()).select(["loan_sequence_number", "label"]).iter_rows()}
    assert last == {"N90": dd.DEFAULT, "REO": dd.DEFAULT, "ZB": dd.DEFAULT, "PAY": dd.PREPAID, "OUT": dd.CURRENT}
    assert rows.filter(pl.col("loan_sequence_number") == "N90")["loan_age"].max() == 4   # the first 90+ month, no later row
    assert rows.filter(pl.col("loan_sequence_number") == "OUT")["loan_age"].max() == 2   # month 3 is the removal itself


def test_a_loan_that_cures_after_default_does_not_re_enter():
    rows = dd.transition_rows(panel({"C": seq("0013" + "0000")}))
    assert rows["loan_age"].max() == 3 and rows.sort("loan_age")["label"].to_list() == [0, 1, 3]


def test_a_re_aged_loan_is_censored_where_its_age_stops_following_the_calendar():
    ages = {"R": [0, 1, 2, 3, 4, 5, 3, 4, 5]}            # the age falls back at the sixth record
    rows = dd.transition_rows(panel({"R": seq("000000000")}, ages=ages))
    assert sorted(rows["loan_age"].to_list()) == [1, 2, 3, 4, 5]


def test_unreported_status_and_gaps_are_not_guessed():
    rows = dd.transition_rows(panel({"X": seq("00XX00"), "G": seq("000000")}, skip={"G": (3,)}))
    ages = sorted(rows.filter(pl.col("loan_sequence_number") == "X")["loan_age"].to_list())
    assert ages == [1, 5], "no row into or out of a month whose status is not reported; 5 pairs two reported months"
    assert sorted(rows.filter(pl.col("loan_sequence_number") == "G")["loan_age"].to_list()) == [1, 2, 5], \
        "the row after a gap in the record is not used"


@pytest.mark.parametrize("seed", range(6))
def test_history_features_match_a_plain_loop(seed):
    rng = random.Random(seed)
    codes, state = "", 0
    for _ in range(40):
        # a sticky chain over current, 30 and 60 days late
        state = rng.choices([0, 1, 2], weights=[[90, 10, 0], [35, 40, 25], [20, 20, 60]][state])[0]
        codes += str(state)
    rows = dd.transition_rows(panel({"L": seq(codes)}))
    states = [int(c) for c in codes]
    assert rows.height == len(codes) - 1
    for age in range(1, len(codes)):
        assert features(rows, "L", age)[1:] == reference(states, age), f"age {age}"
        assert features(rows, "L", age)[0] == states[age - 1]


def test_features_use_only_months_before_the_one_predicted():
    """Task 2.3: alter every record from month t on (status, zero-balance code, even the loan's end) and the
    features of the row for month t are unchanged."""
    rng = random.Random(7)
    codes = "".join(rng.choices("012", weights=[60, 25, 15], k=30))
    base = dd.transition_rows(panel({"L": seq(codes)}))
    for t in range(1, 30):
        altered_codes = codes[:t] + "".join(rng.choices("0126", k=30 - t))
        altered = seq(altered_codes.replace("6", "0"), end_zb=rng.choice([None, "01", "09"]))
        after = dd.transition_rows(panel({"L": altered}))
        assert features(base, "L", t) == features(after, "L", t), f"month {t}"


def test_subsample_keeps_every_event_and_weights_the_rest():
    rows = dd.transition_rows(panel({f"L{i}": seq("0" * 60 + "12" + "0" * 20) for i in range(40)}))
    kept = dd.subsample(rows)
    stay = (kept["source_state"] == 0) & (kept["label"] == 0)
    assert kept.filter(~stay)["weight"].unique().to_list() == [1.0]
    assert kept.filter(stay)["weight"].unique().to_list() == [10.0]
    assert kept.filter(~stay).height == rows.filter(~((pl.col("source_state") == 0) & (pl.col("label") == 0))).height
    share = kept.filter(stay).height / rows.filter((pl.col("source_state") == 0) & (pl.col("label") == 0)).height
    assert 0.07 < share < 0.13


def test_dataset_checks_pass_on_consistent_data_and_catch_a_different_default_age():
    p = panel({"A": seq("0012" + "3"), "B": seq("0000", end_zb="01")})
    states = dd.record_states(p)
    loans = pl.DataFrame({"loan_sequence_number": ["A", "B"], "default_age": [4, None]})
    rows = dd.transition_rows(p)
    assert dd.check_quarter("t", p, loans, rows) == {"default_age_mismatches": 0, "rows_after_absorbing_state": 0,
                                                     "invalid_states": 0, "loans_outside_the_survival_sample": 0}
    wrong = loans.with_columns(default_age=pl.Series([5, None], dtype=pl.Int64))
    assert dd.check_quarter("t", p, wrong, rows)["default_age_mismatches"] == 1
    assert states["state"].to_list().count(dd.DEFAULT) == 1
