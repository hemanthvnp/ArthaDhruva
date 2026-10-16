"""Diagnostic: is there seasonality / non-stationarity in the portfolio 90+ delinquency series that the
SARIMAX forecast (notebooks/sarimax_portfolio_forecast.ipynb, order=(1,0,0), trend="c", no seasonal term)
ignores, and does modelling it improve the out-of-time forecast?

Same series and same trimming as the notebook (ramp-up window and end-of-series coverage cliff removed),
same split (fit through 2021-12, judge 2022-01 onwards). Prints one JSON report.

    1. Stationarity: ADF (H0 unit root) and KPSS (H0 stationary) on level, first difference and logit.
    2. Seasonality: STL seasonal strength (Wang-Smith-Hyndman, 0 = none, 1 = pure) and an F-test that
       month-of-year dummies explain the first-differenced series; classical ratio-to-moving-average
       seasonal index estimated on the TRAINING months only.
    3. Out-of-time forecast error (mean absolute error, rate points) of: base AR(1)+trend, base on the
       training-deseasonalised series (index re-applied), +Fourier(1) seasonal terms, seasonal AR(12).

Run from backend/:  python experiments/sarimax_seasonality.py
"""
from __future__ import annotations

import json
import warnings
from pathlib import Path

import numpy as np
import pandas as pd
import polars as pl
import statsmodels.api as sm
from statsmodels.stats.anova import anova_lm
from statsmodels.tsa.seasonal import STL
from statsmodels.tsa.statespace.sarimax import SARIMAX
from statsmodels.tsa.stattools import adfuller, kpss

warnings.filterwarnings("ignore")

ROOT = Path(__file__).resolve().parents[2]
PANEL = str(ROOT / "data" / "processed" / "monthly_panel" / "orig_year=*" / "orig_quarter=*" / "*" / "part-*.parquet")
CACHE = ROOT / "data" / "docs" / "portfolio_monthly_series_full.parquet"  # the committed series starts in 2020
TRAIN_END, TEST_START = "2021-12-01", "2022-01-01"
MATURE_LOAN_AGE = 6  # the notebook's ramp-up rule: months before the oldest loan reaches this age are zero by construction


def aggregate() -> pd.DataFrame:
    """The notebook's aggregation, plus max_loan_age for the ramp-up rule. Cached: it scans the whole panel."""
    if CACHE.exists():
        return pl.read_parquet(CACHE).to_pandas()
    import glob
    status = pl.col("current_loan_delinquency_status").cast(pl.Int32, strict=False)
    parts = []
    for path in glob.glob(PANEL):  # one file at a time: scanning the whole panel at once exhausts memory
        parts.append(pl.scan_parquet(path, extra_columns="ignore")
                     .select("monthly_reporting_period", "current_loan_delinquency_status", "current_interest_rate", "loan_age")
                     .group_by("monthly_reporting_period")
                     .agg(n_active=pl.len(), n_delinquent_90=(status >= 3).sum(), rate_sum=pl.col("current_interest_rate").sum(),
                          max_loan_age=pl.col("loan_age").max()).collect())
    agg = (pl.concat(parts).group_by("monthly_reporting_period")
           .agg(pl.col("n_active").sum(), pl.col("n_delinquent_90").sum(), pl.col("rate_sum").sum(), pl.col("max_loan_age").max())
           .sort("monthly_reporting_period")
           .with_columns(delinquency_rate_90=pl.col("n_delinquent_90") / pl.col("n_active"),
                         avg_interest_rate=pl.col("rate_sum") / pl.col("n_active")).drop("rate_sum"))
    agg.write_parquet(CACHE)
    return agg.to_pandas()


def load() -> pd.DataFrame:
    pdf = aggregate().set_index("monthly_reporting_period")
    pdf.index = pd.to_datetime(pdf.index).to_period("M").to_timestamp()
    pdf = pdf.loc[pdf.index[pdf["max_loan_age"] >= MATURE_LOAN_AGE].min():]
    ratio = pdf["n_active"] / pdf["n_active"].shift(1)
    cliff = ratio[ratio < 0.5].index
    return pdf.loc[: cliff.min() - pd.DateOffset(months=1)] if len(cliff) else pdf


def tests(x: pd.Series) -> dict:
    x = x.dropna()
    return {"n": len(x), "adf_p": round(float(adfuller(x, autolag="AIC")[1]), 4),
            "kpss_p": round(float(kpss(x, regression="c", nlags="auto")[1]), 4)}


def seasonal_index(train: pd.Series) -> pd.Series:
    """Classical multiplicative index: ratio to a centred 2x12 moving average, averaged by calendar month,
    normalised to mean 1. Estimated from the training months only."""
    ma = train.rolling(12, center=True).mean().rolling(2).mean().shift(-1)
    idx = (train / ma).groupby(train.index.month).mean()
    idx = idx.reindex(range(1, 13))
    idx = idx.fillna(1.0)  # a calendar month the training span cannot estimate gets no adjustment
    return idx / idx.mean()


def fourier(index: pd.DatetimeIndex, k: int = 1) -> pd.DataFrame:
    t = 2 * np.pi * index.month.to_numpy() / 12
    cols = {}
    for j in range(1, k + 1):
        cols[f"sin{j}"], cols[f"cos{j}"] = np.sin(j * t), np.cos(j * t)
    return pd.DataFrame(cols, index=index)


def mae(y: pd.Series, pred) -> float:
    return round(float(np.mean(np.abs(y.to_numpy() - np.asarray(pred)))), 5)


def forecast(y_tr, y_te, exog_tr=None, exog_te=None, seasonal=(0, 0, 0, 0)):
    fit = SARIMAX(y_tr, exog=exog_tr, order=(1, 0, 0), seasonal_order=seasonal, trend="c",
                  enforce_stationarity=False, enforce_invertibility=False).fit(disp=False, maxiter=300)
    return fit, fit.get_forecast(steps=len(y_te), exog=exog_te).predicted_mean.to_numpy()


def main() -> None:
    pdf = load()
    y = pdf["delinquency_rate_90"]
    y_tr, y_te = y.loc[:TRAIN_END], y.loc[TEST_START:]
    report: dict = {"months": len(y), "train": len(y_tr), "test": len(y_te),
                    "range": [str(y.index.min().date()), str(y.index.max().date())]}

    eps = 1e-9
    logit = np.log((y + eps) / (1 - y - eps))
    report["stationarity"] = {"level": tests(y), "diff1": tests(y.diff()), "logit": tests(logit),
                              "logit_diff1": tests(logit.diff())}

    stl = STL(y_tr, period=12, robust=True).fit()
    strength = max(0.0, 1 - float(np.var(stl.resid) / np.var(stl.seasonal + stl.resid)))
    d = y_tr.diff().dropna()
    dummies = pd.get_dummies(pd.Series(d.index.month, index=d.index), drop_first=True).astype(float)
    full = sm.OLS(d, sm.add_constant(dummies)).fit()
    f_test = anova_lm(sm.OLS(d, np.ones(len(d))).fit(), full)
    report["seasonality"] = {"stl_seasonal_strength_train": round(strength, 3),
                             "month_effects_F_p_on_diff1": round(float(f_test["Pr(>F)"].iloc[1]), 4),
                             "train_years": round(len(y_tr) / 12, 1),
                             "seasonal_index_train": {int(m): round(float(v), 3) for m, v in seasonal_index(y_tr).items()}}

    results = {}
    fit, p = forecast(y_tr, y_te)
    results["base_ar1_trend"] = {"mae": mae(y_te, p), "aic": round(float(fit.aic), 2)}

    idx = seasonal_index(y_tr)
    y_tr_sa = y_tr / idx.reindex(y_tr.index.month).to_numpy()
    fit, p = forecast(y_tr_sa, y_te)
    p = p * idx.reindex(y_te.index.month).to_numpy()
    results["deseasonalised_ratio_index"] = {"mae": mae(y_te, p), "aic": round(float(fit.aic), 2)}

    fit, p = forecast(y_tr, y_te, fourier(y_tr.index), fourier(y_te.index))
    results["fourier_k1_exog"] = {"mae": mae(y_te, p), "aic": round(float(fit.aic), 2)}

    fit, p = forecast(y_tr, y_te, seasonal=(1, 0, 0, 12))
    results["seasonal_ar12"] = {"mae": mae(y_te, p), "aic": round(float(fit.aic), 2)}
    report["out_of_time"] = results

    best = min(results, key=lambda k: results[k]["mae"])
    seasonal_supported = strength > 0.6 and report["seasonality"]["month_effects_F_p_on_diff1"] < 0.05
    report["verdict"] = {"best_out_of_time": best, "seasonality_statistically_supported": seasonal_supported}
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
