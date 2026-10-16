## 1. Discovery and workspace (gate: facts verified, workspace builds)

- [x] 1.1 Re-read `CsrfCookieFilter` and `SecurityConfig`; record the CSRF cookie/header names and whether GETs are exempt (design open question 3)
- [x] 1.2 Re-confirm the endpoint list and request/response shapes for every risk-engine tool against the controllers; note any drift from design.md
- [x] 1.3 Create `tools/mcp/` npm workspace (TypeScript, zod, MCP SDK, test runner) with `.gitignore` for `node_modules`
- [x] 1.4 Remove the untested JS scaffold at `tools/ci-guard/` once its logic is ported in section 3
- [x] 1.5 Add `.env.example` documenting every variable (no real values)

  Verified 2026-10-14: CSRF/idempotency/limit facts and the endpoint drift are recorded in design.md ("Facts verified at build time"). The old `tools/ci-guard/` scaffold was moved out of the repository (it was never committed), not deleted.

## 2. mcp-kit (gate: kit tests green)

- [x] 2.1 Result envelope with text and `structuredContent`, plus output-schema validation
- [x] 2.2 Typed errors (`NotFound`, `Forbidden`, `UpstreamDown`, `Timeout`, `RateLimited`, `QuotaExceeded`, `InvalidInput`) and MCP error mapping
- [x] 2.3 Env-only config loader with startup validation that never prints secret values
- [x] 2.4 Redactor applied to every outbound payload and log line
- [x] 2.5 Subprocess runner: allowlist, timeout, process-tree kill (Windows `taskkill` and POSIX)
- [x] 2.6 Read-only guard for adapters
- [x] 2.7 One-line stderr call logging that never touches stdout or payloads
- [x] 2.8 In-memory client/server test harness
- [x] 2.9 Kit unit tests for each requirement in `specs/mcp-kit`
- [x] 2.10 Tool-annotation enforcement at registration (missing or contradictory annotations fail)
- [x] 2.11 Progress-notification and cancellation helpers, with tests for both scenarios

  Gate met 2026-10-14: `kit` 28/28 tests (basics, subprocess incl. process-tree kill on timeout and cancel on Windows, protocol through the in-memory harness incl. annotations, progress and cancellation).

## 3. ci-guard server (gate: a real preflight runs on this repo)

- [x] 3.1 Port the job model; add the test that parses `ci.yml` and `security.yml` and fails on any step missing from the model
- [x] 3.2 Preflight runner: async run id, ordering (cheap first), skipped-with-reason semantics, cancel, `.git/ci-guard` state only
- [x] 3.3 Fix Windows glob handling for shellcheck and verify compose, promtool and wake steps run through the allowlisted runner
- [x] 3.4 Static checks: CRLF scripts, lockfile drift, case collisions, secrets paths, Node-major drift
- [x] 3.5 `typical_time` from `gh` history with the fallback label and no failure probabilities
- [x] 3.6 Tools, protocol tests via the harness, and unit tests for each scenario in `specs/ci-guard-mcp`
- [x] 3.7 Run a real preflight on the current branch and record exactly what passed, failed and was skipped
- [x] 3.8 Wire preflight to progress notifications and cancellation; verify cancel kills the Maven process tree on Windows

  Gate met 2026-10-14: `ci-guard` 16/16 tests. Real preflight on `dev` through the built stdio server (jobs branch-policy, secrets, deployment-files, frontend): PASS, 11 steps, 1m39s. Backend job not run to completion (Maven inside the OneDrive folder is known to corrupt class files); started and cancelled after 40 s instead: the Maven JVM was gone after cancel, verdict "CANCELLED: incomplete, not a pass". `typical_time` read 20 real runs (expected 8m53s, p90 12m36s; failover has no history yet, labelled fallback guess).

## 4. risk-engine server (gate: every tool works against the local backend; pause for review)

- [x] 4.1 HTTP adapter with cookie jar, CSRF echo, timeouts, and redaction of cookie values
- [x] 4.2 Read tools: `get_loan`, `list_scenarios`, `portfolio_summary`, `portfolio_loans`, `regime_forecast`, `list_models`, `model_detail`
- [x] 4.3 Compute tools: `score_loan`, `explain_loan`, `term_structure`, `compare_term_structure`, `cvar`, `expected_loss`, `loss_distribution`
- [x] 4.4 `portfolio_run_start` and `portfolio_run_status`, including the conflict result carrying the existing run id
- [x] 4.5 Idempotency keys on supported POSTs, reused across retries
- [x] 4.6 429/quota to typed errors with retry-after; 401 to "session expired"
- [x] 4.7 Result caps with explicit omitted-row counts
- [x] 4.8 Resources (model card, scenario catalogue) and the explain-a-loan prompt
- [x] 4.9 Contract tests against a fake HTTP server for each scenario in `specs/risk-engine-mcp`
- [x] 4.10 Opt-in live smoke test against a local backend; record results
- [x] 4.11 Check whether the backend can produce an OpenAPI document; if so build the drift test from it, otherwise from controller-derived fixtures (generating OpenAPI is a separate approved backend change)
- [x] 4.12 Drift test failing on added required fields and removed endpoints

  Gate met 2026-10-14: `risk-engine` 18/18 contract tests against a fake API and 4/4 drift tests against the Java sources (no OpenAPI in the backend; derived from controllers and records). Live smoke test against an isolated local stack (`docker compose -p admcp`, session minted with its own JWT secret, torn down after): all 16 tools ok, including a portfolio run started, polled to DONE and its loss distribution. **Paused here for the owner's review, as planned.**

## 5. tenant-ops server (gate: cross-tenant leak test passes; pause for review)

- [ ] 5.1 Postgres adapter: read-only transactions, tenant set first via `set_config`, refuse the worker role
- [ ] 5.2 Fixed parameterised queries for run detail, run list and webhook-outbox status with allowlisted filters and bounded limits
- [ ] 5.3 `audit_query` and `usage` via `/admin/audit-log` and `/admin/usage`
- [ ] 5.4 Run diagnosis with the documented heartbeat-staleness threshold
- [ ] 5.5 Untrusted-data fencing for tenant-written fields
- [ ] 5.6 Testcontainers Postgres test: apply migrations, create two tenants, prove isolation and pool-reuse safety; skip loudly when Docker is down
- [ ] 5.7 Protocol tests for each scenario in `specs/tenant-ops-mcp`

## 6. Analyst agent (gate: deterministic suite green; pause for review)

- [ ] 6.1 MCP client connections to servers 1 and 2, with operator mode enabling 3 to 5 explicitly
- [ ] 6.2 Tool-use loop on the Claude API with configurable model and hard step, token, time and cost budgets
- [ ] 6.3 Fixed-tenant session object and the `allowRunStart` flag
- [ ] 6.4 Tool-result store with ids and the citation contract in the system prompt
- [ ] 6.5 Verifier: figure extraction, tolerance and unit rules, one retry, "could not verify" output
- [ ] 6.6 Injection layers: fencing, neutralising instruction-like text, no widening of permissions
- [ ] 6.7 Local run traces without credentials
- [ ] 6.8 Confirm no file under `backend/` changed and `/assistant/chat` is untouched

## 7. Evals (gate: deterministic suite in CI; live suite scored once)

- [ ] 7.1 Replay harness and recorded transcripts for every verifier rule
- [ ] 7.2 Deterministic suite wired to `npm test` with no network or model use
- [ ] 7.3 Live behavioural suite (about 30 questions) with tool-call and answer scoring and a mandatory spending cap
- [ ] 7.4 Injection suite (about 30 cases) covering audit rows, notes, webhook names, cross-tenant and run-start attempts
- [ ] 7.5 Run the live and injection suites once and save the score report
- [ ] 7.6 Tool-selection suite with per-tool and per-server accuracy and a comparison against the previous report
- [ ] 7.7 Record mode that captures real runs into redacted replay fixtures, with a record-then-replay test

## 8. model-validation server

- [ ] 8.1 Artifact checksum comparison against `pd_model_card.json`
- [ ] 8.2 Golden-test runner limited to `TermStructureGoldenTest`, with skipped-not-passed when Maven cannot run
- [ ] 8.3 Command allowlist refusing exports, `fetch_macro.py`, the pipeline and notebook profiles
- [ ] 8.4 Re-run advisory when artifacts or export scripts changed
- [ ] 8.5 Drift and backtest reports from the API
- [ ] 8.6 Tests for each scenario in `specs/model-validation-mcp`

## 9. deploy-ops server

- [ ] 9.1 Prometheus adapter with fixed bounded queries, short timeouts and bounded retries
- [ ] 9.2 Read-only `az` allowlist for VM power state
- [ ] 9.3 Health tool that reports an asleep VM as a normal state
- [ ] 9.4 Idempotent `wake` against the configured wake URL only
- [ ] 9.5 Tests for each scenario in `specs/deploy-ops-mcp`

## 10. Registration, docs and CI

- [ ] 10.1 Repo-root `.mcp.json` registering servers 1 to 5 (no secrets in the file)
- [ ] 10.2 README per server and an architecture and threat-model document
- [ ] 10.3 Two-minute demo script
- [ ] 10.4 Ask the owner, then add an MCP-workspace CI job running the deterministic tests (edits `ci.yml`)
- [ ] 10.5 Final check: every command run and its result reported exactly; commits only when the owner asks, as `hemanthvnp` with no co-author line
