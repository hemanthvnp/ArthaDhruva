## ADDED Requirements

### Requirement: Persisted results reference the model that produced them
The system SHALL record, on every persisted score, every audit event of a model invocation and every stored portfolio result, the id and version of the model that produced it.

#### Scenario: A new score is stamped
- **WHEN** a loan is scored and the result is persisted
- **THEN** the stored row's `model_version` is the id and version of the model that computed it (for example `pd_24m@2026.10.01-1308`)

#### Scenario: A model invocation is audited
- **WHEN** a request that invokes a model is written to the audit trail
- **THEN** the audit event records the version of the model that answered

#### Scenario: A portfolio run is stored
- **WHEN** a portfolio risk run stores its results
- **THEN** each stored snapshot records the version of the survival model that produced it

#### Scenario: Results from before version tracking
- **WHEN** a score or audit event was persisted before this change
- **THEN** its `model_version` is null, which means "predates version tracking", and reading it succeeds

### Requirement: Responses name the model
The system SHALL return, with every score and expected-loss figure it computes, the version of the model behind it.

#### Scenario: Scoring a loan
- **WHEN** a client scores a loan
- **THEN** the response includes the model version, and the card for that version is retrievable from the model inventory

#### Scenario: Exporting stored scores
- **WHEN** a client exports stored scores as CSV
- **THEN** each row includes the model version that produced it
