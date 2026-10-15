## ADDED Requirements

### Requirement: Extract quarterly ZIP archives
The system SHALL extract every quarterly ZIP archive found under `historical_data_20{20..25}/` into a raw output directory, preserving the origination file and performance ("time") file for each quarter.

#### Scenario: Extracting a single quarter
- **WHEN** the extraction step processes `historical_data_2025/historical_data_2025Q3.zip`
- **THEN** it writes `historical_data_2025Q3.txt` and `historical_data_time_2025Q3.txt` to the raw output directory, unmodified from their zipped contents

#### Scenario: Extracting all available quarters
- **WHEN** the extraction step runs with no quarter filter
- **THEN** every ZIP found across all `historical_data_20{20..25}/` folders is extracted, and the raw output directory contains one origination file and one performance file per quarter processed

### Requirement: Validate extracted file structure
The system SHALL verify, for each extracted quarter, that the origination file has 32 pipe-delimited columns and the performance file has 32 pipe-delimited columns before treating extraction as successful.

#### Scenario: Column count mismatch is detected
- **WHEN** an extracted file's first row does not split into exactly 32 pipe-delimited fields
- **THEN** the extraction step reports an error identifying the offending file and quarter, and does not mark that quarter as successfully extracted

### Requirement: Extraction is resumable
The system SHALL skip re-extracting a quarter whose raw output files already exist, unless a force/overwrite option is explicitly given.

#### Scenario: Re-running extraction after a prior successful run
- **WHEN** the extraction step is run again and raw output files for a quarter already exist
- **THEN** that quarter's ZIP is not re-extracted and the existing raw files are left untouched
