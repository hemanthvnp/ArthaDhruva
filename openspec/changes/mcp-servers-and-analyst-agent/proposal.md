## Why

The platform has rich live state (scoring, expected loss, CVaR, portfolio runs, audit, deploy health, CI) that an AI assistant cannot reach except by reading files. Exposing it through MCP servers makes that state queryable in plain language, removes repeated manual steps for the developer (pre-push CI checks, model-contract checks, deploy health), and gives the project an AI-native interface that is safe by construction: read-only by default, tenant-scoped, and verified.

The existing `/assistant/chat` endpoint is a single-shot LLM answer about one loan. It cannot call the engines, chain steps, or prove its numbers. A tool-using analyst agent with a grounding verifier and an eval suite is the missing, distinctive piece.

## What Changes

- Add a TypeScript MCP workspace under `tools/mcp/` with a shared `kit` and five stdio servers:
  - `risk-engine`: scoring, explanation, term structure, CVaR, expected loss, portfolio runs, model catalogue (HTTP `/v1`).
  - `tenant-ops`: audit, usage, portfolio-run diagnosis, webhook outbox (read-only, tenant-scoped, RLS-respecting).
  - `model-validation`: artifact checksums against the model card, golden-test run, drift and backtest reports.
  - `deploy-ops`: Prometheus metrics, alert state, Azure VM power state, health, wake.
  - `ci-guard`: local pre-push preflight mirroring `ci.yml` and `security.yml`, static Windows-vs-Linux checks, typical CI time.
- Add an external, multi-step analyst agent (`tools/mcp/agent/`) that composes servers 1 and 2 (and 3 to 5 in an explicit operator mode) with hard step, token, time and cost budgets, a per-answer citation contract, and a verifier that rejects unsupported figures.
- Add an eval suite (`tools/mcp/evals/`): a deterministic replay suite for CI, an opt-in live behavioural suite, and a prompt-injection suite.
- Register servers 1 to 5 in a repo-root `.mcp.json`.
- Add per-server READMEs, an architecture and threat-model document, and a short demo script.
- Optionally add a CI job for the MCP workspace (requires owner approval before editing `ci.yml`).
- No change to backend behaviour, schema, or existing endpoints. No new database role unless separately approved.

## Capabilities

### New Capabilities
- `mcp-kit`: shared result envelope, typed errors, env-only validated config, redaction, subprocess allowlist, read-only guard, call logging, in-memory test harness.
- `risk-engine-mcp`: tools, resources and prompt exposing the `/v1` risk API with session-cookie and CSRF handling, run start/status separation, pagination, and typed backoff on rate limit or quota.
- `tenant-ops-mcp`: read-only, tenant-scoped operator diagnostics with RLS-faithful tenant context and untrusted-data fencing.
- `model-validation-mcp`: checksum, golden-test and drift/backtest checks under a strict command allowlist that never runs exports or the pipeline.
- `deploy-ops-mcp`: read-only deployment and observability queries plus wake, with an allowlist that excludes production-infrastructure changes.
- `ci-guard-mcp`: async local preflight mirroring CI, static checks for failures that pass on Windows but fail on Linux, skipped-not-passed semantics for unavailable tools.
- `analyst-agent`: budgeted tool-using loop, fixed tenant per session, citation contract, grounding verifier, injection handling, run traces.
- `agent-evals`: deterministic replay suite, live behavioural suite, injection suite, scoring and CI gating rules.

### Modified Capabilities
<!-- None. No existing specs; backend requirements are unchanged. -->

## Impact

- **New code:** `tools/mcp/**` (TypeScript, Node), `.mcp.json`, docs. Nothing under `backend/`, `frontend/`, `pipeline/` or deployment files changes.
- **Existing systems touched at runtime (read paths):** the `/v1` API (audited, metered, rate-limited, so agent traffic appears in audit and usage), Postgres (read-only, tenant-set-first), Prometheus, Azure CLI (read-only subcommands), git/gh/Docker locally.
- **Dependencies:** `@modelcontextprotocol/sdk`, `zod`, the Anthropic SDK (agent only), a Postgres client, a test runner; a Postgres container for the tenant-leak test (skipped when Docker is down).
- **Cost and quota:** live evals and agent runs cost model tokens and consume plan quota; both are capped by configuration.
- **Overlap to resolve:** the `borrower-portal-and-safety-assistant` change and the existing `/assistant/chat` also involve LLM use. This change is deliberately separate (external, developer/analyst-facing, multi-step) and must not modify the in-product assistant.
- **Out of scope:** consumer-facing hosted MCP, OAuth, remote transport, any backend schema or role migration, any production-infrastructure change.
