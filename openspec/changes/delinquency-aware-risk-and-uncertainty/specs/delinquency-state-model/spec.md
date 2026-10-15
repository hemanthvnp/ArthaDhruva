## ADDED Requirements

### Requirement: Multi-state outcome definition
The model SHALL describe each loan-month by one of the states current, 30 days past due, 60 days past due, default or prepaid, where default and prepayment are absorbing and default follows the existing definition (90+ days past due, REO, or an adverse zero-balance termination).

#### Scenario: Default definition unchanged
- **WHEN** a loan reaches 90 or more days past due, or ends in an adverse zero-balance termination
- **THEN** its training outcome is default, exactly as in the existing survival dataset

#### Scenario: Cure
- **WHEN** a loan moves from 30 days past due to current
- **THEN** the transition is recorded as a cure and the loan remains active

### Requirement: Transition probabilities conditioned on state and history
For each active source state the model SHALL output a probability for every reachable next state, conditioned on the current state, the history features, the loan's origination attributes and the macro drivers, and the probabilities from each source state SHALL sum to 1.

#### Scenario: Probabilities sum to one
- **WHEN** the model scores any loan-month
- **THEN** the next-state probabilities for its source state sum to 1 within 1e-9

### Requirement: Leakage-free history features
History features (current state, months since last cure, count of 30+ episodes in the prior 12 months, maximum state in that window) SHALL be computed only from months strictly before the month being predicted, and an automated test SHALL fail if any feature changes when future months are altered.

#### Scenario: Future altered
- **WHEN** performance records after month t are modified
- **THEN** every feature value for month t is unchanged

### Requirement: Additive artifact
The model SHALL ship as its own artifact with its own card and checksum, and the existing survival model's artifact, card, checksum and golden outputs SHALL be unchanged by this capability, measured against the baseline recorded in the change's baseline note (`baseline.md`), not against any earlier release.

#### Scenario: Existing model untouched
- **WHEN** this change is applied
- **THEN** the existing survival artifact checksum equals its previous value and `TermStructureGoldenTest` passes without regenerating its fixtures

### Requirement: Out-of-time validation protocol
The model SHALL be fitted on loan-months before 2023-01 and evaluated on all later months, using the hash-of-loan-id split, excluding the last month of each data release, and keeping each loan's months on one side of every split.

#### Scenario: Split integrity
- **WHEN** the split is built
- **THEN** no loan has months in both a fitting and an evaluation set

### Requirement: Validation gates
The model SHALL pass the gates frozen before the production fit: among delinquent loan-months its out-of-time log loss is lower than the existing survival model's; predicted over realized 12-month default and cure rates by starting state are within the frozen tolerance; and the projection is monotone, so a worse starting state never has lower lifetime PD with all else equal.

#### Scenario: Gate failure
- **WHEN** any gate fails on the production fit
- **THEN** the export refuses to write the artifact and reports which gate failed

#### Scenario: Monotonicity grid
- **WHEN** a grid of loans is projected from current, 30 and 60 days past due
- **THEN** lifetime PD is non-decreasing in the starting state for every loan on the grid

### Requirement: Benchmarks in the card
The card SHALL report the new model, the existing survival model and a roll-rate transition table on the same out-of-time loan-months, with the count of events behind each reported rate.

#### Scenario: Card content
- **WHEN** the card is generated
- **THEN** it lists log loss and calibration by starting state for all three, with event counts

### Requirement: Stated limitations
The card SHALL state the sparse-data limits of deep-delinquency states, that cure behaviour reflects 2017 to 2026 (including forbearance), that the data contains no housing downturn, and the product scope (agency-conforming fixed-rate).

#### Scenario: Limitations present
- **WHEN** the card is read at `GET /v1/models/{id}`
- **THEN** the limitations above appear in its text

### Requirement: Reproducible export with checksum enforcement
The export SHALL be bit-for-bit reproducible from the same data, and the service SHALL refuse to start if the artifact's checksum differs from its card.

#### Scenario: Tampered artifact
- **WHEN** the artifact file is altered
- **THEN** service start-up fails with a checksum mismatch naming the artifact

### Requirement: Owner-run training only
The dataset build and export SHALL run only when the owner explicitly asks, and SHALL NOT be triggered by tests or tooling.

#### Scenario: Test run
- **WHEN** the backend test suite runs
- **THEN** no dataset build or export is started
