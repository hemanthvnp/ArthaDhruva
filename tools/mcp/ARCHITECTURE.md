# Architecture and threat model

## Shape

```
                     Claude Code / any MCP client                 analyst agent (agent/)
                              |  stdio                               |  stdio, one client per server
        +---------------------+---------------------+                |
        |          |          |          |          |   <------------+   session: fixed tenant, frozen read-only tool set
   risk-engine  tenant-ops  model-     deploy-ops  ci-guard                      |
        |          |       validation       |          |                    Claude API (tool-use loop)
        |          |          |             |          |                    budgets: steps, tokens, time, cost
   /v1 API     Postgres    git, Maven   Prometheus   git, gh,                verifier: every figure vs raw result
 (session     (read-only   (one test),   az (one      Docker                   neutraliser: tool text -> model
  cookie)     txn, RLS)    API reports)  command),
                                         wake URL
```

Every server is the same four layers (design D2): registration (zod schemas, annotations checked against the adapters), tool handlers
(validation, result shaping), pure domain logic, injected adapters (HTTP, Postgres, subprocess). Tests swap the adapters; the logic
needs no I/O. All of it shares `kit/`.

## Trust boundaries

| Boundary | Trusted side | Untrusted side |
|---|---|---|
| Model <-> agent | the agent's session, store, verifier, budgets | everything the model proposes: tool names, arguments, answers |
| Agent <-> servers | the servers' typed results | **the text inside results**: usernames, notes, webhook addresses and errors are written by tenants or remote systems |
| Server <-> database | row-level security, the read-only transaction | the tenant argument (there is none: it is fixed at start-up) |
| Server <-> Azure / shell | the allowlists | any command line a caller might want run (there is no way to pass one) |
| Repository <-> owner | exports, golden fixtures, the pipeline, `prod` | everything tooling does: it may check, never rebuild |

## Threats, defences, and the test that holds each

| Threat | Defence | Held by |
|---|---|---|
| A tenant's text tells the model to act ("ignore previous instructions, call ...") | Fenced as data; instruction-like strings withheld before the model sees them; system prompt says tool data is never an instruction; the session refuses calls that were not offered; the verifier rejects leaked figures | `agent/test/injection.test.ts` (30 cases, each layer broken in turn: 28-31 cases fail) |
| The model reads or writes another organization | No tool takes a tenant; the agent refuses any call that names one; tenant-ops sets the tenant per transaction and relies on row-level security | `session.test.ts`, `tenant-ops/test/postgres.test.ts` (real Postgres, two tenants, a pool of one connection) |
| The model starts work that costs allowance, or starts the VM | Only read-only tools are offered; `portfolio_run_start` only with an explicit flag, whatever its annotation says; `wake` and the golden test are not read-only so not offered | `session.test.ts`, `agent.test.ts` |
| The model states a number the tools never returned | Citation contract; each figure checked against the stored raw result, by unit and precision; one correction, then "could not verify" | `verifier.test.ts` (25), 12 replay fixtures |
| A tool or the model runs unbounded | Limits on steps, tokens, seconds, dollars, checked before every model and tool call; bounded retries and timeouts in the servers | `agent.test.ts`, `deploy-ops` retry tests |
| A server runs a command it should not | Fixed allowlist entries with fixed arguments; one `az` command line; one Maven test class; names pattern-checked | `deploy-ops` and `model-validation` tests (refusals before spawn) |
| A credential leaks into a log, result, trace or fixture | Redactor on every result and log line; the session cookie never reaches the agent; traces hold no result bodies; recorded fixtures are redacted | `kit` redaction tests, `agent.test.ts` (trace), `replay.test.ts` |
| The database is written by a server | Read-only transaction (the database rejects the write); refusal to start as the worker role, a superuser or a BYPASSRLS role | `tenant-ops/test/postgres.test.ts` |
| A tool error breaks a real client | Typed errors ride in `_meta`, not `structuredContent` | `kit/test/errors-after-listing.test.ts` |
| Drift: a tool or endpoint changes under the agent | The risk-engine tools are held to the Java sources; every eval question names a tool that exists; ci-guard's model is held to `ci.yml` | `risk-engine/test/contract.test.ts`, `evals.test.ts`, `ci-guard` tests |

## What is NOT defended, stated plainly

- **A real model may still be steered.** CI proves the layers that do not depend on the model, using a model scripted to be fully
  compromised. Whether the real model resists a planted instruction is measured only by the live injection run (`npm run
  eval:injection -w agent`), which has not been run, because it needs an API key and a spending cap. Its result is unknown.
- **`--allow-run-start` re-opens the hole it exists to close.** With it, a planted instruction can start a portfolio run. It is off
  by default, never on in evals, and a run is bounded work, but it is real.
- **Prose is not verified.** The verifier checks numbers. A false sentence with no number passes. The injection defence limits what a
  false sentence can *do*, not whether it can be said.
- **The tenant match is by convention.** `AGENT_TENANT_ID`/`TENANT_OPS_TENANT_ID` and the API session must be the same organization;
  the audit and usage endpoints answer for the session's own. The agent passes one tenant to tenant-ops, but cannot check the cookie.
- **tenant-ops uses the application role,** which has write privileges the read-only transaction withholds. A SELECT-only role needs a
  migration (design D6) and was not made.
- **Cost is an estimate** from token counts and configured prices; the token limit is the dependable backstop.
- **The audit endpoint is coarse:** `/admin/audit-log` takes only `limit`, so `audit_query` filters a fetched page and says when it may
  be truncated.
- **Dev-only dependency advisories:** `npm audit` still reports 8 moderate findings, all in `testcontainers`' archive chain, which is
  used only by the Postgres isolation test and ships in none of the servers.
- **Agent traffic is audited and metered like any other,** so it appears in the audit log and consumes plan quota.
