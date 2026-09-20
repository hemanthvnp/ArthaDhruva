## ADDED Requirements

### Requirement: Subscription plan assignment
Every organization SHALL have exactly one active subscription plan at a time, determining its seat limit and rate-limit tier.

#### Scenario: New organization defaults to a starter plan
- **WHEN** an organization is created through the self-service signup flow
- **THEN** it is assigned a default trial or starter plan with a defined seat limit and rate-limit tier

### Requirement: Seat limit enforcement
The system SHALL prevent an organization from provisioning more user accounts than its plan's seat limit allows.

#### Scenario: Admin attempts to exceed seat limit
- **WHEN** an organization admin attempts to create a new user account that would exceed the plan's seat limit
- **THEN** the request is rejected with an error identifying the seat limit and current usage

### Requirement: Usage metering
The system SHALL record billable usage events (e.g., scoring calls, active seats) per organization per billing period.

#### Scenario: Scoring call is metered
- **WHEN** an organization's user successfully scores a loan
- **THEN** a usage record is incremented for that organization's current billing period

### Requirement: Self-service signup and trial
A prospective customer SHALL be able to create a new organization and trial account without administrator provisioning.

#### Scenario: Successful self-service signup
- **WHEN** a prospective customer completes the signup form with a unique organization slug and valid email
- **THEN** a new organization is created on a trial plan and the signing-up user becomes its first admin

### Requirement: Plan-tiered rate limiting
API rate limits SHALL scale with an organization's subscription plan rather than applying a single global limit.

#### Scenario: Higher-tier plan gets a higher rate limit
- **WHEN** two organizations on different plan tiers make API requests at the same rate
- **THEN** the organization on the higher tier is throttled at a higher request-per-second threshold than the lower tier
