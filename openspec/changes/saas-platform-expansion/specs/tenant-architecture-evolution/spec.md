## ADDED Requirements

### Requirement: Subdomain-based tenant resolution
The system SHALL support resolving an organization from the request's subdomain (e.g., `acme.arthadhruva.app`) as an alternative to the `orgSlug` login field, during a deprecation window where both are accepted.

#### Scenario: Login via subdomain
- **WHEN** a login request arrives at an organization-specific subdomain without an `orgSlug` field
- **THEN** the system resolves the organization from the subdomain and proceeds with authentication identically to the existing `orgSlug` flow

#### Scenario: Both resolution methods remain valid during the deprecation window
- **WHEN** a login request supplies both a matching subdomain and an `orgSlug`
- **THEN** the system accepts the request as long as they resolve to the same organization, and rejects it if they conflict

### Requirement: Tenant-uploadable loan portfolios
An organization admin SHALL be able to upload their own loan portfolio data, which is then used in place of the shared static demo catalog for that organization's scoring and browsing workflows.

#### Scenario: Organization uploads its own portfolio
- **WHEN** an admin uploads a valid loan portfolio file
- **THEN** that organization's loan catalog views and scoring-by-ID workflows use the uploaded data instead of the shared demo catalog

#### Scenario: Other organizations are unaffected
- **WHEN** one organization uploads its own portfolio
- **THEN** organizations that have not uploaded their own data continue to see the shared demo catalog unchanged

### Requirement: Postgres Row-Level Security as defense-in-depth
The system SHALL enforce tenant isolation at the database level via Postgres Row-Level Security policies on every tenant-scoped table, in addition to the existing application-level explicit-parameter and Hibernate-filter guards.

#### Scenario: RLS blocks a query missing tenant context
- **WHEN** a database query executes against a tenant-scoped table without the session's tenant context set
- **THEN** Postgres returns zero rows for that query regardless of what the query itself requested

### Requirement: Model-serving extraction evaluation
The system SHALL document a concrete evaluation of extracting ML model-serving (`score`, `earlywarning`, `trajectory`) into an independently-deployable service, including the criteria that would justify doing so.

#### Scenario: Evaluation document exists and is decision-ready
- **WHEN** the evaluation is complete
- **THEN** it states specific, measurable load/latency criteria under which extraction would be undertaken, rather than a general recommendation without thresholds
