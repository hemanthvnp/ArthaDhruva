## 1. Project Setup

- [x] 1.1 Create pipeline project structure (e.g. `pipeline/` with `extract.py`, `readable.py`, `clean.py`, `preprocess.py`, `schema.py`, a shared config/paths module)
- [x] 1.2 Add dependencies (Polars, PyArrow for Parquet, a CLI entry point) to a `requirements.txt`/`pyproject.toml`
- [x] 1.3 Define output directory layout (`data/raw/`, `data/readable/`, `data/clean/`, `data/processed/`) and quarter-list config covering 2020Q1–2025Q3

## 2. Schema Definition

- [x] 2.1 Encode the Freddie Mac origination schema (32 columns: name, position, dtype) in `schema.py`
- [x] 2.2 Encode the Freddie Mac performance schema (32 columns: name, position, dtype) in `schema.py`
- [x] 2.3 Document per-column sentinel/placeholder values (e.g. `999`, `9999`, blanks) that must map to null during cleaning
- [x] 2.4 Generate the human-readable data dictionary (name, type, description) from the schema definitions

## 3. Extraction (dataset-extraction)

- [x] 3.1 Implement ZIP discovery across `historical_data_20{20..25}/` folders
- [x] 3.2 Implement extraction of origination + performance `.txt` files into `data/raw/<quarter>/`
- [x] 3.3 Add column-count validation (expect 32) on each extracted file, failing loudly on mismatch
- [x] 3.4 Add skip-if-already-extracted behavior with an explicit force/overwrite flag
- [x] 3.5 Validate end-to-end on the smallest quarter (2025Q3) before running the full range

## 4. Readable Conversion (dataset-readability)

- [x] 4.1 Implement pipe-delimited read with the origination schema applied (headers + dtypes), writing Parquet to `data/readable/origination/<quarter>.parquet`
- [x] 4.2 Implement pipe-delimited read with the performance schema applied (headers + dtypes), writing Parquet to `data/readable/performance/<quarter>.parquet`
- [x] 4.3 Enforce the 32-column check before schema mapping and error out on mismatch
- [x] 4.4 Verify readable row counts match raw line counts per quarter

## 5. Cleaning (dataset-cleaning)

- [x] 5.1 Implement per-column sentinel-to-null normalization for origination data
- [x] 5.2 Implement per-column sentinel-to-null normalization for performance data
- [x] 5.3 Implement type enforcement/casting (including `YYYYMM` date parsing) for both tables
- [x] 5.4 Implement deduplication (origination by `loan_sequence_number`; performance by `loan_sequence_number` + `monthly_reporting_period`)
- [x] 5.5 Produce a per-quarter cleaning summary (input/output row counts, duplicates removed, nulls introduced) and write cleaned Parquet to `data/clean/`

## 6. Preprocessing (dataset-preprocessing)

- [x] 6.1 Implement the origination-to-performance join on `loan_sequence_number`, retaining loans with no performance history
- [x] 6.2 Derive the loan-level default/delinquency target from `current_loan_delinquency_status` and `zero_balance_code`
- [x] 6.3 Engineer loan-level risk features (current age, months since origination, worst delinquency status, current UPB trend)
- [x] 6.4 Write the final loan-level modeling table as Parquet, partitioned by origination year/quarter, to `data/processed/loan_level/`
- [x] 6.5 Write the cleaned, joined monthly performance panel as Parquet to `data/processed/monthly_panel/`
- [x] 6.6 Produce a join/label summary report (loans joined, loans with no performance history, default rate)

## 7. Pipeline Orchestration & Validation

- [x] 7.1 Add a top-level CLI/script to run all stages in order (extract → readable → clean → preprocess) with per-stage skip/force flags
- [x] 7.2 Run the full pipeline against all quarters (2020Q1–2025Q3) and confirm it completes without column/type errors
- [x] 7.3 Spot-check value distributions (credit score, delinquency status, target rate) between readable, clean, and processed stages to confirm no silent data corruption
- [x] 7.4 Record final dataset stats (row counts, partitions, default rate, disk size) in the data dictionary or a summary README
