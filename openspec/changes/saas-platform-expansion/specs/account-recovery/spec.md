## ADDED Requirements

### Requirement: Self-service password reset request
A user SHALL be able to request a password reset for their own account within their organization without administrator intervention, by submitting their organization slug and username.

#### Scenario: Valid account requests a reset
- **WHEN** a user submits a password reset request with a valid `orgSlug` and `username`
- **THEN** the system generates a single-use, time-limited reset token and sends a reset email to the account's on-file email address

#### Scenario: Unknown account does not leak existence
- **WHEN** a password reset request is submitted for an `orgSlug`/`username` combination that does not resolve to an account
- **THEN** the system returns the same generic success response as a valid request, and no email is sent

### Requirement: Password reset completion
A user SHALL be able to set a new password by presenting a valid, unexpired reset token, without needing their current password.

#### Scenario: Successful reset
- **WHEN** a user submits a valid, unexpired reset token and a new password meeting the existing password policy
- **THEN** the account's password is updated, the reset token is invalidated, and any existing lockout state is cleared

#### Scenario: Expired or reused token
- **WHEN** a user submits a reset token that has expired or was already used
- **THEN** the system rejects the request with a generic "invalid or expired reset link" error and does not update the password

### Requirement: Transactional email delivery
The system SHALL send transactional email (password reset, and future invite/notification-digest email) through a configured SMTP provider, with graceful degradation when no provider is configured.

#### Scenario: Email provider configured
- **WHEN** SMTP credentials are configured and a transactional email is triggered
- **THEN** the email is sent via the configured provider and the triggering request succeeds regardless of delivery outcome

#### Scenario: Email provider not configured
- **WHEN** no SMTP credentials are configured and a transactional email is triggered
- **THEN** the system logs the intended email content and the triggering request still succeeds, matching the existing activation-link fallback behavior
