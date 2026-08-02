"""Generate a human-readable data dictionary from schema.py."""

from __future__ import annotations

from pipeline import config, schema


def _table(columns: list[schema.ColumnSpec]) -> str:
    lines = ["| # | Column | Type | Sentinel(s) -> null | Description |", "|---|---|---|---|---|"]
    for i, col in enumerate(columns, start=1):
        sentinels = ", ".join(f"`{s}`" if s != "" else "(blank)" for s in col.sentinels) or "-"
        lines.append(f"| {i} | `{col.name}` | {col.kind} | {sentinels} | {col.description} |")
    return "\n".join(lines)


def build_data_dictionary() -> str:
    return (
        "# Mortgage Dataset Data Dictionary\n\n"
        "Generated from `pipeline/schema.py`. Types shown are the semantic kind "
        "used by the pipeline (`date_yyyymm` columns are stored as raw YYYYMM "
        "integers in the readable stage and converted to real dates in the "
        "cleaning stage). Sentinel values are replaced with null during cleaning.\n\n"
        "## Origination file (32 columns)\n\n"
        f"{_table(schema.ORIGINATION_COLUMNS)}\n\n"
        "## Performance file (32 columns)\n\n"
        f"{_table(schema.PERFORMANCE_COLUMNS)}\n"
    )


def write_data_dictionary() -> None:
    config.ensure_output_dirs()
    out_path = config.DOCS_DIR / "data_dictionary.md"
    out_path.write_text(build_data_dictionary(), encoding="utf-8")
    print(f"Wrote data dictionary to {out_path}")


if __name__ == "__main__":
    write_data_dictionary()
