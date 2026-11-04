# ArthaDhruva MCP servers and analyst agent

Developer tooling outside the product: five stdio MCP servers over this repository and its risk API, one shared kit, and an
analyst agent that composes them under budgets and checks every figure it states. Nothing here is deployed or imported by the
backend or the frontend. The plan and requirements are in `openspec/changes/mcp-servers-and-analyst-agent/`; how it fits
together and what it defends against is in [ARCHITECTURE.md](ARCHITECTURE.md); a two-minute tour is [DEMO.md](DEMO.md).

| Package | What it does |
|---|---|
| [`kit/`](kit) | Result envelope, typed errors (carried in `_meta`), env-only config, redaction, subprocess allowlist with process-tree kill, read-only guard, annotation checks at registration, progress and cancellation, in-memory test harness |
| [`risk-engine/`](risk-engine) | The `/v1` risk API as 16 tools, 2 resources and a prompt: scoring, term structure, loss distribution, portfolio runs, model catalogue |
| [`tenant-ops/`](tenant-ops/README.md) | One organization's operations, read-only: run diagnosis, webhook outbox, audit log, usage. Row-level security does the isolation |
| [`model-validation/`](model-validation/README.md) | Artifact checksums against model cards, the term-structure golden test, drift and backtest reports. Never exports or retrains |
| [`deploy-ops/`](deploy-ops/README.md) | Deployment health (an asleep VM is normal), named Prometheus metrics, alerts, and an idempotent `wake` |
| [`ci-guard/`](ci-guard) | Runs this repo's CI checks locally (skipped is never passed), static platform-drift checks, typical CI time |
| [`agent/`](agent/README.md) | The analyst agent: a budgeted tool-use loop on the Claude API, one fixed tenant, read-only, every figure cited and verified |

## Use

```bash
cd tools/mcp
npm ci
npm run build      # tsc -b: every package
npm test           # vitest: every package, no model calls; Docker is needed for the tenant-ops isolation test
```

The deterministic tests run in CI (job `mcp`). The tenant-ops isolation test needs Docker and is **skipped, loudly, not passed**
without it. The agent's live suites cost money and never run in CI ([agent/evals/README.md](agent/evals/README.md)).

### Using the servers from Claude Code

`.mcp.json` at the repository root registers all five servers. It holds no secrets, only references to environment variables
(`RISK_ENGINE_SESSION`, `TENANT_OPS_DATABASE_URL`, ...); set them in your shell, build first (`npm run build`), and approve the
servers when Claude Code asks. `.env.example` lists every variable of every package.

### Asking the analyst agent

```bash
export ANTHROPIC_API_KEY=...            # or: ant auth login
export AGENT_TENANT_ID=7 RISK_ENGINE_SESSION=... TENANT_OPS_DATABASE_URL=...
node agent/dist/cli.js ask "Why is our portfolio run stuck?"
```

Default servers are risk-engine and tenant-ops; `--operator` adds model-validation, deploy-ops and ci-guard. Details, budgets and exit
codes: [agent/README.md](agent/README.md).

### risk-engine authentication

The API accepts only the session cookie, and staff accounts need TOTP, so no server can sign in on its own. Copy the `ad_session`
cookie of a signed-in browser into `RISK_ENGINE_SESSION`. When it expires, tools answer `Forbidden: session expired, refresh the
session cookie`. Writes carry the double-submit CSRF pair, fetched from the API when `RISK_ENGINE_XSRF` is unset. Neither value is
logged, stored or returned. For a local stack whose `JWT_SECRET` you set yourself, `scripts/mint-session.mjs` mints a token the same
way CI's load test does.

### Scripts

- `scripts/preflight-live.mjs [jobs] [--cancel-after=N]` drives the built ci-guard server over stdio.
- `scripts/call.mjs <server> <tool> [json]` calls one tool on a built server.
- `scripts/mint-session.mjs` (above).

## Rules the code enforces

- Every subprocess is an allowlist entry with fixed arguments; anything else is refused before spawning.
- A tool that claims `readOnlyHint` cannot use an adapter method marked mutating: registration fails.
- Results and log lines pass through the redactor (configured secrets, cookies, bearer tokens, API keys, JWTs).
- stdout belongs to the protocol; each tool call logs one line to stderr with no payload.
- A tool error is a typed result (`isError`, code in `_meta`) and never carries `structuredContent`, so a client that has listed the
  tools does not reject it against the output schema.
- ci-guard writes only under `.git/ci-guard` and never pushes, commits or edits tracked files; model-validation never runs an
  export; deploy-ops runs exactly one `az` command; tenant-ops runs every query in a read-only transaction with the tenant set first.
