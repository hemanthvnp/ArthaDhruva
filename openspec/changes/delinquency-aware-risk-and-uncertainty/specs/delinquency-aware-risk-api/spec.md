## ADDED Requirements

### Requirement: Optional delinquency fields
The term-structure, expected-loss and portfolio endpoints SHALL accept optional fields for a loan's current delinquency status and its recent history, and a request without them SHALL behave exactly as it does today.

#### Scenario: Old client
- **WHEN** a request omits the new fields
- **THEN** the response is identical in meaning to the previous behaviour, with no error

### Requirement: Validation of delinquency input
The API SHALL reject a status outside the supported set, a negative or impossible history (for example more 30+ episodes than months observed), and a status of 90+ days past due on endpoints that project forward from a performing state, with a clear message naming the field.

#### Scenario: Impossible history
- **WHEN** a request states 5 episodes of 30+ days past due in the last 3 months
- **THEN** the API returns 400 naming the history field

### Requirement: Explicit fallback
When delinquency data is missing, incomplete or unusable, the API SHALL fall back to the existing engine and SHALL report in the response which model ran and why the fallback occurred.

#### Scenario: Incomplete history
- **WHEN** the status is supplied but required history fields are not
- **THEN** the response uses the existing engine and states "history incomplete, delinquency model not used"

### Requirement: Response reports the model and intervals
Responses SHALL include `modelUsed`, the interval object (bounds, method label, draws, vintages behind the band) for lifetime PD and expected loss, and the out-of-range months, as additive fields.

#### Scenario: Additive shape
- **WHEN** a client written against the previous response parses a new response
- **THEN** all previously present fields keep their names, types and meaning

### Requirement: Portfolio integration
Portfolio runs SHALL accept per-loan delinquency data where the upload provides it, SHALL route each loan to the appropriate engine, and SHALL report how many loans used each model.

#### Scenario: Mixed portfolio
- **WHEN** a portfolio has some loans with delinquency data and some without
- **THEN** the run result states the count per model and the totals equal the sum of the loans

### Requirement: Tenant isolation, audit, metering and limits unchanged
The new fields SHALL pass through the same tenant scoping, audit entries, metering and rate limits as the existing endpoints, and the audit entry SHALL record the model version used.

#### Scenario: Audit record
- **WHEN** a request is served by the delinquency model
- **THEN** the audit entry records that model's identifier and version

### Requirement: Model card and inventory entry
The new model SHALL appear in `GET /v1/models` and `GET /v1/models/{id}` with its card, checksum, validation, benchmarks and limitations, consistent with the model governance format.

#### Scenario: Inventory
- **WHEN** a client lists models
- **THEN** the delinquency model appears with its tier, validated status and checksum

### Requirement: Bounded request cost
The API SHALL cap the number of interval draws and the portfolio size eligible for intervals per request, and SHALL state the cap in the response when it applies.

#### Scenario: Cap applied
- **WHEN** a portfolio exceeds the interval cap
- **THEN** the response states that intervals were limited and why
