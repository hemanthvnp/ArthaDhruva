## Why

ArthaDhruva has grown from a single-tenant risk-scoring tool into a multi-tenant platform with authentication, notifications, attachments, and an AI assistant — but it still lacks the account-recovery, monetization, integration, and engineering-maturity capabilities a real SaaS product needs to be sold, operated, and scaled beyond a demo. This proposal captures that gap as a sequenced, multi-phase roadmap rather than a single atomic change, so each capability can be implemented, reviewed, and shipped independently while sharing one coherent plan. Compliance/trust capabilities (SR 11-7 model documentation, ECOA/Reg B explainability, CECL, SOC 2) are explicitly out of scope for this proposal and will be proposed separately when a regulated customer is imminent.

## What Changes

- Add self-service password reset backed by real email delivery (SMTP) — today a forgotten password requires an admin to intervene, and no email infrastructure exists at all.
- Add enterprise identity features: SSO/SAML/OIDC login, tenant-scoped API keys for programmatic access, a platform-level (cross-tenant) admin console distinct from today's tenant-scoped `ADMIN` role, and a sandbox/demo-org mode for prospects to trial the product on non-production data.
- Add billing and monetization: subscription plans, usage metering, seat limits, a self-service signup/trial flow, and per-tenant rate limiting tied to plan tier (today's rate limiting is global and login-only).
- Add outbound webhooks (case flagged, loan scored, etc.) delivered via a transactional outbox for at-least-once guarantees, plus batch/API integration points for a bank's core loan origination/servicing system.
- Add a case automation rules engine ("when X happens, do Y" — auto-flag, auto-assign, auto-notify) so `workflow` stops being purely reactive.
- Add an advanced, composable query/filter language across loans, cases, and notes, plus bulk case operations (bulk-assign/update).
- Add user-configurable dashboard widgets and granular, per-event notification preferences (instant/digest/off), replacing today's fixed dashboard and two hardcoded notification triggers.
- Add ML-driven insight features: topic clustering over free-text case notes, unsupervised borrower segmentation, and feature-importance (SHAP-style) explainability surfaced on PD scores.
- Evolve tenant architecture: subdomain-based tenant resolution (replacing the `orgSlug` login field), tenant-uploadable loan portfolios (replacing the shared static demo catalog), Postgres Row-Level Security as a defense-in-depth layer beneath the existing Hibernate filter, and an evaluation path for extracting ML model-serving into an independently-scaled service.
- Raise engineering maturity: an event-driven core (Spring `ApplicationEventPublisher`, Observer pattern) decoupling `LoanCaseService` side effects from direct method calls; a Template Method refactor unifying the near-duplicated `ModelService`/`EarlyWarningModelService`/`TrajectoryModelService` load-features-infer-calibrate skeleton; Testcontainers-based integration tests plus a GitHub Actions CI pipeline (today's only integration test requires a live local Postgres/Redis/Neo4j and cannot run in CI); a self-hosted observability stack (Prometheus, Grafana, Loki, Jaeger/Tempo) with structured JSON logging and request-correlation IDs; API versioning (`/v1/` prefix); real secrets management (Vault/AWS Secrets Manager) with fail-fast startup validation in a production profile; an nginx reverse-proxy layer for TLS termination, static asset serving, compression, and edge rate limiting; and Terraform/IaC once a concrete cloud deployment target exists.
- Improve performance end to end: frontend route-based code-splitting, a TanStack Query data-fetching/caching layer, virtualized tables for large lists, debounced search, memoized renders, and a CI-enforced bundle-size budget; backend N+1 query audit (particularly around the recent tenant-scoping/composite-key changes), consistent pagination across all list endpoints, HikariCP pool tuning, and load testing (k6/Gatling) as a CI gate.
- **BREAKING**: subdomain-based tenant resolution removes the `orgSlug` field from the login request shape once implemented; existing integrations against `POST /login` must migrate.

## Capabilities

### New Capabilities
- `account-recovery`: self-service password reset and the email-delivery infrastructure it depends on.
- `enterprise-identity`: SSO/SAML/OIDC, tenant API keys, platform-level admin console, sandbox/demo-org mode.
- `billing-subscriptions`: plans, usage metering, seat limits, self-service signup/trial, plan-tiered rate limiting.
- `webhooks-integration`: outbound webhook delivery (transactional outbox) and core banking/LOS integration points.
- `case-automation-rules`: condition/action automation engine on loan cases.
- `advanced-search`: composable query/filter language and bulk operations across loans/cases/notes.
- `dashboard-personalization`: user-configurable dashboard widgets and per-event notification preferences.
- `ml-insights`: note topic clustering, borrower segmentation, PD-model explainability.
- `tenant-architecture-evolution`: subdomain tenant resolution, tenant-uploadable catalogs, Postgres RLS, model-serving extraction evaluation.
- `platform-observability`: event-driven core, CI/test infrastructure, observability stack, API versioning, secrets management, nginx, IaC.
- `performance-optimization`: frontend and backend performance work and CI performance gates.

### Modified Capabilities
- None. No prior OpenSpec capability specs exist in this repository (`openspec/specs/` is empty) — the notifications, attachments, CSV export, and AI-assistant features already shipped in code were not previously captured as specs, so nothing here modifies an existing spec's requirements.

## Impact

- **Backend**: new modules (`billing`, `webhooks`/outbox, `automation`, `search`), extensions to `security` (SSO, API keys), `tenant` (platform admin, subdomain resolution), `workflow` (automation triggers, bulk ops), `score`/`earlywarning`/`trajectory` (Template Method refactor, explainability), `assistant`/`notification` (event-driven wiring). New Flyway migrations for every new entity (plans, API keys, webhook subscriptions/outbox, automation rules, dashboard preferences).
- **Frontend**: new pages/components for billing, SSO login, API key management, automation rule builder, advanced search/filter UI, configurable dashboard, ML insight views; a data-fetching layer migration (ad hoc `useEffect`/`useState` → TanStack Query) touching most existing pages.
- **Infrastructure**: `docker-compose.yml` gains `nginx`, `prometheus`, `grafana`, `loki`, and a tracing collector; new GitHub Actions CI workflow; Testcontainers dependency for backend tests.
- **APIs**: introduces `/v1/` versioning for all endpoints (breaking for any existing integration, though none exist externally yet); login request shape changes once subdomain resolution replaces `orgSlug`.
- **Out of scope**: all compliance/trust capabilities (model risk documentation, ECOA explainability-as-a-legal-requirement, CECL, SOC 2, disparate-impact testing) are deferred to a separate future proposal.
