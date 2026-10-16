## Why

The `historical_data_20{20..25}/` folders hold 23 quarterly ZIP archives (~8.9 GB compressed) of the Freddie Mac Single-Family Loan-Level Dataset. Each ZIP contains two headerless, pipe-delimited `.txt` files (loan origination records and monthly loan performance records) that are unreadable without the external Freddie Mac data dictionary and are far too large to load with standard tools (pandas/Excel). Before any credit-risk analysis or default-prediction modeling can happen, the raw data needs to be extracted, made human-readable (headers + typed columns), cleaned, and assembled into a modeling-ready dataset.

## What Changes

- Extract all 23 quarterly ZIPs across 2020–2025Q3 into raw origination and performance text files.
- Convert each raw file into a readable, typed, headered format (apply the official Freddie Mac column layout to both origination and performance schemas) and persist as Parquet.
- Clean the readable data: normalize sentinel/blank values (e.g. `999`, `9999`, blank strings) to nulls, enforce per-column data types, deduplicate records, and validate row counts against source files.
- Preprocess the cleaned data: join origination records to their performance history by `Loan Sequence Number`, derive a loan-level default/delinquency target (e.g. ever 90+ days delinquent, foreclosure, or REO per Zero Balance Code), and engineer standard risk features (e.g. loan age, delinquency status trajectory, current LTV).
- Produce a final combined, partitioned (by origination year/quarter) Parquet dataset ready for credit-risk/default modeling, plus a data dictionary documenting all columns.
- Build this as a repeatable Polars-based pipeline (scripts/notebook) rather than a one-off manual transformation, since new quarterly drops will need the same process.

## Capabilities

### New Capabilities
- `dataset-extraction`: Extracting and validating raw origination/performance files from the quarterly ZIP archives.
- `dataset-readability`: Applying the Freddie Mac schema (headers, column names, data types) to raw pipe-delimited files and persisting them as readable Parquet tables.
- `dataset-cleaning`: Normalizing sentinel values, enforcing types, deduplicating, and validating cleaned origination/performance tables.
- `dataset-preprocessing`: Joining origination and performance data, deriving the default/delinquency target, engineering risk features, and producing the final modeling-ready dataset.

### Modified Capabilities
(none — this is a new pipeline in a repo with no existing specs)

## Impact

- **New code**: a Polars-based data pipeline (extraction, schema-mapping, cleaning, preprocessing stages) plus a generated data dictionary.
- **New data artifacts**: extracted raw text files, readable per-quarter Parquet tables, cleaned tables, and a final combined modeling dataset — all written under a new `data/` output tree (raw/readable/clean/processed stages), separate from the source `historical_data_*` ZIP folders.
- **Disk/compute**: unzipping ~8.9 GB of ZIPs and converting to Parquet will require tens of GB of free disk space and a Polars (out-of-core capable) processing approach rather than in-memory pandas.
- **No existing systems affected** — this is greenfield data preparation work with no prior specs or code in this repo.
