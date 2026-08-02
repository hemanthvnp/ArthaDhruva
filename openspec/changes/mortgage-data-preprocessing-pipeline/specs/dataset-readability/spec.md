## ADDED Requirements

### Requirement: Apply readable column names and data types
The system SHALL convert each raw, headerless origination file and performance file into a readable Parquet table by applying the Freddie Mac origination schema (32 named, typed columns) and performance schema (32 named, typed columns) respectively.

#### Scenario: Origination file conversion
- **WHEN** the readability step processes a raw origination file
- **THEN** it produces a Parquet table with 32 named columns (e.g. `credit_score`, `first_payment_date`, `loan_sequence_number`, `original_upb`, `original_interest_rate`, ...) matching the Freddie Mac origination data dictionary, with each column cast to its documented type (integer, float, string, or date)

#### Scenario: Performance file conversion
- **WHEN** the readability step processes a raw performance file
- **THEN** it produces a Parquet table with 32 named columns (e.g. `loan_sequence_number`, `monthly_reporting_period`, `current_actual_upb`, `current_loan_delinquency_status`, `zero_balance_code`, ...) matching the Freddie Mac performance data dictionary, with each column cast to its documented type

### Requirement: Reject files with an unexpected layout
The system SHALL refuse to apply a schema to a raw file whose column count does not match the expected 32 columns, rather than silently mis-mapping column names.

#### Scenario: Unexpected column count
- **WHEN** the readability step encounters a raw file with a column count other than 32
- **THEN** it stops processing that file and reports an error identifying the file, the expected column count, and the actual column count found

### Requirement: Persist a data dictionary
The system SHALL produce a human-readable data dictionary documenting every origination and performance column: its name, source column position, data type, and meaning.

#### Scenario: Data dictionary is generated
- **WHEN** the readability step completes for at least one quarter
- **THEN** a data dictionary file exists listing all 32 origination columns and all 32 performance columns with name, type, and description
