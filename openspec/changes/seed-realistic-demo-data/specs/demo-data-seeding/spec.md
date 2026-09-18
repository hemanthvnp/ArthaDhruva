## ADDED Requirements

### Requirement: API-driven provisioning only
The seeding script SHALL provision every user, loan link, and computed score exclusively by calling `risk-engine`'s existing public HTTP endpoints as an authenticated ADMIN. It SHALL NOT write directly to Postgres, Redis, or Neo4j.

#### Scenario: Seeded data is indistinguishable from admin-created data
- **WHEN** the seeding script creates a user, links a loan, or produces a score
- **THEN** the resulting row is one that `AdminUserController`, `ScoreController`, or the equivalent existing controller could have produced for a real admin performing the same action through the frontend

### Requirement: Real, mixed-risk loan sampling
The seeding script SHALL select loan feature values for every seeded loan directly from the already-processed dataset (`data/processed/loan_level`), using the values as found. It SHALL NOT fabricate or hand-tune feature values. The sample SHALL include at least one clearly low-risk loan (high credit score, low LTV/DTI) and at least one clearly higher-risk loan (lower credit score, high LTV/DTI), across more than one `property_state`.

#### Scenario: Sampled loans show visible contrast
- **WHEN** the seeding script selects its sample of loans
- **THEN** the sample includes loans whose original feature values differ meaningfully in credit score and LTV/DTI, drawn from more than one state, rather than N near-identical loans

### Requirement: Idempotent re-run
The seeding script SHALL be safe to run more than once against the same instance. When a username the script would create already exists, the script SHALL treat that identity as already seeded (skip the create step) and continue provisioning the rest of that identity's state and the remaining identities, rather than aborting the run.

#### Scenario: Fresh instance
- **WHEN** the seeding script runs against an instance with no prior seeded accounts
- **THEN** every configured ANALYST and CLIENT account is created, activated/enrolled, and left usable

#### Scenario: Already-seeded instance
- **WHEN** the seeding script runs again against an instance where the same deterministic usernames already exist
- **THEN** the script does not error out or attempt to duplicate those accounts, and still completes any remaining provisioning steps for other identities

### Requirement: Fully-usable seeded accounts
By the end of a successful run, every seeded ANALYST account SHALL have completed TOTP enrollment (not left in `setupRequired` state), and every seeded CLIENT account SHALL be activated with a known working password (not left pending).

#### Scenario: Seeded analyst logs in without extra setup
- **WHEN** an operator logs in as a seeded ANALYST username with the demo password and a TOTP code generated from the printed secret
- **THEN** login succeeds and returns a normal session token, with no `setupRequired` response

#### Scenario: Seeded client logs in immediately
- **WHEN** an operator logs in as a seeded CLIENT username with the printed demo password
- **THEN** login succeeds immediately, with no "Account not yet activated" error

### Requirement: Genuine computed scores for seeded loans
For every loan attached to a seeded CLIENT, the seeding script SHALL invoke the real scoring endpoint (with that loan's `loanId`) so that a durable, calibrated score exists for it before the run completes.

#### Scenario: Client's loan already has a score
- **WHEN** a seeded CLIENT logs in and requests their own loans after seeding completes
- **THEN** the returned view includes a non-null calibrated probability computed by the real PD model, not an empty/unscored placeholder

### Requirement: Login-ready end-of-run summary
The seeding script SHALL print, at the end of a run, every credential and secret an operator needs to log in as each seeded account: username, demo password, and (for ANALYST accounts) the TOTP secret used for enrollment.

#### Scenario: Operator can act immediately after the script finishes
- **WHEN** the seeding script finishes running
- **THEN** its final output alone (no other lookup) is sufficient for the operator to log in as any seeded ANALYST or CLIENT account
