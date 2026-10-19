## ADDED Requirements

### Requirement: A regional correlation estimate with its uncertainty
The system SHALL estimate, for the regions of the learned graph, the correlation between regions and the share of loan-level default risk that a regional factor explains beyond what the survival model already expects. Both SHALL be reported with a bootstrap interval.

#### Scenario: Estimated on out-of-time experience
- **WHEN** the estimate is computed
- **THEN** it uses the survival model's predictions for periods after that model's training window, aggregated by region and quarter, and not in-sample predictions

#### Scenario: A valid correlation matrix
- **WHEN** the correlation matrix between regions is written
- **THEN** it is symmetric, has a unit diagonal and is positive semi-definite

#### Scenario: Indistinguishable from zero
- **WHEN** the bootstrap interval for the regional share includes zero
- **THEN** the default recorded in the graph file is zero, and the card states that the estimate is not distinguishable from zero

### Requirement: The estimator recovers what it is asked to measure
The system SHALL show that the estimator recovers a known regional share on simulated portfolios before its output is written to the graph file.

#### Scenario: Parameter recovery
- **WHEN** the estimator is applied to portfolios simulated from the two-level factor model with a known regional share, at the sample size available in the real data
- **THEN** the known value lies inside the reported interval in at least 90% of 200 simulations

### Requirement: The estimates are part of the graph artifact
The system SHALL record the regional correlation matrix and the regional share in the graph file and its card, so that one checksum covers everything the loss simulation reads.

#### Scenario: Fields in the file
- **WHEN** the export runs with regional estimates available
- **THEN** the graph file contains `regionCorrelation` and `regionalShare` with the estimate, its interval and the default, and the card records the data period and the method
