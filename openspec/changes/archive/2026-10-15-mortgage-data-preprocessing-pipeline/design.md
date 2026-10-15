## Context

The source data is the Freddie Mac Single-Family Loan-Level Dataset, laid out as:

```
historical_data_2020/historical_data_2020Q1.zip
  historical_data_2020Q1.txt          # origination file, 32 pipe-delimited columns, no header
  historical_data_time_2020Q1.txt     # performance file, 32 pipe-delimited columns, no header
...
historical_data_2025/historical_data_2025Q3.zip   # latest quarter present
```

23 ZIPs total, ~8.9 GB compressed. Sampling the smallest quarter (2025Q3) shows ~165K origination rows and ~254K performance rows for that quarter alone; the full 2020–2025 range will span tens of millions of performance rows. Both files use Freddie Mac's fixed, documented column order (verified by column count: 32 fields per file) but use sentinel codes for missing data (e.g. blank fields, `999`/`9999`/`99` credit score or DTI placeholders) instead of nulls, and encode dates as `YYYYMM` integers.

There is no existing code or spec in this repo — this is a greenfield pipeline.

## Goals / Non-Goals

**Goals:**
- Deterministically go from raw ZIPs → readable, typed, headered tables → cleaned tables → a single joined, modeling-ready Parquet dataset with a derived default/delinquency target.
- Handle the full 2020–2025Q3 volume without requiring the whole dataset to fit in RAM at once.
- Make the pipeline re-runnable end-to-end and re-runnable per stage (e.g. re-clean without re-extracting) so future quarterly drops can be processed the same way.
- Document the schema (data dictionary) so the output is genuinely "readable" to a human, not just to code.

**Non-Goals:**
- Training or evaluating any actual credit-risk model — this change stops at producing the modeling-ready dataset.
- Building a UI, API, or scheduled/automated ingestion job — this is a batch, manually-triggered pipeline for now.
- Handling Freddie Mac dataset vintages/layouts other than the current 32-column origination/performance format already observed in the sampled files.

## Decisions

**Processing engine: Polars (lazy API), not pandas or Dask.**
Combined performance data across 23 quarters will be far larger than comfortably fits in memory with pandas. Polars' lazy `scan_csv`/streaming engine handles out-of-core processing on a single machine with a pandas-like API, avoiding the operational overhead of standing up Dask. DuckDB was considered (also strong for this) but Polars is preferred here for a single cohesive Python pipeline (schema application, cleaning, feature engineering) rather than mixing SQL and Python.

**Storage layout: four staged directories, not in-place mutation.**
```
data/
  raw/          extracted .txt files (from ZIPs, untouched)
  readable/     per-quarter Parquet, headers + dtypes applied, no cleaning yet
  clean/        per-quarter Parquet, sentinels->null, deduped, validated
  processed/    final joined + feature-engineered dataset, partitioned by origination year/quarter
```
Keeping stages separate (rather than overwriting) makes the pipeline resumable and debuggable — if cleaning logic changes, only `clean/` and `processed/` need regenerating, not a full re-extract.

**Schema application via an explicit column-name/dtype map, not inference.**
Both files are headerless with fixed column order per Freddie Mac's published data dictionary. Column names and dtypes are hardcoded in a schema module (one for origination, one for performance) rather than inferred, since inference on sentinel-laden, headerless pipe files is unreliable and the layout is fixed/known.

**Join key and target derivation.**
Origination and performance tables join on `Loan Sequence Number` (1:many, one origination row to many monthly performance rows). The modeling target is derived per loan from the performance history's `Current Loan Delinquency Status` and `Zero Balance Code` fields (e.g. flag a loan as "ever 90+ days delinquent" or terminated via foreclosure/REO), producing one label per loan rather than per monthly observation, alongside loan-level aggregated/derived features (current age, months since origination, worst delinquency observed, current UPB trend).

**Partitioning: by origination year and quarter.**
The final Parquet output is partitioned by the origination quarter (derived from the source file, e.g. `orig_year=2020/orig_quarter=Q1/`) so downstream consumers can filter/read subsets without scanning the whole dataset, and so future quarters can be appended without rewriting existing partitions.

**Sentinel handling is column-specific, defined in the cleaning stage.**
Freddie Mac uses different sentinel values per field (e.g. `999` for unknown credit score, blank for missing MSA, `9999` for unknown MI %). These are mapped to actual nulls per-column in the cleaning stage rather than a single blanket rule, since a global sentinel (e.g. "999") could be a legitimate value in another column.

## Risks / Trade-offs

- [Disk footprint: raw extraction + 4 pipeline stages could multiply the ~8.9 GB source into 40–60+ GB of intermediate data] → Use Parquet (columnar + compressed) from the `readable/` stage onward, and allow deleting `raw/` `.txt` files after the `readable/` stage succeeds if disk space is constrained.
- [Freddie Mac's column layout could differ slightly between vintages (e.g. added columns in later years)] → Verify column count (32) per file before applying the schema map at the `readable/` stage; the pipeline should fail loudly rather than silently misalign columns if a file doesn't match.
- [Wrong sentinel→null mapping silently corrupts data rather than erroring] → Document the exact sentinel table per column in the data dictionary and spot-check value distributions before/after cleaning during implementation.
- [Joining origination to full performance history for millions of loans is the most compute/memory-intensive step] → Use Polars lazy execution and process/join per origination-quarter partition rather than one all-quarters-at-once join.
- [Large ZIP extraction and Parquet conversion is time-consuming; iterating on cleaning/preprocessing logic against the full dataset each time is slow] → Build and validate the pipeline against the smallest quarter (2025Q3) first, then run the full 2020–2025Q3 range once logic is validated.

## Migration Plan

Not applicable — this is a new, standalone pipeline with no existing system to migrate from or roll back to. If a pipeline run produces bad output, the fix is to re-run the affected stage(s) after correcting the code, since each stage writes to its own directory without mutating prior stages.

## Open Questions

- Exact default/delinquency target definition (e.g. "ever 90+ days delinquent" vs. "ever 60+ days delinquent" vs. only foreclosure/REO) — a reasonable default (ever 90+ DPD or adverse zero-balance code) will be used unless refined during implementation.
- Whether to keep the full monthly performance panel available downstream (for time-series/survival modeling) in addition to the loan-level flat modeling table — current plan produces both the cleaned monthly performance table and the loan-level joined/labeled table.
