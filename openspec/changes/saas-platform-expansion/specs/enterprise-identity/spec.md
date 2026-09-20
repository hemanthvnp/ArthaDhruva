## ADDED Requirements

### Requirement: SSO login via SAML or OIDC
An organization SHALL be able to configure a SAML or OIDC identity provider so its users can authenticate without a locally-managed password.

#### Scenario: User authenticates via configured IdP
- **WHEN** a user from an organization with SSO configured completes authentication at the organization's identity provider
- **THEN** the system issues a normal session JWT scoped to that organization, without requiring a locally stored password

#### Scenario: SSO-enabled organization still supports emergency local login
- **WHEN** an organization has SSO configured but a designated break-glass admin account exists
- **THEN** that account can still authenticate via the existing username/password/2FA flow

### Requirement: Tenant-scoped API keys
An organization admin SHALL be able to issue and revoke API keys scoped to their own tenant for programmatic, non-interactive access.

#### Scenario: API key authenticates a request
- **WHEN** a request presents a valid, non-revoked API key for an organization
- **THEN** the request is authenticated as belonging to that organization with the permissions assigned to the key

#### Scenario: Revoked key is rejected
- **WHEN** a request presents an API key that has been revoked
- **THEN** the request is rejected as unauthenticated, identically to a missing key

### Requirement: Platform-level administration
A distinct platform-administrator role, separate from tenant-scoped `ADMIN`, SHALL be able to view and manage organizations across all tenants.

#### Scenario: Platform admin suspends an organization
- **WHEN** a platform administrator suspends an organization
- **THEN** all users of that organization are denied login until the organization is reactivated, while other organizations are unaffected

#### Scenario: Tenant admin cannot access platform administration
- **WHEN** a user with only the tenant-scoped `ADMIN` role attempts to access a platform-administration endpoint
- **THEN** the request is rejected with the same 403 behavior as any other role-restricted endpoint

### Requirement: Sandbox/demo organization mode
An organization SHALL be markable as a sandbox, clearly distinguishing it from production tenants in both the API and the UI.

#### Scenario: Sandbox organization is visually distinct
- **WHEN** a user is authenticated into a sandbox-flagged organization
- **THEN** the UI displays a persistent, unmistakable "sandbox" indicator
