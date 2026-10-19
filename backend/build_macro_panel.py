"""Builds the macro panel the segment graph is learned from (openspec/changes/segment-graph-models, task 1.4).

Two sources per state, from data/processed/survival/macro_state.parquet (built by fetch_macro.py):
  unemployment  monthly change in the unemployment rate, 1995-02 onward
  hpi           quarterly log growth of the FHFA house price index between quarter values (the published
                quarterly observations, which fetch_macro.py places on the first month of each quarter)
Quarters stop at the last published national observation (data/external/fred/USSTHPI.csv): fetch_macro.py carries
the index flat beyond it, which would read as zero growth.

Each state's change is regressed on the mean change of the other 50 places, and the residual standardised
(segment_series.residualise_loo). The published national series (UNRATE, USSTHPI from the same FRED cache) are
kept as national_change and reported for comparison but not used: the national CPS unemployment rate and the
model-based state LAUS estimates move differently enough that regressing on UNRATE leaves a common factor with
49% of the residual variance, against 32% for the cross-state mean (design.md, amendment of 2026-10-19). Places whose own series was replaced by the national one in
fetch_macro.py are listed and left out, as are the territories (the graph covers the 50 states and DC). The
share of variance on the first principal component of each residual panel is printed: the design expects it
well below the 89.5% of today's 15-state delinquency series.

Writes data/processed/segments/macro_panel.parquet: source, state, period (year * 12 + month - 1), change,
national_change, residual (residual and standardisation fitted on the full sample; the validation graph refits
them on data through 2016-12 from change).

Run from backend/:  python build_macro_panel.py
"""
from __future__ import annotations

from pathlib import Path

import numpy as np
import polars as pl

import segment_series as ss

ROOT = Path(__file__).resolve().parents[1]
MACRO_STATE = ROOT / "data" / "processed" / "survival" / "macro_state.parquet"
FRED = ROOT / "data" / "external" / "fred"
OUT = ROOT / "data" / "processed" / "segments" / "macro_panel.parquet"


def fred(series: str) -> pl.DataFrame:
    df = pl.read_csv(FRED / f"{series}.csv", try_parse_dates=True, null_values=["."])
    return (df.rename({df.columns[0]: "date", df.columns[1]: "value"})
            .with_columns(period=(pl.col("date").dt.year() * 12 + pl.col("date").dt.month() - 1).cast(pl.Int32),
                          value=pl.col("value").cast(pl.Float64))
            .drop_nulls("value").select("period", "value"))


def changes(macro: pl.DataFrame, column: str, quarterly: bool, last_period: int) -> pl.DataFrame:
    df = macro.rename({"month": "period"}).filter(pl.col("period") <= last_period)
    if quarterly:
        df = df.filter(pl.col("period") % 3 == 0).with_columns(pl.col(column).log())
    return (df.sort("state", "period")
            .with_columns(change=pl.col(column).diff().over("state"))
            .drop_nulls("change").select("state", "period", "change"))


def main() -> None:
    macro = pl.read_parquet(MACRO_STATE)
    fallback = {
        "unemployment": sorted(macro.filter(pl.col("unemployment_source") == "national")["state"].unique().to_list()),
        "hpi": sorted(macro.filter(pl.col("hpi_source") == "national")["state"].unique().to_list()),
    }
    places = sorted(macro["state"].unique().to_list())
    missing = sorted(set(ss.STATES) - set(places))
    if missing:
        raise ss.BuildError(f"states missing from {MACRO_STATE.name}: {', '.join(missing)}")

    nat_hpi, nat_ur = fred("USSTHPI"), fred("UNRATE")
    sources = {
        "unemployment": (changes(macro, "unemployment", False, int(nat_ur["period"].max())),
                         nat_ur.with_columns(national_change=pl.col("value").diff()), "monthly"),
        "hpi": (changes(macro, "hpi", True, int(nat_hpi["period"].max())),
                nat_hpi.with_columns(national_change=pl.col("value").log().diff()), "quarterly"),
    }

    frames = []
    for source, (state_changes, national, freq) in sources.items():
        left_out = [s for s in fallback[source] if s in ss.STATES]
        use = [s for s in ss.STATES if s not in left_out]
        wide = (state_changes.filter(pl.col("state").is_in(use))
                .pivot(index="period", on="state", values="change").sort("period")
                .join(national.select("period", "national_change"), on="period", how="inner")
                .drop_nulls())
        if wide.is_empty():
            raise ss.BuildError(f"{source}: empty series")
        x = wide.select(use).to_numpy()
        resid = ss.residualise_loo(x)
        raw_share = ss.first_pc_share(x)
        share = ss.first_pc_share(resid)
        published = ss.first_pc_share(ss.residualise(x, wide["national_change"].to_numpy()))
        periods = wide["period"].to_list()
        print(f"{source} ({freq}): {len(use)} places x {len(periods)} periods, "
              f"{ss.month_label(periods[0])} .. {ss.month_label(periods[-1])}")
        print(f"  first principal component: {raw_share:.1%} of the raw changes, {share:.1%} after removing the others' mean "
              f"({published:.1%} against the published national series)")
        print(f"  national fallback, left out: {', '.join(fallback[source]) or 'none'}"
              f" (of which in the 50 states and DC: {', '.join(left_out) or 'none'})")
        long = (pl.DataFrame(resid, schema=use).with_columns(period=pl.Series(periods, dtype=pl.Int32))
                .unpivot(index="period", variable_name="state", value_name="residual"))
        frames.append(long.join(state_changes, on=["state", "period"])
                      .join(wide.select("period", "national_change"), on="period")
                      .with_columns(source=pl.lit(source)))

    out = pl.concat(frames).select("source", "state", "period", "change", "national_change", "residual") \
        .sort("source", "state", "period")
    OUT.parent.mkdir(parents=True, exist_ok=True)
    out.write_parquet(OUT)
    print(f"wrote {OUT}  {out.shape}")


if __name__ == "__main__":
    main()
