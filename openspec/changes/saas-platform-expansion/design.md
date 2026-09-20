## Context

ArthaDhruva is a Spring Boot + Postgres/Redis/Neo4j backend with a React/TypeScript frontend, already multi-tenant (shared-schema, `tenant_id`/composite-key isolation backed by explicit repository parameters plus a Hibernate `@Filter` backstop) with JWT+TOTP auth, an audit trail, in-app notifications, file attachments, CSV export, and an LLM assistant fronted by a LiteLLM proxy. The codebase follows a modular-monolith discipline: each bounded-context package (`security`, `tenant`, `score`, `earlywarning`, `trajectory`, `workflow`, `audit`, `assistant`, `notification`) exposes a `@Service` façade over a private `@Repository`; cross-module calls go through that façade, never the repository directly. This proposal's capabilities must extend that discipline, not bypass it.

The eleven capabilities in this proposal are independently valuable and will be implemented and shipped in sequence (not as one deploy). This document covers the cross-cutting technical decisions that apply across capabilities; capability-specific behavior lives in each `specs/<name>/spec.md`.

## Goals / Non-Goals

**Goals:**
- Preserve the existing module-boundary discipline (Service façade, tenant-scoped repository methods, Hibernate filter backstop) as every new capability is added.
- Sequence work so early phases (event-driven core, CI/test infra) make later phases (automation rules, webhooks) strictly easier, not harder.
- Keep every new external dependency (email provider, SSO IdP, Stripe, observability stack, LiteLLM already in place) behind an interface with a graceful-degradation fallback, matching the existing `CacheService`/`LlmClient` fail-open pattern.
- Avoid distributed-systems complexity (message brokers, microservices, Kubernetes) until a concrete scaling need forces it — this is a modular monolith by deliberate choice.

**Non-Goals:**
- Compliance/trust capabilities (SR 11-7 model documentation, ECOA/Reg B explainability-as-a-legal-requirement, CECL, SOC 2, disparate-impact testing) — explicitly deferred to a future, separate proposal.
- Full microservices decomposition — `tenant-architecture-evolution` only *evaluates* extracting ML model-serving; it does not commit to doing so in this proposal.
- Kubernetes, a message broker (Kafka/RabbitMQ/SQS), or a separate API gateway product — Docker Compose, an in-process event bus, and nginx are sufficient at this scale (see Decisions).

## Decisions

### 1. Event-driven core via Spring `ApplicationEventPublisher`, not a message broker
`case-automation-rules`, `webhooks-integration`, and the existing `notification` trigger in `LoanCaseService` all need "something happened, N things react" semantics. **Decision**: introduce domain events (e.g. `LoanCaseUpdatedEvent`) published via Spring's in-process `ApplicationEventPublisher` (Observer pattern), with `NotificationService`, the automation-rules evaluator, and the webhook dispatcher each as independent `@EventListener`s. **Alternative considered**: Kafka/RabbitMQ — rejected as premature; this is a single-process monolith with no cross-service delivery need yet, and an external broker adds an operational dependency with no corresponding benefit at current scale. Revisit only if model-serving (or another module) is actually extracted into a separate process.

### 2. Transactional Outbox for webhook delivery specifically
Webhooks differ from in-process listeners: delivery is an HTTP call to an external, unreliable endpoint, and a lost event is a real customer-visible problem. **Decision**: webhook dispatch writes an outbox row (event payload + status) in the same transaction as the state change, and a separate poller delivers it with retry/backoff, marking it delivered on success. This guarantees at-least-once delivery even if the app crashes between the state change and the HTTP call. **Alternative considered**: firing the webhook HTTP call inline inside the event listener — rejected because a failed/slow webhook endpoint would then block or fail the triggering request's listener chain.

### 3. `case-automation-rules` as Command + Chain of Responsibility, evaluated as a listener on the same event bus
Each rule is a `(Specification<LoanCase> condition, Command action)` pair; rules for a tenant are evaluated in order as a chain against the incoming event. **Alternative considered**: a generic scripting/DSL engine (e.g., embedding a rules DSL) — rejected as over-engineered for the initial rule set (assign/flag/notify); revisit only if customers need conditions this data model can't express.

### 4. `advanced-search` via JPA Specifications (Specification pattern), not a search index
Composable, runtime-built query predicates over existing Postgres tables. **Alternative considered**: Elasticsearch/OpenSearch — rejected at current data volumes (thousands, not millions, of rows per tenant); a dedicated search index is a real future option once row counts justify it, not now.

### 5. Secrets management: fail-fast validation now, external secrets manager later
**Decision**: add a `production` Spring profile that fails startup if any of `JWT_SECRET`, `TOTP_ENCRYPTION_KEY`, `LITELLM_API_KEY`-equivalent, etc. are unset — closing the current silent-random-generation behavior for that profile only (local/dev profiles keep today's convenience fallback unchanged). Actual Vault/AWS Secrets Manager integration is scoped as a follow-up once a real deployment target exists, since it requires choosing a target cloud/host first.

### 6. Observability stack: Prometheus + Grafana + Loki + Jaeger/Tempo, self-hosted via Docker Compose
Matches the project's existing self-hosted-via-compose pattern (Postgres/Redis/Neo4j/LiteLLM already run this way). **Alternative considered**: a managed SaaS observability product (Datadog, etc.) — rejected for now on cost/operational-scope grounds for a project at this stage; the self-hosted stack is also more directly resume/portfolio-relevant.

### 7. nginx as reverse proxy + static file server + TLS termination, not a dedicated API gateway product
nginx serves the built frontend, proxies API paths to Spring Boot, terminates TLS, and handles gzip/Brotli compression and edge rate limiting. **Alternative considered**: Kong/Traefik — rejected as redundant; nginx already covers every current need, and introducing a second product for marginal additional features isn't justified yet.

### 8. `ModelService`/`EarlyWarningModelService`/`TrajectoryModelService` unified via Template Method
All three share a load-model → build-feature-vector → run-ONNX-inference → calibrate skeleton today, implemented three times. **Decision**: extract an abstract base class defining that skeleton as template methods, with each subclass supplying only its feature-vector construction and model/calibration artifacts. This is a pure internal refactor (no API/behavior change) and should land early, since `ml-insights` (explainability) and any future model additions benefit from touching one code path instead of three.

### 9. Subdomain tenant resolution is additive-then-switchover, not a big-bang cutover
**Decision**: introduce subdomain-based resolution (`acme.arthadhruva.app`) alongside the existing `orgSlug` login field, not as an immediate replacement — the login endpoint accepts either for a deprecation window before `orgSlug` is removed (the **BREAKING** change noted in the proposal happens at the *end* of that window, not at initial rollout). Requires wildcard DNS/TLS on whatever host serves the frontend, which is also why this depends on the nginx/deployment work landing first.

### 10. Frontend data layer: TanStack Query adopted incrementally, page by page
**Alternative considered**: a big-bang migration of every page at once — rejected as high-risk/low-value; pages are migrated as they're touched for other reasons (e.g., a page gaining automation-rules or advanced-search UI naturally migrates to TanStack Query at the same time) rather than as a standalone sweep.

## Risks / Trade-offs

- **[Risk]** Eleven capabilities across months of work risk architectural drift if implemented by different sessions/people without re-reading this design doc → **[Mitigation]** every capability's spec delta must explicitly reference the relevant Decision number(s) above; PRs/tasks that violate a stated decision (e.g., introducing a message broker, bypassing the Service façade) should be treated as a design regression, not a local judgment call.
- **[Risk]** Event-driven core (Decision 1) changes failure semantics: a listener exception no longer surfaces synchronously to the original caller the way a direct method call did → **[Mitigation]** each listener must independently handle/log its own failures (matching the existing fail-open philosophy of `CacheService`/`LlmClient`); a failure in the automation-rules listener must never prevent the notification listener from running or the original request from succeeding.
- **[Risk]** Subdomain tenant resolution's dual-mode window (Decision 9) means two parallel tenant-resolution code paths temporarily exist → **[Mitigation]** keep the window short and time-boxed; add a startup log warning (not just a config flag) so the deprecated path's continued use is visible in every environment, not just discoverable by reading code.
- **[Risk]** Self-hosted observability stack (Decision 6) adds four new containers to operate → **[Mitigation]** ship it behind a docker-compose profile so local dev without observability needs stays lightweight; only CI and any real deployment target run the full stack by default.
- **[Trade-off]** Postgres RLS (`tenant-architecture-evolution`) as a *third* isolation layer (alongside explicit parameters and the Hibernate filter) adds real complexity for marginal additional safety, given the existing two layers already passed live cross-tenant isolation testing — included because it's genuinely the last line of defense against a raw/native query bypassing the ORM entirely, not because the existing layers are believed insufficient.

## Migration Plan

Recommended sequencing (each is independently shippable; later phases depend on earlier ones as noted):

1. **Foundation** (unblocks everything else): Testcontainers + CI pipeline, event-driven core (Decision 1), Template Method model-service refactor (Decision 8), secrets fail-fast validation (Decision 5), API versioning.
2. **Trust the plumbing**: observability stack (Decision 6), nginx (Decision 7) — do this before adding customer-facing capabilities so they're observable/deployable-behind-a-proxy from day one.
3. **Account & growth**: account-recovery (email delivery is also a dependency for later invite/digest features), enterprise-identity, billing-subscriptions.
4. **Workflow depth** (depends on Phase 1's event bus): case-automation-rules, webhooks-integration (depends on the outbox pattern, Decision 2), advanced-search, dashboard-personalization.
5. **ML depth**: ml-insights (depends on Phase 1's model-service refactor for a single place to hook explainability into).
6. **Tenant architecture evolution**: subdomain resolution (depends on nginx/DNS from Phase 2), tenant-uploadable portfolios, Postgres RLS.
7. **Performance**: can run in parallel with any phase above; the CI-enforced bundle budget and load-testing gate should exist by the time customer-facing capacities (Phase 3+) ship.

Rollback strategy: each capability ships behind its own Flyway migrations and, where user-facing, a feature flag/config toggle — no phase requires a prior phase's rollback to undo independently.

## Open Questions

- Which SSO protocol to prioritize first (SAML vs. OIDC) — depends on which enterprise prospect's IdP is closest to signing, not a technical decision.
- Stripe vs. an alternative billing provider — needs a business decision on target markets (Stripe's coverage varies by region).
- Whether subdomain resolution's wildcard DNS/TLS is managed via the eventual cloud host's native support or via nginx + a manual cert process — depends on the concrete deployment target chosen in the Terraform/IaC work.
- Target cloud provider for the eventual Terraform/IaC work — not yet decided; blocks that specific item but not the rest of this proposal.
