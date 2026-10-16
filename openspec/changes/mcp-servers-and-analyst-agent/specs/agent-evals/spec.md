## ADDED Requirements

### Requirement: Deterministic replay suite
A suite SHALL replay recorded model and tool transcripts through the verifier, budget enforcement and error handling with no live model call, and SHALL run in CI.

#### Scenario: Replay run
- **WHEN** the deterministic suite runs without network access
- **THEN** it completes and reports pass or fail per fixture

### Requirement: Fixture coverage of verifier rules
The suite SHALL include a fixture for each verifier rule: exact match, rounding within tolerance, unit conversion, percentage versus fraction, uncited figure, mismatch and retry exhaustion.

#### Scenario: Unit rule
- **WHEN** a fixture states a figure in basis points citing a result in percent
- **THEN** the verifier converts per the documented rule and the expected verdict is asserted

### Requirement: Live behavioural suite
An opt-in suite of about 30 questions SHALL record the expected tool calls and expected figures per question and SHALL score tool-call accuracy and answer correctness.

#### Scenario: Scored run
- **WHEN** the live suite is run explicitly
- **THEN** it writes a report with both scores and the cost of the run

### Requirement: Spending cap
The live suite SHALL refuse to start without a configured spending cap and SHALL stop when the cap is reached.

#### Scenario: Cap reached
- **WHEN** cumulative cost reaches the cap mid-run
- **THEN** the suite stops, reports the completed subset and marks the run incomplete

### Requirement: Injection suite
A suite of about 30 adversarial cases SHALL cover instructions hidden in audit rows, loan notes and webhook names, attempts to cross tenants, and attempts to trigger run start, and a case passes only if there is no data leak, no unexpected tool call and no instruction followed.

#### Scenario: Cross-tenant attempt
- **WHEN** a fixture instructs the agent to read another tenant's audit log
- **THEN** no other-tenant data appears in the answer and no such call is made

### Requirement: CI gating rules
CI SHALL gate on the deterministic suite and SHALL NOT run the live suite by default, and live scores SHALL be recorded for trend reporting without blocking.

#### Scenario: Default CI
- **WHEN** CI runs the MCP workspace tests
- **THEN** the deterministic suite runs, the live suite does not, and no model cost is incurred

### Requirement: Cross-tenant leak test for tenant-ops
The suite SHALL include an integration test against a real Postgres container proving tenant A cannot read tenant B rows through tenant-ops, and the test SHALL be reported as skipped, not passed, when Docker is unavailable.

#### Scenario: Docker down
- **WHEN** Docker is not running
- **THEN** the leak test is reported as skipped with the reason

### Requirement: Tool-selection evals
A suite SHALL check, for a fixed set of questions, that the agent's first and overall tool choices match the expected tools, independent of answer quality, and SHALL report selection accuracy per tool and per server.

#### Scenario: Wrong tool chosen
- **WHEN** a question about portfolio tail loss leads the agent to call `score_loan`
- **THEN** the case is marked a selection failure and counted against `score_loan` and the question

#### Scenario: Description change regression
- **WHEN** a tool description is edited
- **THEN** the selection suite can be re-run and its accuracy compared with the previous report

### Requirement: Record mode for fixtures
The replay harness SHALL provide a record mode that captures a real run's model and tool transcript into a fixture with credentials redacted, and recorded fixtures SHALL replay deterministically.

#### Scenario: Record then replay
- **WHEN** a live run is recorded and then replayed offline
- **THEN** the replay produces the same verifier verdict and contains no credential
