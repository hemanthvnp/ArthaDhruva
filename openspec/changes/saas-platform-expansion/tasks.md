## 1. Foundation (unblocks everything else)

- [x] 1.1 Add Testcontainers (Postgres, Redis) to the backend test dependency set and rewrite existing integration tests to provision containers instead of requiring a live local Postgres/Redis
- [x] 1.2 Add a GitHub Actions workflow that runs `mvn test` (backend, Testcontainers-backed) and `npx tsc -b && vite build` (frontend) on every push
- [x] 1.3 Introduce a `DomainEvent` base type and publish `LoanCaseUpdatedEvent`/`LoanCaseAssignedEvent`/`LoanCaseNoteAddedEvent`/`LoanScoredEvent` via Spring `ApplicationEventPublisher` from `LoanCaseService`/`LoanScoreService` (Decision 1)
- [x] 1.4 Convert `NotificationService`'s existing case-assignment/note-added triggers from direct method calls into `@EventListener`s on the new domain events, with each listener independently catching/logging its own failures (Decision 1, Risk mitigation)
- [x] 1.5 Extract an abstract `AbstractModelService` template-method base class (load model → build feature vector → run inference → calibrate) and refactor `ModelService`, `EarlyWarningModelService`, `TrajectoryModelService` to extend it, supplying only their feature-vector construction and model/calibration artifacts (Decision 8)
- [x] 1.6 Add a `production` Spring profile that fails startup if `JWT_SECRET`, `TOTP_ENCRYPTION_KEY`, or other required secrets are unset, while leaving the existing random-generation fallback unchanged for non-production profiles (Decision 5)
- [x] 1.7 Introduce a `/v1/` path prefix for all existing REST endpoints, updating the frontend `api/client.ts` base path accordingly
- [x] 1.8 Verify: full `mvn test` passes with Testcontainers and no locally-running Postgres/Redis; CI workflow runs green on a pushed branch

## 2. Trust the plumbing

- [x] 2.1 Add Prometheus, Grafana, Loki, and Jaeger/Tempo services to `docker-compose.yml` behind a dedicated compose profile (e.g. `observability`) so local dev without it stays lightweight (Decision 6, Risk mitigation)
- [x] 2.2 Add Micrometer + `spring-boot-starter-actuator` metrics export to Prometheus, and OpenTelemetry tracing export to Jaeger/Tempo
- [x] 2.3 Switch backend logging to structured JSON output with a request-correlation ID (e.g. via a servlet filter populating MDC), shipped to Loki
- [x] 2.4 Add an `nginx` service to `docker-compose.yml` serving the built frontend as static files, proxying `/v1/` API paths to the backend, terminating TLS, and enabling gzip/Brotli compression and edge rate limiting (Decision 7)
- [x] 2.5 Verify: a request's logs are correlatable end-to-end by a single ID across service calls; Grafana dashboard shows live per-endpoint latency; frontend loads correctly when served through nginx instead of directly from the dev server

## 3. Account & growth

- [x] 3.1 Add SMTP-based transactional email sending with graceful degradation (log-and-continue when unconfigured, matching the existing activation-link fallback pattern)
- [x] 3.2 Add password-reset-token entity/repository (single-use, time-limited, tenant-scoped) and `POST /v1/account/reset-password/request` + `POST /v1/account/reset-password/complete` endpoints, with identical generic responses for valid and unknown accounts
- [x] 3.3 Add frontend "forgot password" request and reset-completion pages, wired into the login flow
- [x] 3.4 Add SAML/OIDC SSO configuration per organization (IdP metadata storage, ACS/callback endpoint) issuing normal session JWTs on successful IdP authentication, preserving a break-glass local-login path
- [x] 3.5 Add tenant-scoped API key entity/repository, issuance/revocation endpoints for org admins, and an `ApiKeyAuthenticationFilter` alongside the existing JWT filter
- [x] 3.6 Add a platform-administrator role (distinct from tenant `ADMIN`) with cross-tenant organization list/suspend/reactivate endpoints, rejecting tenant-scoped `ADMIN` users with the existing 403 behavior
- [x] 3.7 Add a sandbox/demo organization flag on `Organization`, surfaced in the JWT/session response and rendered as a persistent UI indicator when active
- [x] 3.8 Add subscription plan entity (seat limit, rate-limit tier) and assign a default trial/starter plan on organization creation
- [x] 3.9 Enforce seat limits in the existing user-creation path, rejecting with an error identifying the limit and current usage when exceeded
- [x] 3.10 Add usage-metering records (e.g. scoring calls) incremented per organization per billing period
- [x] 3.11 Add a self-service signup flow (new organization + first admin user) distinct from today's admin-provisioned-only account creation, assigning the new org a trial plan
- [x] 3.12 Integrate Stripe (or chosen provider, pending Open Question) for plan billing, and make API rate limiting scale with the caller's organization plan tier instead of a single global limit
- [x] 3.13 Verify: password reset round-trip works end-to-end with a configured SMTP provider and degrades gracefully without one; a seat-limit-exceeded signup is rejected with the correct error; two orgs on different plan tiers are throttled at different rates

## 4. Workflow depth

- [x] 4.1 Add an `AutomationRule` entity (tenant-scoped: trigger event type, `Specification<LoanCase>` condition, ordered actions) and repository
- [x] 4.2 Implement rule actions as `Command` objects (assign case, flag case, send notification) and an ordered `Chain of Responsibility` evaluator invoked as an `@EventListener` on the Phase 1 domain events (Decision 3)
- [x] 4.3 Ensure rule evaluation failure isolation: one rule's exception is logged and does not prevent other rules or the triggering request from completing
- [x] 4.4 Add admin CRUD endpoints and a frontend page for defining/editing automation rules
- [x] 4.5 Add a webhook outbox table (event payload, target URL, status, attempt count) written in the same transaction as the triggering state change, and a `WebhookSubscription` entity for admin-registered endpoint URLs + subscribed event types (Decision 2)
- [x] 4.6 Implement a webhook delivery poller with retry/backoff, marking events delivered on success and failed (not silently dropped) after exhausting the configured max attempts; include a unique event ID per delivery for receiver-side deduplication
- [x] 4.7 Add a batch loan-ingestion API endpoint (API-key authenticated) that ingests valid records into tenant-scoped data and reports invalid records individually without failing the whole batch
- [x] 4.8 Implement JPA `Specification`-based composable query building for loans/cases/notes, supporting combined filters (status, flagged, state, risk band, date range) scoped to the caller's tenant (Decision 4)
- [x] 4.9 Add a bulk case-update endpoint (status/assignment/flag) that applies to multiple case IDs in one call, reporting invalid/foreign IDs individually without failing the batch, and triggering existing per-case notifications
- [x] 4.10 Add per-user dashboard widget configuration (selection + arrangement, independent per user) and a `GET/PUT /v1/dashboard/config` endpoint plus frontend widget picker
- [x] 4.11 Add per-event-type notification preferences (instant/digest/off) per user, a digest-batching job for events set to digest mode, and a frontend preferences page
- [x] 4.12 Verify: crashing the app between a state change and webhook delivery still results in delivery after restart; a combined-filter search never returns cross-tenant rows; a bulk update with one invalid ID updates the rest and reports the failure

## 5. ML depth

- [x] 5.1 Add a scheduled job that clusters an organization's case notes into topics (e.g. TF-IDF + k-means or an embedding-based approach) and a `GET` endpoint/frontend view surfacing topic clusters with representative example notes
- [x] 5.2 Add unsupervised borrower segmentation (clustering over loan features, distinct from the existing segment-correlation graph) with an endpoint/frontend view returning clusters, their defining feature characteristics, and member loan counts
- [x] 5.3 Add feature-attribution/explainability (e.g. SHAP) to the score response, computed per-loan against the Phase 1 `AbstractModelService` skeleton so all three model types gain it from one integration point (depends on Decision 8 refactor)
- [x] 5.4 Verify: two loans with different feature values return different feature-attribution explanations; topic clusters and borrower segments are correctly tenant-scoped

## 6. Tenant architecture evolution

- [x] 6.1 Add subdomain-based tenant resolution alongside the existing `orgSlug` login field, accepting either during a deprecation window and rejecting only when both are supplied but conflict (Decision 9); requires wildcard DNS/TLS on the nginx layer from Phase 2
- [x] 6.2 Add a startup log warning when the deprecated `orgSlug`-only login path is used, per the Risk mitigation in the design doc
- [ ] 6.3 Schedule and execute the `orgSlug` removal (the BREAKING change) at the end of the deprecation window
- [x] 6.4 Add tenant-uploadable loan portfolio ingestion (admin upload), routing that organization's catalog/scoring-by-ID views to the uploaded data while other organizations continue seeing the shared demo catalog unchanged
- [x] 6.5 Add Postgres Row-Level Security policies on every tenant-scoped table, keyed to the session's tenant context, as a third defense-in-depth layer beneath the existing explicit-parameter and Hibernate-filter guards
- [x] 6.6 Write the model-serving extraction evaluation document: concrete, measurable load/latency criteria under which extracting `score`/`earlywarning`/`trajectory` into an independently-deployable service would be justified
- [x] 6.7 Verify: a login via subdomain authenticates identically to the existing `orgSlug` flow; an RLS-protected query issued without tenant context set returns zero rows; two orgs' uploaded/demo catalogs remain independent

## 7. Performance (parallelizable with any phase above)

- [x] 7.1 Add route-based code-splitting to the frontend router so unvisited pages are not downloaded on initial load
- [x] 7.2 Adopt TanStack Query incrementally, page by page, as each page is next touched for other reasons (Decision 10), replacing ad-hoc `useEffect`/`useState` fetching with cached, background-refreshed queries
- [x] 7.3 Add virtualized rendering to large loan/case list tables, debounce search inputs, and memoize expensive renders
- [x] 7.4 Add a CI step that fails the build if the production frontend bundle exceeds a defined size budget
- [x] 7.5 Audit list-returning endpoints (especially those touching the tenant_id/composite-key changes) for N+1 queries and fix via fetch joins/batch fetching so query count does not scale linearly with result size
- [x] 7.6 Add consistent pagination (with an enforced maximum page size) to every list endpoint that does not already have it
- [x] 7.7 Tune HikariCP connection-pool settings based on observed load
- [x] 7.8 Add a k6 or Gatling load-testing suite against key endpoints and wire it into CI as a latency-threshold gate
- [x] 7.9 Verify: CI fails on an intentionally oversized bundle or an intentionally slow endpoint in a test run, then passes once reverted

## 8. CS fundamentals: concurrency, complexity, cost (added 2026-09-20)

- [x] 8.1 Atomic failed-login counter (single UPDATE with CASE) replacing the load-increment-save read-modify-write; concurrency test added
- [x] 8.2 Optimistic locking (`@Version`, V13) on `loan_case`; stale writes and lost create-races return 409 instead of overwriting or 500
- [x] 8.3 Outbox poller uses `SELECT ... FOR UPDATE SKIP LOCKED` so parallel workers never double-deliver (with 4.5/4.6)
- [x] 8.4 Async event listeners on a bounded executor with a `TaskDecorator` propagating `TenantContext`
- [x] 8.5 Complexity audit: replace O(n) list scans and per-row queries on hot paths (`List.contains` in model feature loops, N+1) and record before/after timings
- [x] 8.6 Deployment-cost profile: JVM heap/container limits, Hikari pool sizing, response caching, and measured memory/CPU per request at idle and under load (k6)

## 9. Distributed systems and computer architecture (added 2026-09-20)

- [x] 9.1 Run two backend instances behind nginx (upstream load balancing) and show the app is horizontally scalable (stateless JWT, shared Postgres/Redis)
- [x] 9.2 Move rate limiting from in-process Resilience4j to Redis-backed limits so the limit holds across instances
- [x] 9.3 Idempotency-Key support on mutating endpoints (stored result replayed on retry)
- [x] 9.4 Retries with exponential backoff and jitter on outbound calls (LLM, webhooks)
- [x] 9.5 Distributed lock (Redis or Postgres advisory lock) so scheduled jobs run on exactly one instance
- [x] 9.6 Rewrite model feature-vector building with primitive arrays and precomputed index maps (no per-request HashMap/boxed Float, no List.contains scans)
- [x] 9.7 JMH benchmarks for feature building and scoring, with before/after ns/op and allocation numbers
- [x] 9.8 Container JVM tuning (heap, GC choice) and Hikari/thread pool sizing derived from cores and Little's law, measured under k6
- [x] 9.9 EXPLAIN ANALYZE review of every tenant-scoped query and index, results recorded

## 10. Docker and computer-networks hardening (added 2026-09-20)

- [x] 10.1 Multi-stage, non-root backend Dockerfile (JRE-only runtime image) so the whole stack runs in containers
- [x] 10.2 Segmented compose networks: `edge` (nginx, app), `data` (Postgres/Redis/Neo4j, internal-only, no host ports), `observability`
- [x] 10.3 Container hardening: read_only rootfs, cap_drop ALL, no-new-privileges, CPU/memory limits
- [x] 10.4 Healthchecks on every service with depends_on service_healthy
- [x] 10.5 Secrets via Docker secrets/env files kept out of git; production profile has no default passwords
- [x] 10.6 TLS to Postgres (verify-full) and nginx security headers (HSTS, CSP, X-Content-Type-Options, X-Frame-Options)
- [x] 10.7 Backend trusts X-Forwarded-* only from the proxy; CORS reviewed
- [x] 10.8 Verify and document: docker network inspect, data ports unreachable from host, TLS handshake and DNS service-discovery walkthrough
