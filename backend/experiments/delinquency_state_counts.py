"""Discovery for openspec/changes/delinquency-aware-risk-and-uncertainty (tasks 1.2 and 1.3).

Counts, over the whole monthly panel, (a) every raw value of current_loan_delinquency_status and (b) the
month-to-month transitions between the proposed states, so the D60 pooling decision rests on numbers:

    C = status 0   D30 = 1   D60 = 2   D90+ = 3 or more, or RA (REO acquisition)   -> the existing default event

Transitions are counted between consecutive reported months of the same loan. A loan's rows must all sit in
one part file for a per-file pass to be valid; the script verifies that and says so in the report.

Run from backend/:  python experiments/delinquency_state_counts.py   (prints JSON; ~10 min, one file at a time)
"""
from __future__ import annotations

import glob
import json
from pathlib import Path

import polars as pl

PANEL = str(Path(__file__).resolve().parents[2] / "data" / "processed" / "monthly_panel" / "orig_year=*" / "orig_quarter=*" / "*" / "part-*.parquet")
NAMES = ["C", "D30", "D60", "D90+"]


def state(col: str) -> pl.Expr:
    n = pl.col(col).cast(pl.Int32, strict=False)
    return (pl.when(pl.col(col) == "RA").then(3).when(n >= 3).then(3).when(n.is_in([0, 1, 2])).then(n).otherwise(None)).alias("state")


def main() -> None:
    raw, trans = {}, {}
    owners: dict[str, int] = {}
    straddlers = 0
    files = glob.glob(PANEL)
    for i, path in enumerate(files):
        d = pl.read_parquet(path, columns=["loan_sequence_number", "monthly_reporting_period", "current_loan_delinquency_status"])
        ids = d["loan_sequence_number"].unique().to_list()
        for loan in ids:
            if owners.setdefault(loan, i) != i:
                straddlers += 1
        for row in d.group_by("current_loan_delinquency_status").len().iter_rows():
            raw[row[0]] = raw.get(row[0], 0) + row[1]
        d = (d.with_columns(state("current_loan_delinquency_status")).sort("loan_sequence_number", "monthly_reporting_period")
             .with_columns(prev=pl.col("state").shift(1).over("loan_sequence_number"),
                           gap=(pl.col("monthly_reporting_period").dt.year() * 12 + pl.col("monthly_reporting_period").dt.month()
                                - (pl.col("monthly_reporting_period").dt.year() * 12 + pl.col("monthly_reporting_period").dt.month()).shift(1).over("loan_sequence_number"))))
        d = d.filter(pl.col("prev").is_not_null() & pl.col("state").is_not_null() & (pl.col("gap") == 1))
        for row in d.group_by("prev", "state").len().iter_rows():
            key = f"{NAMES[row[0]]}->{NAMES[row[1]]}"
            trans[key] = trans.get(key, 0) + row[2]
        if i % 200 == 0:
            print(f"{i}/{len(files)}", flush=True)
    by_from = {n: sum(v for k, v in trans.items() if k.startswith(n + "->")) for n in NAMES}
    report = {"files": len(files), "loans": len(owners), "loans_split_across_files": straddlers,
              "raw_status_values": dict(sorted(raw.items(), key=lambda kv: -kv[1])),
              "transition_counts": dict(sorted(trans.items())), "loan_months_by_starting_state": by_from,
              "transition_rates": {k: round(v / by_from[k.split("->")[0]], 5) for k, v in sorted(trans.items())}}
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
