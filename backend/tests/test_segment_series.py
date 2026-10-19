"""The segment risk series (segment_series.py, build_state_panel.py) on synthetic data with known answers."""
import datetime as dt

import numpy as np
import polars as pl
import pytest

import build_state_panel as bsp
import segment_series as ss

STATES = ["AA", "BB", "CC", "DD"]
MONTHS = [24240, 24241, 24242]


def cells(rate_by_state: dict[str, float], mix_by_state: dict[str, list[int]] | None = None,
          cell_rates=(0.01, 0.05)) -> pl.DataFrame:
    """Two cells (age buckets 0 and 1) with national rates cell_rates; each state's rate in each cell is the cell
    rate times the state's multiplier, so a multiplier of 2 is a state twice as delinquent on the same mix."""
    rows = []
    for state, mult in rate_by_state.items():
        mix = (mix_by_state or {}).get(state, [100_000, 100_000])
        for month in MONTHS:
            for age, (n, r) in enumerate(zip(mix, cell_rates)):
                d = round(n * r * mult)
                rows.append({"state": state, "month": month, "age_bucket": age, "score_band": 0, "ltv_band": 0,
                             "n_active": n, "n_delinquent": d, "n_delinquent_disaster": d // 4,
                             "n_delinquent_assistance": d // 2})
    return pl.DataFrame(rows)


def series(c: pl.DataFrame, **kw) -> pl.DataFrame:
    return ss.standardised(c, ss.VARIANTS["primary"], states=STATES, **kw)


def log_ratio(s: pl.DataFrame, state: str) -> float:
    return s.filter(pl.col("state") == state)["log_ratio"].mean()


def test_a_state_with_a_known_excess_is_recovered():
    s = series(cells({"AA": 2.0, "BB": 1.0, "CC": 1.0, "DD": 1.0}))
    assert log_ratio(s, "AA") == pytest.approx(np.log(2.0), abs=0.01)
    # BB's reference rate is the other three states' (2 + 1 + 1) / 3 = 4/3 of the cell rate, AA's excess included.
    assert log_ratio(s, "BB") == pytest.approx(np.log(3 / 4), abs=0.01)


def test_a_different_loan_mix_alone_is_not_an_excess():
    # CC holds mostly the riskier cell; at the same cell rates its raw rate is far higher, its ratio is not.
    c = cells({s: 1.0 for s in STATES}, mix_by_state={"CC": [10_000, 190_000]})
    s = series(c)
    raw = c.filter(pl.col("state") == "CC").select(pl.col("n_delinquent").sum() / pl.col("n_active").sum()).item()
    assert raw > 0.04
    assert log_ratio(s, "CC") == pytest.approx(0.0, abs=0.01)


def test_leave_one_out_excludes_the_state_itself():
    base = series(cells({"AA": 1.0, "BB": 1.0, "CC": 1.0, "DD": 1.0}))
    worse = series(cells({"AA": 3.0, "BB": 1.0, "CC": 1.0, "DD": 1.0}))
    # AA's own delinquencies must not move its expected count ...
    assert worse.filter(pl.col("state") == "AA")["expected"].to_list() == \
        pytest.approx(base.filter(pl.col("state") == "AA")["expected"].to_list())
    # ... while the other states' expected counts do rise, because AA is in their reference rates.
    assert worse.filter(pl.col("state") == "BB")["expected"].sum() > base.filter(pl.col("state") == "BB")["expected"].sum()
    # A large excess state measured against a rate that included itself would be pulled toward 1; here it is not.
    assert log_ratio(worse, "AA") == pytest.approx(np.log(3.0), abs=0.01)


def test_thin_months_are_missing_not_computed():
    c = cells({"AA": 1.0, "BB": 1.0, "CC": 1.0, "DD": 1.0}, mix_by_state={"DD": [100, 100]})
    s = series(c)
    assert s.filter(pl.col("state") == "DD")["log_ratio"].null_count() == len(MONTHS)
    assert s.filter(pl.col("state") == "AA")["log_ratio"].null_count() == 0
    cov = ss.coverage(s, MONTHS, states=STATES)
    assert cov.row(0, named=True) == {"state": "DD", "months_present": 0, "coverage": 0.0}


def test_variants_come_from_the_same_counts():
    c = cells({"AA": 2.0, "BB": 1.0, "CC": 1.0, "DD": 1.0})
    primary = ss.standardised(c, ss.VARIANTS["primary"], states=STATES)
    ex_dis = ss.standardised(c, ss.VARIANTS["ex_disaster"], states=STATES)
    assert ex_dis["observed"].sum() == pytest.approx(primary["observed"].sum() - c["n_delinquent_disaster"].sum())
    assert ex_dis["n_active"].to_list() == primary["n_active"].to_list()


@pytest.mark.parametrize("broken, message", [
    (lambda c: c.filter(pl.col("state") != "DD"), "states missing"),
    (lambda c: pl.concat([c, c.head(1)]), r"share a \(state, month, cell\) key"),
    (lambda c: c.with_columns(n_active=pl.when(pl.col("state") == "CC").then(0).otherwise(pl.col("n_active"))),
     "no active loans"),
    (lambda c: c.head(0), "no cell counts"),
])
def test_a_build_that_cannot_be_trusted_stops(broken, message):
    with pytest.raises(ss.BuildError, match=message) as e:
        ss.check_cells(broken(cells({s: 1.0 for s in STATES})), states=STATES)
    assert e.value.code != 0


def test_buckets():
    df = pl.DataFrame({"x": [None, 0, 5, 6, 59, 60, 200]})
    assert df.select(b=ss.bucket(pl.col("x"), ss.AGE_EDGES))["b"].to_list() == [-1, 0, 0, 1, 4, 5, 5]
    ltv = pl.DataFrame({"x": [60.0, 60.5, 80.0, 95.0]}).select(b=ss.bucket(pl.col("x"), ss.LTV_EDGES, right_closed=True))
    assert ltv["b"].to_list() == [0, 1, 1, 3]


def test_delinquent_means_ninety_days_or_reo():
    df = pl.DataFrame({"current_loan_delinquency_status": ["0", "00", "02", "03", "3", "12", "RA", "XX", None]})
    assert df.select(d=ss.delinquent())["d"].to_list() == \
        [False, False, False, True, True, True, True, False, False]


def test_residuals_are_free_of_the_national_series_and_standardised():
    rng = np.random.default_rng(1)
    national = rng.normal(size=300)
    betas = np.array([0.5, 1.0, 2.0])
    changes = national[:, None] * betas + rng.normal(scale=0.3, size=(300, 3))
    r = ss.residualise(changes, national)
    assert np.allclose(r.mean(axis=0), 0, atol=1e-12)
    assert np.allclose(r.std(axis=0, ddof=1), 1)
    assert np.abs([np.corrcoef(national, r[:, j])[0, 1] for j in range(3)]).max() < 1e-10


def test_residuals_fitted_before_a_cutoff_ignore_what_comes_after():
    rng = np.random.default_rng(2)
    national = rng.normal(size=200)
    changes = national[:, None] * np.array([1.0, 2.0]) + rng.normal(scale=0.2, size=(200, 2))
    fit = np.arange(200) < 120
    shocked = changes.copy()
    shocked[150:] += 50.0  # anything after the cutoff
    a, b = ss.residualise(changes, national, fit), ss.residualise(shocked, national, fit)
    assert np.allclose(a[:120], b[:120])


def test_leave_one_out_mean_excludes_the_state():
    rng = np.random.default_rng(3)
    x = rng.normal(size=(100, 4))
    expected = ss.residualise(x[:, [0]], x[:, 1:].mean(axis=1))[:, 0]
    assert np.allclose(ss.residualise_loo(x)[:, 0], expected)


def test_first_pc_share_of_a_one_factor_panel():
    rng = np.random.default_rng(4)
    f = rng.normal(size=(2000, 1))
    assert ss.first_pc_share(f @ np.ones((1, 5)) + rng.normal(scale=0.01, size=(2000, 5))) > 0.99
    assert ss.first_pc_share(rng.normal(size=(2000, 5))) < 0.3


# ---- the build resumes slice by slice -------------------------------------------------------------------

def make_raw(tmp_path):
    panel, loans = tmp_path / "monthly_panel", tmp_path / "loan_level"
    ids = [f"L{i}" for i in range(6)]
    pl.DataFrame({"loan_sequence_number": ids, "property_state": ["AA", "AA", "BB", "BB", "CC", "DD"],
                  "credit_score": [700, 800, None, 650, 760, 790], "original_ltv": [80.0, 95.0, 60.0, 70.0, 85.0, 90.0]}) \
        .write_parquet(_mk(loans / "orig_year=2020" / "orig_quarter=Q1") / "2020Q1.parquet")
    for k in range(3):
        n = len(ids)
        pl.DataFrame({
            "loan_sequence_number": ids,
            "monthly_reporting_period": [dt.date(2020, 4 + k, 1)] * n,
            "current_actual_upb": [1000.0] * n,
            "loan_age": [3 + k] * n,
            "current_loan_delinquency_status": ["0", "3", "RA", "0", "1", "4"],
            "delinquency_due_to_disaster": [None, "Y", None, None, None, None],
            "borrower_assistance_status_code": [None, None, "F", None, None, "T"],
        }).write_parquet(_mk(panel / "orig_year=2020" / "orig_quarter=Q1" / "2020Q1") / f"part-{k:05d}.parquet")
    return panel, loans


def _mk(p):
    p.mkdir(parents=True, exist_ok=True)
    return p


def build(monkeypatch, tmp_path):
    panel, loans = make_raw(tmp_path)
    monkeypatch.setattr(bsp, "PANEL", panel)
    monkeypatch.setattr(bsp, "LOAN_LEVEL", loans)
    monkeypatch.setattr(bsp, "PARTS", _mk(tmp_path / "parts"))
    quarters = bsp.quarters()
    return [bsp.build_quarter(q, paths) for q, paths in quarters.items()]


def test_an_interrupted_build_rebuilds_only_the_missing_slice(monkeypatch, tmp_path):
    assert build(monkeypatch, tmp_path) == [("2020Q1", 3, 0)]
    first = bsp.combine()
    victim = tmp_path / "parts" / "2020Q1_part-00001.parquet"
    others = {p: p.stat().st_mtime_ns for p in (tmp_path / "parts").glob("*.parquet") if p != victim}
    victim.unlink()

    assert build(monkeypatch, tmp_path) == [("2020Q1", 1, 0)]
    assert victim.exists()
    assert {p: p.stat().st_mtime_ns for p in others} == others
    assert bsp.combine().equals(first)


def test_slice_counts(monkeypatch, tmp_path):
    build(monkeypatch, tmp_path)
    c = bsp.combine()
    month = c.filter(pl.col("month") == 2020 * 12 + 3)
    assert month["n_active"].sum() == 6
    assert month["n_delinquent"].sum() == 3  # status 3, RA and 4
    assert month["n_delinquent_disaster"].sum() == 1
    assert month["n_delinquent_assistance"].sum() == 2
    assert month.filter(pl.col("state") == "BB")["score_band"].to_list().count(-1) == 1
