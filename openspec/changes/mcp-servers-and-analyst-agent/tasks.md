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

- [x] 5.1 Postgres adapter: read-only transactions, tenant set first via `set_config`, refuse the worker role (done 2026-11-04: tools/mcp/tenant-ops/src/store.ts: BEGIN READ ONLY, tenant set first with transaction-local set_config, always ROLLBACK; refuses the worker role, a superuser and BYPASSRLS at startup)
- [x] 5.2 Fixed parameterised queries for run detail, run list and webhook-outbox status with allowlisted filters and bounded limits (done 2026-11-04: fixed statements with bound parameters; status and sort column from allowlists, limits bounded; unknown values are InvalidInput before any query. Run list and detail, webhook outbox counts and rows)
- [x] 5.3 `audit_query` and `usage` via `/admin/audit-log` and `/admin/usage` (done 2026-11-04: `audit_query` and `usage`. /admin/audit-log takes only `limit`, so actor, path and time are filtered on the fetched page and the result reports `mayBeTruncated`; request/response bodies and client addresses are not returned)
- [x] 5.4 Run diagnosis with the documented heartbeat-staleness threshold (done 2026-11-04: `run_detail` and `run_list` diagnose with the application's 120 s threshold by the database's clock; up to 3 attempts)
- [x] 5.5 Untrusted-data fencing for tenant-written fields (done 2026-11-04: src/fence.ts: structured `untrusted` objects with `flagged` and reasons, fenced text, forged fence markers defused)
- [x] 5.6 Testcontainers Postgres test: apply migrations, create two tenants, prove isolation and pool-reuse safety; skip loudly when Docker is down (done 2026-11-04: test/postgres.test.ts: Postgres 16 in Testcontainers with all Flyway migrations, as the application role; skipped loudly on stderr when Docker is down. Mutation-checked: dropping READ ONLY, dropping the role check, and a tenant setting that survives the call each fail a test)
- [x] 5.7 Protocol tests for each scenario in `specs/tenant-ops-mcp` (done 2026-11-04: test/tenant-ops.test.ts covers every scenario of specs/tenant-ops-mcp (23 protocol tests); the database ones are in postgres.test.ts)

## 6. Analyst agent (gate: deterministic suite green; pause for review)

- [x] 6.1 MCP client connections to servers 1 and 2, with operator mode enabling 3 to 5 explicitly (done 2026-11-04: tools/mcp/agent/src/host.ts: an MCP client per server, over stdio (tested against a real stdio server) or in-process; default servers are risk-engine and tenant-ops, `--operator` adds model-validation, deploy-ops and ci-guard; each server is given only its own variables)
- [x] 6.2 Tool-use loop on the Claude API with configurable model and hard step, token, time and cost budgets (done 2026-11-04: src/agent.ts and src/budget.ts: a manual tool-use loop on the Claude API through the official SDK (default claude-opus-5-5, adaptive thinking, effort high, refusal fallback on) with limits on model calls, tokens, seconds and estimated dollars; the first reached stops the question and is named)
- [x] 6.3 Fixed-tenant session object and the `allowRunStart` flag (done 2026-11-04: src/session.ts: the tenant is fixed at construction and the tool set is frozen; a call naming another tenant is refused before any server is asked; `portfolio_run_start` is offered only with `--allow-run-start`, whatever its annotation says)
- [x] 6.4 Tool-result store with ids and the citation contract in the system prompt (done 2026-11-04: src/store.ts and src/prompt.ts: every result kept whole under an id (r1, r2, ...); the system prompt states the citation contract and that tool data is never an instruction)
- [x] 6.5 Verifier: figure extraction, tolerance and unit rules, one retry, "could not verify" output (done 2026-11-04: src/verifier.ts: figure extraction, unit and precision rules (percent against a fraction or a percentage, bps, $/k/m/bn), one correction, then "could not verify" naming the figures; 25 rule tests and 12 replay fixtures. Numbers only: claims in words are not verified)
- [x] 6.6 Injection layers: fencing, neutralising instruction-like text, no widening of permissions (done 2026-11-04: src/neutralise.ts: fenced results, instruction-like strings withheld before the model sees them, the frozen read-only tool set, and the verifier; 30 adversarial cases, and each layer broken in turn fails 28 to 31 of them)
- [x] 6.7 Local run traces without credentials (done 2026-11-04: src/trace.ts: a local JSON trace per question (steps, tool calls with outcomes, tokens, estimated cost, verdict), no result bodies, redacted; tested with a secret placed in a call)
- [x] 6.8 Confirm no file under `backend/` changed and `/assistant/chat` is untouched (done 2026-11-04: git diff --stat origin/dev -- backend/ is empty for this work, and `/assistant/chat` is untouched)

## 7. Evals (gate: deterministic suite in CI; live suite scored once)

- [x] 7.1 Replay harness and recorded transcripts for every verifier rule (done 2026-11-04: src/replay.ts and src/fixtures.ts: a scripted model and a recorded host replay a fixture through the real loop; 12 JSON fixtures in agent/test/fixtures, one per verifier rule plus the step limit, a refused run start, a refused cross-tenant call and an injection)
- [x] 7.2 Deterministic suite wired to `npm test` with no network or model use (done 2026-11-04: agent/test/replay.test.ts runs every fixture by name under `npm test` and in the CI job `mcp`, with fetch stubbed to throw so any network use fails it)
- [x] 7.3 Live behavioural suite (about 30 questions) with tool-call and answer scoring and a mandatory spending cap (done 2026-11-04: built, not run: src/evals/runner.ts (mandatory spending cap, stops mid-run when it is reached, reports the finished subset and marks the run incomplete) and 30 questions in agent/evals/questions.json; `npm run eval:live -w agent`)
- [x] 7.4 Injection suite (about 30 cases) covering audit rows, notes, webhook names, cross-tenant and run-start attempts (done 2026-11-04: src/injection-cases.ts: 30 cases (audit row, loan note, webhook error x 10 payloads) driven in CI by a scripted compromised model (agent/test/injection.test.ts), and by the real model in `npm run eval:injection -w agent` (built, not run))
- [ ] 7.5 Run the live and injection suites once and save the score report (NOT DONE: needs an Anthropic API key, a spending cap and a running stack, none of which the build had; the suites are built and tested with scripted models. The three scores the report would hold are unknown)
- [x] 7.6 Tool-selection suite with per-tool and per-server accuracy and a comparison against the previous report (done 2026-11-04: built, not run: `npm run eval:selection -w agent` scores tool choice apart from the answer, per tool and per server, and prints the change against the previous completed report)
- [x] 7.7 Record mode that captures real runs into redacted replay fixtures, with a record-then-replay test (done 2026-11-04: RecordingModel and RecordingHost capture a real run into a redacted fixture; agent/test/replay.test.ts records, replays offline, and checks the same verdict and no credential in the file)

## 8. model-validation server

- [x] 8.1 Artifact checksum comparison against `pd_model_card.json` (done 2026-11-04: `verify_artifacts` checks seven artifact/card pairs (PD, survival, delinquency, regime chain, early warning and its calibration, segment graph); the repository's own pass today, and a tampered one is a mismatch with both checksums)
- [x] 8.2 Golden-test runner limited to `TermStructureGoldenTest`, with skipped-not-passed when Maven cannot run (done 2026-11-04: `run_golden_test`: the fixed Maven command, passed/failed/skipped, skipped and never passed when the wrapper cannot run; run once for real through the server (passed, 2 tests, 42.9 s). That run found that cmd.exe cannot resolve a bare `mvnw.cmd` here (NoDefaultCurrentDirectoryInExePath=1), so the path is `.\mvnw.cmd`, also fixed in ci-guard)
- [x] 8.3 Command allowlist refusing exports, `fetch_macro.py`, the pipeline and notebook profiles (done 2026-11-04: src/forbidden.ts: exports, fetch_macro, dataset builds, golden and validate scripts, the pipeline, notebooks and profiles are Forbidden before spawning, saying the owner must run them)
- [x] 8.4 Re-run advisory when artifacts or export scripts changed (done 2026-11-04: `rerun_advisory` against a base ref, from what each golden script actually reads; never runs a script)
- [x] 8.5 Drift and backtest reports from the API (done 2026-11-04: `drift_report` and `survival_backtest` pass the API's reports through unaltered with source and fetch time)
- [x] 8.6 Tests for each scenario in `specs/model-validation-mcp` (done 2026-11-04: test/model-validation.test.ts, 23 tests covering each scenario of specs/model-validation-mcp; mutation-checked (no forbidden commands, and every checksum matching, each fail a test))

## 9. deploy-ops server

- [x] 9.1 Prometheus adapter with fixed bounded queries, short timeouts and bounded retries (done 2026-11-04: tools/mcp/deploy-ops: `metric_query` takes a metric name from a fixed catalog (the alert rules' own expressions), `alert_state` reads /api/v1/alerts; 3 s timeout, at most 3 retries with doubling backoff, so the worst case is stated (9.6 s by default))
- [x] 9.2 Read-only `az` allowlist for VM power state (done 2026-11-04: src/azure.ts: exactly one az command line is allowed; every other one, mutating or not, is Forbidden before a process is spawned; names are pattern-checked at start-up)
- [x] 9.3 Health tool that reports an asleep VM as a normal state (done 2026-11-04: `health` reports a deallocated or stopped VM as `asleep`, normal, with a hint, and does not ask the application or Prometheus; without a power source an unanswering application is `unknown`, not a failure)
- [x] 9.4 Idempotent `wake` against the configured wake URL only (done 2026-11-04: `wake` reads /status, asks for /wake only from a deallocated or stopped VM, reports already-running, already-starting, started-by-this-call or going-to-sleep, and only ever requests the configured URL's two paths)
- [x] 9.5 Tests for each scenario in `specs/deploy-ops-mcp` (done 2026-11-04: test/deploy-ops.test.ts, 26 tests covering each scenario of specs/deploy-ops-mcp; mutation-checked (allowing any az command, and wake starting unconditionally, each fail tests))

## 10. Registration, docs and CI

- [x] 10.1 Repo-root `.mcp.json` registering servers 1 to 5 (no secrets in the file) (done 2026-11-04: .mcp.json registers all five servers with references to environment variables only (no secrets))
- [x] 10.2 README per server and an architecture and threat-model document (done 2026-11-04: a README in each package, tools/mcp/README.md, and tools/mcp/ARCHITECTURE.md with the threat model, including what is NOT defended)
- [x] 10.3 Two-minute demo script (done 2026-11-04: tools/mcp/DEMO.md)
- [x] 10.4 Ask the owner, then add an MCP-workspace CI job running the deterministic tests (edits `ci.yml`) (done 2026-11-04: the owner, asked whether to continue with the agent and the CI job, said "do everything": job `mcp` added to ci.yml (build and `npm test`, no model, no live suite) and to ci-guard's job model, whose drift test holds the two together)
- [ ] 10.5 Final check: every command run and its result reported exactly; commits only when the owner asks, as `hemanthvnp` with no co-author line
