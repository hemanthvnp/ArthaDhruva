## ADDED Requirements

### Requirement: Join origination and performance records
The system SHALL join each cleaned origination record to its full monthly performance history using `loan_sequence_number` as the join key.

#### Scenario: Loan with performance history is joined
- **WHEN** the preprocessing step processes a loan sequence number present in both the cleaned origination table and the cleaned performance table
- **THEN** the joined output associates that loan's origination attributes with every one of its monthly performance records

#### Scenario: Loan missing performance history is flagged, not dropped silently
- **WHEN** a loan sequence number exists in the origination table but has no matching performance records
- **THEN** the loan is retained in the join output with its origination attributes and null/empty performance-derived fields, and is counted in the join summary

### Requirement: Derive a loan-level default/delinquency target
The system SHALL derive one default/delinquency label per loan from its performance history, flagging a loan as in default if it was ever reported 90+ days delinquent or reached an adverse zero-balance outcome (e.g. foreclosure, third-party sale, or REO disposition).

#### Scenario: Loan that becomes 90+ days delinquent is labeled default
- **WHEN** a loan's performance history contains any monthly record with `current_loan_delinquency_status` indicating 90 or more days delinquent
- **THEN** that loan's derived target is "default"

#### Scenario: Loan that pays off with no adverse history is labeled non-default
- **WHEN** a loan's performance history never shows 90+ days delinquent and its terminal zero-balance code (if any) indicates a non-adverse payoff
- **THEN** that loan's derived target is "non-default"

### Requirement: Engineer loan-level risk features
The system SHALL compute standard loan-level risk features from the joined data, including current loan age, months since origination, worst (maximum) delinquency status observed, and current unpaid principal balance trend.

#### Scenario: Feature computation for an active loan
- **WHEN** the preprocessing step computes features for a loan with performance history
- **THEN** the output includes that loan's current age in months, its worst observed delinquency status, and its most recent current UPB

### Requirement: Produce a partitioned, modeling-ready dataset
The system SHALL write the final loan-level dataset (origination attributes, derived target, engineered features) as Parquet, partitioned by origination year and quarter.

#### Scenario: Output is partitioned by origination quarter
- **WHEN** the final dataset is written
- **THEN** records originated in a given year/quarter are stored under a partition path identifying that origination year and quarter (e.g. `orig_year=2020/orig_quarter=Q1/`)

### Requirement: Retain the cleaned monthly performance panel
The system SHALL also persist the cleaned, joined monthly performance panel (one row per loan per month) separately from the loan-level flat modeling table, so time-series/survival-style downstream use remains possible.

#### Scenario: Monthly panel is available alongside the loan-level table
- **WHEN** preprocessing completes
- **THEN** both the loan-level modeling table and the full monthly performance panel exist as distinct output datasets
