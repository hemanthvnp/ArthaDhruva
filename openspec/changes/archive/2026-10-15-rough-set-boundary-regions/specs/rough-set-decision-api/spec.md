## ADDED Requirements

### Requirement: Additive decision block on projection requests
`POST /risk/term-structure` and `POST /risk/term-structure/compare` SHALL accept an optional `decision` object with `metric`, `threshold`, optional `tolerance` (default 0), and optional `granule` (`kind`, `members`, `shockBand`, `timingBandMonths`, `scenarios` for the scenario-set kind, `regimeDraws`), plus an optional `seed`. A request without `decision` SHALL produce a response identical to today's.

#### Scenario: No decision block
- **WHEN** a term-structure request omits `decision`
- **THEN** the response has no `decision` field and every existing field is unchanged

#### Scenario: Defaults applied and echoed
- **WHEN** a request gives only a metric and a threshold
- **THEN** the response echoes the tolerance, granule kind, member count, bands and seed that were used

### Requirement: Decision block in responses
When a `decision` was requested, the response SHALL include a `decision` block holding the region, `k`, `n`, the tolerance, the metric's minimum, median and maximum, the width, `extrapolated` with its driver list, the seed used, the model version, and the fixed method label "rough-set three-way decision over a seeded perturbation granule; not a probability and not a coverage-guaranteed interval" (or its scenario-set variant).

#### Scenario: Label is always present
- **WHEN** any response carries a `decision` block
- **THEN** the method label is present and non-empty

#### Scenario: Compare endpoint classifies each scenario
- **WHEN** the compare endpoint is called with a `decision`
- **THEN** each scenario curve carries its own region and spread, computed with its own granule

### Requirement: Validation and error shape
Invalid decision options SHALL return HTTP 422 with an `error` message naming the field, in the same shape as other projection errors, and SHALL NOT partially run the projection.

#### Scenario: Bad member count
- **WHEN** `members` exceeds the single-loan cap
- **THEN** the response is 422 naming `members` and the cap

#### Scenario: Both custom scenario and scenario name
- **WHEN** a request that already violates the existing scenario rule also carries a `decision`
- **THEN** the existing error is returned unchanged

### Requirement: Metering and audit
A request with a `decision` SHALL be metered under the existing term-structure unit and SHALL additionally record a number of `DECISION_MEMBER` units equal to the granule size, and SHALL record the model version in the audit context. The decision parameters (metric, threshold, tolerance, granule kind, members, seed) SHALL be echoed in the response.

#### Scenario: Cost scales with members
- **WHEN** a decision request uses 64 members
- **THEN** 64 `DECISION_MEMBER` units are recorded, against 8 for the same request with 8 members

### Requirement: Existing model contract untouched
The system SHALL leave the survival model artifact, its card, its checksum and the term-structure golden output unchanged by this capability.

#### Scenario: Checksum and golden unchanged
- **WHEN** the existing card-checksum and golden tests run after this change
- **THEN** they pass without any edit to their expected values

### Requirement: Portfolio endpoints accept the decision block
`POST /risk/portfolio/runs` SHALL accept the same `decision` object (with the portfolio member cap), and `GET /risk/portfolio/loans` SHALL accept a `region` filter, both optional and backward compatible.

#### Scenario: Region filter absent
- **WHEN** the loan listing is requested without `region`
- **THEN** the result is the same as today's
