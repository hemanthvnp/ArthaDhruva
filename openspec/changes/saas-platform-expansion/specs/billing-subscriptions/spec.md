## ADDED Requirements

### Requirement: Subscription plan assignment
Every organization SHALL have exactly one active subscription plan at a time, determining its seat limit and rate-limit tier.

#### Scenario: New organization defaults to a starter plan
- **WHEN** a platform administrator provisions an organization from an approved access request without choosing a plan
- **THEN** it is assigned the trial (pilot) plan with a defined seat limit, rate-limit tier, and trial end date

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

### Requirement: Sales-assisted access requests
A prospective customer SHALL be able to submit a request for access, which SHALL NOT create an organization or user account by itself. Organizations SHALL be provisioned only by a platform administrator reviewing that request, matching how banks procure software (security review and pilot, not self-service card signup).

#### Scenario: Prospect submits an access request
- **WHEN** a prospective customer submits the request-access form with institution name, contact name, and a valid work email
- **THEN** a pending access request is recorded and no organization or user account is created

#### Scenario: Platform administrator approves a request
- **WHEN** a platform administrator approves a pending access request with an available organization slug and an admin username
- **THEN** the organization is created on the chosen plan (trial by default), its first ADMIN is created as an invited, not-yet-activated account, an activation link is issued to the requester's work email, and the request is marked approved with the reviewer and the organization it produced

#### Scenario: Invited admin activates
- **WHEN** the invited ADMIN sets a password through the activation link
- **THEN** no session is issued; the admin is directed to sign in, which requires two-factor enrollment like every ADMIN

#### Scenario: Platform administrator declines a request
- **WHEN** a platform administrator declines a pending access request
- **THEN** the request is marked declined with the reviewer, and no organization is created

#### Scenario: Request already reviewed
- **WHEN** a platform administrator approves or declines a request that is not pending
- **THEN** the action is rejected with a conflict error

### Requirement: Plan-tiered rate limiting
API rate limits SHALL scale with an organization's subscription plan rather than applying a single global limit.

#### Scenario: Higher-tier plan gets a higher rate limit
- **WHEN** two organizations on different plan tiers make API requests at the same rate
- **THEN** the organization on the higher tier is throttled at a higher request-per-second threshold than the lower tier
