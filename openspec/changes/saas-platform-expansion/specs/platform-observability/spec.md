## ADDED Requirements

### Requirement: Domain events for cross-cutting side effects
State changes that trigger multiple independent side effects (notifications, automation rules, webhooks) SHALL be published as domain events via an in-process event bus rather than invoked as direct method calls between services.

#### Scenario: New listener does not require modifying the publisher
- **WHEN** a new listener is added for an existing domain event (e.g., a webhook dispatcher listening for `LoanCaseUpdatedEvent`)
- **THEN** the code that publishes the event requires no modification

#### Scenario: One listener's failure does not affect another
- **WHEN** one event listener throws an exception while handling an event
- **THEN** other listeners for the same event still execute, and the original request that triggered the event still completes successfully

### Requirement: Automated CI pipeline
Every push SHALL automatically run the full backend and frontend test suites against disposable, containerized dependencies, without requiring a developer's local environment.

#### Scenario: CI runs backend tests against Testcontainers
- **WHEN** a change is pushed
- **THEN** the CI pipeline provisions disposable Postgres/Redis containers, runs the full backend test suite against them, and reports pass/fail without any manually-managed local database

### Requirement: Structured logging with request correlation
Every log line produced while handling a single request SHALL include a shared correlation ID, and log output SHALL be structured (JSON), not freeform text.

#### Scenario: Multi-service request is traceable
- **WHEN** a single request causes log output from more than one internal service call
- **THEN** every resulting log line carries the same correlation ID, allowing them to be grouped in log tooling

### Requirement: Metrics and tracing exposure
The system SHALL expose request-level metrics and distributed traces to a self-hosted observability stack.

#### Scenario: Latency is queryable per endpoint
- **WHEN** the observability stack is queried for a specific endpoint's latency
- **THEN** it returns per-endpoint latency metrics collected from live traffic

### Requirement: Fail-fast secret validation in production
When running under a `production` profile, the system SHALL refuse to start if any required secret (JWT signing key, TOTP encryption key, etc.) is unset, rather than generating a random value.

#### Scenario: Missing secret in production profile
- **WHEN** the application starts with the `production` profile active and `JWT_SECRET` is unset
- **THEN** startup fails with an explicit error identifying the missing secret

#### Scenario: Local/dev profile keeps existing convenience fallback
- **WHEN** the application starts without the `production` profile active and `JWT_SECRET` is unset
- **THEN** startup succeeds with a randomly generated key, matching current behavior

### Requirement: API versioning
All API endpoints SHALL be reachable under a versioned path prefix, allowing future breaking changes to be introduced without affecting existing integrations.

#### Scenario: Versioned path is authoritative
- **WHEN** a client calls an endpoint under the `/v1/` prefix
- **THEN** the response shape is governed by that version's contract regardless of future unversioned changes

### Requirement: Reverse proxy for TLS, compression, and static assets
The system SHALL be deployed behind a reverse proxy that terminates TLS, serves the built frontend as static files, and compresses API responses.

#### Scenario: Frontend is served without the application server
- **WHEN** a user requests the frontend application
- **THEN** the reverse proxy serves the built static assets directly, without a request reaching the backend application server
