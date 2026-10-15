# ArthaDhruva MCP servers

Developer tooling outside the product: stdio MCP servers over this repository and its risk API, built on one
shared kit. Nothing here is deployed or imported by the backend or the frontend. The plan and the requirements are in
`openspec/changes/mcp-servers-and-analyst-agent/`.

| Package | What it does | State |
|---|---|---|
| `kit/` | Result envelope, typed errors, env-only config, redaction, subprocess allowlist with process-tree kill, read-only guard, annotation checks at registration, progress and cancellation, in-memory test harness | Built, tested |
| `ci-guard/` | Runs this repo's CI checks locally (honest outcomes: skipped is never passed), static platform-drift checks, typical CI time from `gh` history | Built, tested, run on this repo |
| `risk-engine/` | The `/v1` risk API as 16 tools, 2 resources and a prompt | Built, tested against a fake API; drift test against the Java sources |
| `tenant-ops/`, `model-validation/`, `deploy-ops/`, `agent/` | Later sections of the change | Not started |

## Use

```bash
cd tools/mcp
npm ci
npm run build      # tsc -b: kit, ci-guard, risk-engine
npm test           # vitest: every package's tests, no network, no model calls
```

Run a server over stdio: `node ci-guard/dist/cli.js`, `node risk-engine/dist/cli.js`. A client such as Claude Code
starts them itself once they are registered (a repo-root `.mcp.json` is a later task). Configuration is environment
variables only; `.env.example` lists every one.

### risk-engine authentication

The API accepts only the session cookie, and staff accounts need TOTP, so the server cannot sign in on its own. Copy
the `ad_session` cookie of a signed-in browser into `RISK_ENGINE_SESSION`. When it expires, tools answer
`Forbidden: session expired, refresh the session cookie`. Writes carry the double-submit CSRF pair, fetched from the
API when `RISK_ENGINE_XSRF` is unset. Neither value is logged, stored or returned.

For a local stack whose `JWT_SECRET` you set yourself, `scripts/mint-session.mjs` mints a token the same way CI's
load test does. It cannot mint one for a deployment you did not configure.

### Scripts

- `scripts/preflight-live.mjs [jobs] [--cancel-after=N]` drives the built ci-guard server over stdio.
- `scripts/call.mjs <server> <tool> [json]` calls one tool on a built server.
- `scripts/mint-session.mjs` (above).

## Rules the code enforces

- Every subprocess is an allowlist entry with fixed arguments; anything else is refused before spawning.
- A tool that claims `readOnlyHint` cannot use an adapter method marked mutating: registration fails.
- Results and log lines pass through the redactor (configured secrets, cookies, bearer tokens, API keys, JWTs).
- stdout belongs to the protocol; each tool call logs one line to stderr with no payload.
- ci-guard writes only under `.git/ci-guard` and never pushes, commits or edits tracked files.
