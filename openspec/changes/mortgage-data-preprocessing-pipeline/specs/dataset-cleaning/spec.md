## ADDED Requirements

### Requirement: Normalize sentinel values to null
The system SHALL replace each column's documented sentinel/placeholder values (e.g. blank strings, `999`/`9999` unknown codes) with true nulls, using a per-column mapping rather than a single global sentinel value.

#### Scenario: Sentinel credit score is nulled
- **WHEN** the cleaning step processes an origination row where `credit_score` is a documented "unknown" sentinel value
- **THEN** the cleaned row's `credit_score` is null, not the sentinel value

#### Scenario: A legitimate value matching another column's sentinel is preserved
- **WHEN** the cleaning step processes a column where the value equals another column's sentinel but is a documented valid value for this column
- **THEN** the value is preserved, not incorrectly nulled

### Requirement: Enforce column data types
The system SHALL ensure every column in the cleaned origination and performance tables matches its documented type (numeric, date, categorical/string), converting parseable values and nulling values that cannot be parsed as the expected type.

#### Scenario: Date column parses correctly
- **WHEN** the cleaning step processes a `YYYYMM`-encoded date column
- **THEN** the cleaned output stores it as a proper date/period type, not a raw integer

### Requirement: Remove duplicate records
The system SHALL deduplicate origination records by `loan_sequence_number` and deduplicate performance records by the combination of `loan_sequence_number` and `monthly_reporting_period`.

#### Scenario: Duplicate origination row is removed
- **WHEN** the same `loan_sequence_number` appears more than once in an origination file
- **THEN** the cleaned origination table retains exactly one record for that loan sequence number

#### Scenario: Duplicate performance row is removed
- **WHEN** the same `loan_sequence_number` and `monthly_reporting_period` pair appears more than once
- **THEN** the cleaned performance table retains exactly one record for that pair

### Requirement: Validate cleaned row counts
The system SHALL report, for each quarter, the row counts before and after cleaning (nulls introduced, duplicates removed) so data loss is visible rather than silent.

#### Scenario: Cleaning summary is produced
- **WHEN** the cleaning step finishes processing a quarter's origination and performance tables
- **THEN** a summary reporting input row count, output row count, and duplicate rows removed is available for that quarter
