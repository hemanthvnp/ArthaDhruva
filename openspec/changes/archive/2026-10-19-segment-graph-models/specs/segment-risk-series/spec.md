## ADDED Requirements

### Requirement: A standardised delinquency series for every state
The system SHALL provide, for each of the 50 states and the District of Columbia, a monthly series of how much more or less delinquent that state's mortgage book is than the rest of the country's would be on the same loan mix. The series SHALL be built from the full loan-level panel, not from a sample.

#### Scenario: Building the cell counts
- **WHEN** the panel build runs over the monthly performance panel joined to the loan-level table
- **THEN** it writes, for each state, month, loan-age bucket, credit-score band and LTV band, the number of active loans, the number 90 or more days past due or in REO, and how many of those are flagged for disaster or for borrower assistance

#### Scenario: Expected counts leave the state out
- **WHEN** the expected number of delinquent loans is computed for a state and month
- **THEN** each cell's national delinquency rate excludes that state's own loans, so that a large state is not compared with a rate it largely determines

#### Scenario: Thin months are not used
- **WHEN** a state's expected number of delinquent loans in a month is below 20
- **THEN** the ratio for that state and month is missing instead of computed, and a coverage table records the share of months present for every state

#### Scenario: Interrupted builds resume
- **WHEN** the build is interrupted and run again
- **THEN** only the slices that were not completed are recomputed, and the final output is identical to that of an uninterrupted run

### Requirement: Sensitivity variants of the series
The system SHALL also provide the series with disaster-flagged delinquencies excluded and with borrower-assistance-flagged delinquencies excluded, so that results can be checked for dependence on regional shocks and on forbearance.

#### Scenario: Variants are built from the same counts
- **WHEN** the series are derived from the cell counts
- **THEN** the primary series and both variants are written together, from the same pass over the panel

### Requirement: A macro panel for learning, with the national movement removed
The system SHALL provide, for each state, a monthly series of the change in the unemployment rate and a quarterly series of house-price growth from quarter-end values, from 1995 to the latest month available, with the national movement removed and the result standardised.

#### Scenario: National movement removed
- **WHEN** the macro panel is built
- **THEN** each state's series is the residual of a regression on the national series, and the share of variance explained by the first principal component of the residual panel is recorded

#### Scenario: A place without its own series
- **WHEN** a place's published series is missing and was replaced by the national one
- **THEN** that place is listed and left out of learning

### Requirement: Builds that cannot be trusted stop
The build scripts SHALL stop, with a message that names the problem, instead of writing output that cannot be trusted.

#### Scenario: Corrupted or incomplete input
- **WHEN** a state is missing, a key of state, month and cell appears twice, or a series is empty
- **THEN** the script exits with a non-zero status and writes no output
