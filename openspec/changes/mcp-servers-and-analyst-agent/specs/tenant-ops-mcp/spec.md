## ADDED Requirements

### Requirement: Read-only by construction
The server SHALL expose no write tools, SHALL run all database access inside read-only transactions, and SHALL never connect as the worker role.

#### Scenario: Write attempt
- **WHEN** any code path attempts an INSERT, UPDATE, DELETE or DDL
- **THEN** the database rejects it because the transaction is read-only

#### Scenario: Wrong role
- **WHEN** the configured database user is the worker role
- **THEN** the server refuses to start with a clear message

### Requirement: Tenant context set before every query
Before any query the server SHALL set `app.tenant_id` to the session's fixed tenant using the same mechanism as the application, and a query MUST return no tenant rows when the value is empty.

#### Scenario: Tenant isolation
- **WHEN** a tool runs for tenant A while tenant B has rows in the same table
- **THEN** no tenant B row appears in the result

#### Scenario: Pool reuse
- **WHEN** two calls for different tenants reuse one pooled connection
- **THEN** the second call sees only its own tenant's rows

### Requirement: No free-form SQL
All queries SHALL be fixed and parameterised, with allowlisted filter columns and bounded limits, and no tool SHALL accept SQL text.

#### Scenario: Unknown filter column
- **WHEN** a caller filters on a column not in the allowlist
- **THEN** the tool returns `InvalidInput` without executing a query

### Requirement: Audit and usage via the API
Audit-log and usage queries SHALL be served through `/admin/audit-log` and `/admin/usage`, not by direct table access.

#### Scenario: Audit query
- **WHEN** `audit_query` is called with a time range and actor
- **THEN** the server calls `/admin/audit-log` and returns the page with its pagination state

### Requirement: Run diagnosis
`run_detail` SHALL report a portfolio run's state, heartbeat age, and whether the worker appears dead, using a documented staleness threshold.

#### Scenario: Stale heartbeat
- **WHEN** a run's heartbeat is older than the threshold and its state is still running
- **THEN** the result flags "worker appears dead" and shows the heartbeat age

### Requirement: Untrusted-data fencing
Text written by tenants (audit details, loan notes, webhook names) SHALL be marked as untrusted in structured output and wrapped in fences in text output.

#### Scenario: Instruction-like note
- **WHEN** a loan note contains "ignore previous instructions"
- **THEN** the note appears inside an untrusted-data fence with a flag, never as bare text
