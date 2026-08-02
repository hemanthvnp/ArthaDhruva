"""Shared paths and quarter list for the mortgage data pipeline."""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent

RAW_SOURCE_DIR = REPO_ROOT
DATA_DIR = REPO_ROOT / "data"

RAW_DIR = DATA_DIR / "raw"
READABLE_DIR = DATA_DIR / "readable"
READABLE_ORIGINATION_DIR = READABLE_DIR / "origination"
READABLE_PERFORMANCE_DIR = READABLE_DIR / "performance"
CLEAN_DIR = DATA_DIR / "clean"
CLEAN_ORIGINATION_DIR = CLEAN_DIR / "origination"
CLEAN_PERFORMANCE_DIR = CLEAN_DIR / "performance"
PROCESSED_DIR = DATA_DIR / "processed"
LOAN_LEVEL_DIR = PROCESSED_DIR / "loan_level"
MONTHLY_PANEL_DIR = PROCESSED_DIR / "monthly_panel"
DOCS_DIR = DATA_DIR / "docs"

EXPECTED_COLUMN_COUNT = 32

# Years present under historical_data_<year>/ and the quarters available for each,
# per the ZIP files found in the repository. 2025 only has Q1-Q3 published so far.
YEAR_QUARTERS: dict[int, list[str]] = {
    2020: ["Q1", "Q2", "Q3", "Q4"],
    2021: ["Q1", "Q2", "Q3", "Q4"],
    2022: ["Q1", "Q2", "Q3", "Q4"],
    2023: ["Q1", "Q2", "Q3", "Q4"],
    2024: ["Q1", "Q2", "Q3", "Q4"],
    2025: ["Q1", "Q2", "Q3"],
}


@dataclass(frozen=True)
class Quarter:
    year: int
    quarter: str  # e.g. "Q1"

    @property
    def label(self) -> str:
        return f"{self.year}{self.quarter}"

    @property
    def zip_path(self) -> Path:
        return RAW_SOURCE_DIR / f"historical_data_{self.year}" / f"historical_data_{self.label}.zip"

    @property
    def origination_member(self) -> str:
        return f"historical_data_{self.label}.txt"

    @property
    def performance_member(self) -> str:
        return f"historical_data_time_{self.label}.txt"

    @property
    def raw_origination_path(self) -> Path:
        return RAW_DIR / self.label / self.origination_member

    @property
    def raw_performance_path(self) -> Path:
        return RAW_DIR / self.label / self.performance_member

    @property
    def readable_origination_path(self) -> Path:
        return READABLE_ORIGINATION_DIR / f"{self.label}.parquet"

    @property
    def readable_performance_path(self) -> Path:
        return READABLE_PERFORMANCE_DIR / f"{self.label}.parquet"

    @property
    def clean_origination_path(self) -> Path:
        return CLEAN_ORIGINATION_DIR / f"{self.label}.parquet"

    @property
    def clean_performance_dir(self) -> Path:
        """Directory of part-*.parquet files (performance tables can be 40M+ rows/quarter,
        so they're written and read in bounded chunks rather than as one file)."""
        return CLEAN_PERFORMANCE_DIR / self.label


def all_quarters() -> list[Quarter]:
    quarters: list[Quarter] = []
    for year in sorted(YEAR_QUARTERS):
        for q in YEAR_QUARTERS[year]:
            quarters.append(Quarter(year=year, quarter=q))
    return quarters


def parse_quarter(label: str) -> Quarter:
    """Parse a label like '2025Q3' into a Quarter."""
    year = int(label[:4])
    quarter = label[4:]
    return Quarter(year=year, quarter=quarter)


def ensure_output_dirs() -> None:
    for d in (
        RAW_DIR,
        READABLE_ORIGINATION_DIR,
        READABLE_PERFORMANCE_DIR,
        CLEAN_ORIGINATION_DIR,
        CLEAN_PERFORMANCE_DIR,
        LOAN_LEVEL_DIR,
        MONTHLY_PANEL_DIR,
        DOCS_DIR,
    ):
        d.mkdir(parents=True, exist_ok=True)
