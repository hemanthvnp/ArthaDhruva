## Context

ArthaDhruva exposes its engines through a Spring Boot `/v1` API. Facts established while scoping this change (re-verify before building, the code may have moved):

- **Auth:** the session token lives only in an httpOnly, SameSite=Strict cookie. `X-API-Key` authenticates only `/v1/ingest/**`. Staff accounts require TOTP. A `CsrfCookieFilter` exists, so mutating requests probably need a double-submit token (confirm the cookie and header names at build time).
- **Tenancy:** every connection runs `set_config('app.tenant_id', …)` (`TenantAwareDataSource`); RLS policies key off it. A separate `arthadhruva_worker` role bypasses tenant filters for background jobs and must never be used by tooling.
- **API surface:** `/score`, `/loans`, `/risk/term-structure(/compare)`, `/risk/scenarios`, `/cvar`, `/expected-loss`, `/risk/portfolio/runs` (POST returns `202`, GET polls), `/risk/portfolio(/loans|/loss-distribution|/history)`, `/regime-forecast`, `/models(/{id}|/survival/backtest|/pd_24m/drift)`, `/admin/audit-log`, `/admin/usage`, `/early-warning-score`, `/trajectory-score`.
- **Cross-cutting API behaviour:** every call is audited, metered (`@Metered`) and rate-limited per plan; POSTs support idempotency keys.
- **Existing assistant:** `/assistant/chat` is a single-shot LLM Q&A about one loan through LiteLLM (provider set in `litellm-config.yaml`), with a `PiiRedactor`. It is not a tool-using agent.
- **Repo rules that bind this work:** never run `export_*.py`, `fetch_macro.py`, `golden_term_structure.py` or the pipeline unless asked; never touch production infrastructure or push to `prod`; secrets, `.env*` and `historical_data_*` stay out of edits; commits by the owner only with no co-author line; report only checks that actually ran.

Stakeholders: the owner (developer/operator and the primary consumer), and future reviewers of the repo.

## Goals / Non-Goals

**Goals:**
- Five stdio MCP servers and one analyst agent that are safe by construction: read-only by default, tenant-scoped, bounded in cost, and unable to leak secrets.
- Answers from the agent whose figures are provably traceable to engine output.
- Tests at every layer, including a cross-tenant leak test and an adversarial injection suite, with CI gating on the deterministic parts.
- A structure a reviewer can read in minutes: one shared kit, one pattern per server.

**Non-Goals:**
- A consumer-facing hosted MCP endpoint, OAuth, or remote transport.
- Any backend, schema, role or endpoint change (a read-only DB role would be a separate, approved change).
- Replacing or modifying the in-product `/assistant/chat`.
- Running model exports, the data pipeline, or any production-infrastructure action.
- Publishing the packages to npm or an MCP registry.

## Decisions

### D1. TypeScript on Node, one npm workspace at `tools/mcp/`
The frontend is already TypeScript, and typed zod schemas double as the tool contracts. **Alternative:** plain JS (less setup, weaker contracts); Python (matches the training scripts but splits the toolchain). A workspace shares the kit and one `npm test`.

### D2. Four layers inside every server
Registration → tool handlers (zod validation, result shaping) → pure domain logic → adapters (HTTP, Postgres, subprocess, `gh`). Adapters are injected, so protocol tests swap in fakes and domain logic needs no I/O. **Alternative:** handlers calling I/O directly (faster to write, untestable without live systems).

### D3. Shared `kit` instead of copy-paste
One result envelope (text plus `structuredContent`), typed errors (`NotFound`, `Forbidden`, `UpstreamDown`, `Timeout`, `RateLimited` with retry-after, `QuotaExceeded`), env-only config validated at startup, a redactor applied to every outbound payload, a subprocess runner with timeout and command allowlist, a read-only guard, and an in-memory client/server test harness. Servers do not import each other; only the agent composes them.

### D4. Authentication for risk-engine: session cookie from env, CSRF echoed
The API accepts only the cookie, and staff need TOTP, so the server cannot log in unattended. It takes a session cookie (and derives the CSRF header from the CSRF cookie) from environment variables, returns a clear "session expired, refresh your cookie" error on 401, and never persists or logs it. A documented dev path mints a token for a local stack the way CI does with `JWT_SECRET`. **Alternative:** add API-key auth to `/v1` (backend change, widens the attack surface, out of scope).

### D5. Long-running work mirrors the API: separate start and status tools
`portfolio_run_start` returns the run id from the `202`; `portfolio_run_status` polls. A single blocking tool would exceed client timeouts and hide the "one active run per org" constraint. `portfolio_run_start` consumes quota, so the agent may call it only when the session sets an explicit `allowRunStart` flag.

### D6. tenant-ops: API first, Postgres only where the API cannot answer
Audit and usage come from `/admin/audit-log` and `/admin/usage`. Direct Postgres is limited to run diagnosis (heartbeat age, state, apparent dead worker) and webhook-outbox status. Direct access uses the existing app role inside a **read-only transaction** with the tenant set first, via the same `set_config` mechanism. Queries are fixed, parameterised, with allowlisted columns and bounded limits; there is no free-form SQL tool. **Alternative:** a dedicated SELECT-only role (cleaner, needs a migration, deferred as an open question). The worker role is never used.

### D7. Allowlists, not blocklists, for every subprocess and cloud call
`model-validation` may run only `TermStructureGoldenTest` through Maven. `deploy-ops` may run only read-only `az` subcommands plus the wake URL. `ci-guard` may run only the commands in its job model. Anything else is refused before spawning. This encodes the repo rules (no exports, no pipeline, no production changes) in code instead of relying on caller discipline.

### D8. ci-guard keeps one job model that mirrors CI, with honest outcomes
A single table of CI jobs and steps (from `ci.yml` and `security.yml`) drives preflight, estimates and static checks. Outcomes are `passed`, `failed` or `skipped (reason)`; a check that needs Docker or a missing tool is never reported as passed. Time prediction is a plain historical median and p90 from `gh` run history with a labelled fallback; no failure probabilities are produced, only concrete static findings. The runner never pushes, commits or edits files.

### D9. The agent is external, budgeted, and composed over MCP
It lives in `tools/mcp/agent/`, connects as an MCP client, and runs a tool-use loop against the Claude API (model name is configuration; default is the latest capable model). Hard per-question limits apply to steps, tokens, wall-clock time and cost. The tenant is fixed at session creation and no tool can change it. **Alternative:** extend the Java `AssistantService` (keeps one stack, but hides the MCP and agent design inside product code and risks regressions in a shipped feature).

### D10. Grounding contract plus a verifier
Every tool result is stored with an id. The final answer must cite ids next to each figure. A mostly deterministic verifier extracts each cited figure and compares it to the stored result (with a stated numeric tolerance and unit handling); on mismatch the agent gets one retry, then returns "could not verify" with the offending figures. An LLM judge is used only for non-numeric claims in the opt-in suite, never as the sole gate.

### D11. Injection handling is layered, not a single filter
(1) Tool output is delivered as clearly fenced data. (2) Instruction-like content in tenant-written fields (audit details, loan notes, webhook names) is neutralised before the model sees it. (3) The system prompt states that tool data is never an instruction. (4) The agent has no write tools, and tool permissions never widen mid-session. (5) The adversarial suite proves the combination, because no single layer is trusted.

### D12. Three-tier testing with an explicit CI split
Unit tests on domain logic; protocol tests through the in-memory harness; contract tests against a fake HTTP server; one real Postgres container test for tenant-ops including a cross-tenant leak test (skipped loudly when Docker is down). Agent evals split into a deterministic replay suite (runs in CI, no model, no cost) and an opt-in live suite (costs tokens, capped by configuration, never in default CI).

### D13. Observability of the tooling itself
Each tool call logs one line to stderr (tool, duration, outcome, never payloads, because stdout belongs to the protocol). Each agent run writes a local trace file (steps, tool calls, tokens, cost, verifier verdict).

### D14. Tool annotations enforced at registration
Every tool declares `readOnlyHint`, `destructiveHint` and `idempotentHint`, and the kit rejects a tool whose annotations contradict the adapter methods it uses. Clients can then decide when to ask for confirmation, and the "read-only by construction" claim is checked by code, not convention. **Alternative:** annotate by hand with no check (drifts silently).

### D15. Native MCP progress and cancellation for long work
`preflight`, run polling and the golden-test runner emit progress notifications and honour cancellation, in addition to the explicit start/status/cancel tools, because some clients drive only the protocol features. The explicit tools stay as the portable path.

### D16. Drift test between tools and the API
A contract test compares tool schemas to the API's real schema (generated OpenAPI if the backend can produce one, otherwise fixtures derived from the controllers) so a changed endpoint fails the build instead of failing in a conversation. If no machine-readable schema exists, building one is a backend change and is a separate, approved task.

### D17. Evaluate tool selection separately from answers
Tool descriptions steer which tool the model picks, so a selection suite scores tool choice on its own and records accuracy per tool. Together with a record mode that turns real runs into replay fixtures, this keeps the deterministic suite growing without hand-writing transcripts.

## Risks / Trade-offs

- **TOTP-gated cookie expires** → clear typed error, documented refresh and dev-mint procedure; no attempt to automate login.
- **Agent traffic is audited, metered and rate-limited** → a feature (it appears in audit), but runs can hit limits or consume quota → typed `RateLimited`/`QuotaExceeded` results with retry-after, backoff, per-question caps, `allowRunStart` flag.
- **Prompt injection through tenant-written data** → layered defence (D11) and a 30-case adversarial suite; residual risk accepted for a read-only, tenant-fixed agent and stated in the threat model.
- **Cross-tenant exposure in tenant-ops** → RLS-faithful tenant-set-first, read-only transaction, no free SQL, an automated leak test; a bug here is the highest-severity failure and gates the stage.
- **Verifier false negatives/positives on numbers** (rounding, units, percentages) → explicit tolerance and unit rules in the spec, with replay fixtures for each rule.
- **Live evals cost money and are non-deterministic** → opt-in, spending cap, scores trended not gated; CI gates only the deterministic suite.
- **Docker-dependent tests skipped on machines without Docker** → skips are loud and listed, never silent passes.
- **ci-guard drifts from the real workflows** → one job model, and a test that parses `ci.yml` and fails if a CI step is missing from the model.
- **Scope size (roughly 8 to 10 working days)** → gated stages with review pauses after risk-engine and after the agent; no new scope once a stage is done.

## Migration Plan

Purely additive: new directories and a new `.mcp.json`; nothing existing is modified except, with approval, one new CI job. Rollback is deleting `tools/mcp/` and `.mcp.json`. The scaffold currently at `tools/ci-guard/` (plain JS, untested end-to-end) is converted into the TypeScript `ci-guard` package and the original folder removed in the same step. Commits are by `hemanthvnp` only, with no co-author line, and only when the owner asks.

## Open Questions

0. Does the backend expose or can it generate an OpenAPI document for the drift test (D16)? If not, use controller-derived fixtures and record generating one as a separate backend change.
1. Reuse the existing app role for tenant-ops run diagnosis, or approve a new SELECT-only role migration? (Default: reuse.)
2. Add a CI job for the MCP workspace, which edits `ci.yml`? (Default: ask at that stage.)
3. Exact CSRF cookie and header names and whether GETs are exempt (verify by reading `CsrfCookieFilter` and `SecurityConfig`).
4. Agent model and provider: Claude API directly (default) or through the LiteLLM gateway, whose configured provider is currently Groq.
5. Spending cap for the live eval suite.
6. Whether the overlapping `borrower-portal-and-safety-assistant` change should share PII-redaction rules with the agent.

## Facts verified at build time (2026-10-14)

- **CSRF (open question 3).** Double-submit: the `XSRF-TOKEN` cookie (readable, `SameSite=Strict`) echoed in the
  `X-XSRF-TOKEN` header on every unsafe method. GET, HEAD, OPTIONS and TRACE are exempt (Spring's default).
  `CsrfCookieFilter` attaches the cookie to any response, even a 401, and the console fetches `GET /v1/csrf` to get it;
  the risk-engine adapter does the same when `RISK_ENGINE_XSRF` is unset. The session cookie is `ad_session`; the API
  may rotate it in a `Set-Cookie`, which the adapter follows in memory and registers with the redactor.
- **Idempotency.** `Idempotency-Key` on POST/PATCH; a reused key replays the first response, a duplicate in flight gets
  409, the same key with a different body 422, and server errors are not stored. The adapter reuses a key only when the
  same tool call failed without an answer (timeout, unreachable); any answered call gets a fresh key next time, so a
  later identical portfolio run start is a new run, not a replay.
- **Limits.** The API answers 429 with `Retry-After` (token buckets per organization and per caller) and 503 with
  `Retry-After` when every portfolio worker is busy. There is no quota response today: plan seats are enforced at user
  creation only. `QuotaExceeded` is mapped from 402 or a 429 whose body names a quota, ready for when one exists.
- **Endpoint drift from the list above (task 1.2).** `explain_loan` has no endpoint of its own: the Shapley explanation
  and reason codes come back in the `POST /score` response, so the tool scores and returns that part.
  `/risk/portfolio/loss-distribution` is a POST (optional body: confidence level, scenarios, correlation, seed).
  `/risk/portfolio/loans` takes `stage`, `region`, `sort`, `limit` (at most 500) and `offset`. A run start answers 202 with
  the run, 409 with the active run, 503 when busy.
- **OpenAPI (open question 0).** The backend has no springdoc or other generator. The drift test
  (`risk-engine/test/contract.test.ts`) derives the API's schema from the Java sources instead: every mapping, and every
  record's components with their `@NotNull`/`@NotBlank`/`@NotEmpty` marks. Publishing an OpenAPI document remains a
  separate backend change.
- **Old scaffold.** `tools/ci-guard/` (plain JS, never committed) was ported into `tools/mcp/ci-guard/` and moved out of the
  repository. Its failure-probability heuristic was dropped, as D8 requires, and its shellcheck command (a broken
  path-separator regex, and globs cmd.exe does not expand) is replaced by an explicit script list built at startup.

