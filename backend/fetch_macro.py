"""Downloads the macro drivers of mortgage credit risk from FRED and builds a monthly state panel.

Series (public FRED CSV endpoint, cached under ../data/external/fred/ so a rerun is offline):
  * <ST>STHPI  FHFA all-transactions house price index by state (quarterly)
  * <ST>UR     unemployment rate by state (monthly, seasonally adjusted)
  * USSTHPI / UNRATE  national equivalents, used for any state FRED does not publish

Output: ../data/processed/survival/macro_state.parquet with one row per (state, month):
  hpi            house price index, quarterly values interpolated log-linearly to months
  unemployment   unemployment rate (%)
Months are indexed as year*12 + month - 1, the convention every training script uses.
"""
from __future__ import annotations

import io
import time
import urllib.request
from pathlib import Path

import numpy as np
import polars as pl

from credit_common import SURVIVAL, category_mappings

FRED = "https://fred.stlouisfed.org/graph/fredgraph.csv?id="
CACHE = Path("../data/external/fred")
FIRST_MONTH = 1995 * 12


def fetch(series: str) -> pl.DataFrame | None:
    CACHE.mkdir(parents=True, exist_ok=True)
    path = CACHE / f"{series}.csv"
    if not path.exists():
        try:
            with urllib.request.urlopen(FRED + series, timeout=30) as response:
                body = response.read()
        except Exception as e:  # a series FRED does not publish (e.g. territories) falls back to national
            print(f"  {series}: unavailable ({type(e).__name__})", flush=True)
            return None
        if not body.startswith(b"observation_date"):
            print(f"  {series}: unexpected response, skipped", flush=True)
            return None
        path.write_bytes(body)
        time.sleep(0.2)
    df = pl.read_csv(io.BytesIO(path.read_bytes()), try_parse_dates=True, null_values=["."])
    value = df.columns[1]
    return (df.rename({"observation_date": "date", value: "value"})
            .with_columns(month=(pl.col("date").dt.year() * 12 + pl.col("date").dt.month() - 1).cast(pl.Int32),
                          value=pl.col("value").cast(pl.Float64))
            .drop_nulls("value").select(["month", "value"]))


def monthly(series: pl.DataFrame, last_month: int, log_interp: bool) -> np.ndarray:
    """Values on the month grid FIRST_MONTH..last_month: interpolated between observations,
    carried flat before the first and after the last observation."""
    months = np.arange(FIRST_MONTH, last_month + 1)
    x = series["month"].to_numpy().astype(float)
    y = series["value"].to_numpy()
    if log_interp:
        return np.exp(np.interp(months, x, np.log(y)))
    return np.interp(months, x, y)


def main() -> None:
    states = sorted(category_mappings()["property_state"])
    national_hpi = fetch("USSTHPI")
    national_ur = fetch("UNRATE")
    last_month = int(max(national_hpi["month"].max() + 2, national_ur["month"].max()))
    frames = []
    for st in states:
        hpi = fetch(f"{st}STHPI")
        ur = fetch(f"{st}UR")
        frames.append(pl.DataFrame({
            "state": st,
            "month": np.arange(FIRST_MONTH, last_month + 1, dtype=np.int32),
            "hpi": monthly(hpi if hpi is not None else national_hpi, last_month, True),
            "unemployment": monthly(ur if ur is not None else national_ur, last_month, False),
            "hpi_source": "state" if hpi is not None else "national",
            "unemployment_source": "state" if ur is not None else "national",
        }))
    panel = pl.concat(frames)
    SURVIVAL.mkdir(parents=True, exist_ok=True)
    panel.write_parquet(SURVIVAL / "macro_state.parquet")
    fallback = panel.filter((pl.col("hpi_source") == "national") | (pl.col("unemployment_source") == "national"))
    print(f"macro_state: {panel.height:,} rows, {len(states)} states, months through "
          f"{last_month // 12}-{last_month % 12 + 1:02d}; national fallback for "
          f"{sorted(fallback['state'].unique().to_list())}", flush=True)


if __name__ == "__main__":
    main()
