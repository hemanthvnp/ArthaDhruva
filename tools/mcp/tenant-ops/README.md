# tenant-ops MCP server

Read-only operations for **one organization**: why a portfolio run is stuck, whether webhooks are being delivered, what the
audit log shows, how much of the plan is used. It is the third server of `openspec/changes/mcp-servers-and-analyst-agent`
(task group 5).

## Tools

| Tool | Source | What it answers |
|---|---|---|
| `run_list` | Postgres | Recent portfolio runs, newest first, each with a diagnosis. Filter by `status`; sort by `requestedAt`, `startedAt`, `finishedAt`. |
| `run_detail` | Postgres | One run: state, progress, attempt, heartbeat age, and **whether its worker appears dead**. |
| `webhook_outbox` | Postgres | Delivery status: counts per status with the oldest row, and recent rows. |
| `audit_query` | `GET /v1/admin/audit-log` | Recent audit events, filtered by actor, path, time, failures. |
| `usage` | `GET /v1/admin/usage` | Plan, seats, rate limit and this period's metered usage. |

No tool takes a tenant, SQL, or anything that writes.

## Configuration (environment only)

| Variable | |
|---|---|
| `TENANT_OPS_DATABASE_URL` | `postgres://…` as the **application role** (`arthadhruva_app`). Secret. |
| `TENANT_OPS_TENANT_ID` | The organization to serve. Fixed for the life of the process. |
| `RISK_ENGINE_SESSION` | `ad_session` cookie of an **administrator** of that organization (the `/admin` endpoints are administrator-only). Secret. |
| `RISK_ENGINE_URL` | Backend base URL, default `http://localhost:8080`. |
| `TENANT_OPS_TIMEOUT_MS`, `TENANT_OPS_DB_STATEMENT_TIMEOUT_MS` | Default 30000 and 5000. |

`TENANT_OPS_TENANT_ID` and the session must be the same organization. The server cannot check that (the audit and usage
endpoints answer for the session's own organization), so set both from one account.

## How it is kept safe

- **Row-level security does the isolation.** Each call runs in `BEGIN READ ONLY`, sets `app.tenant_id` with
  `set_config(..., true)` (local to the transaction) *first*, runs one fixed statement, and always `ROLLBACK`s. A pooled
  connection therefore carries no tenant from one call to the next. There is deliberately no second `WHERE tenant_id =`: the
  tests must show that the setting alone isolates.
- **The database rejects writes.** A read-only transaction refuses INSERT, UPDATE, DELETE and DDL (SQLSTATE 25006), whatever the
  code does.
- **It will not start as the wrong role.** The worker role (`arthadhruva_worker`, which reads every organization), a superuser,
  and any role with `BYPASSRLS` are refused at startup, since the tenant setting would filter nothing for them.
- **No free-form SQL.** The statements are constants in `src/store.ts`. A caller's value is a bound parameter; the one thing that
  varies in text, the sort column, is chosen from a three-entry map by key. Unknown filters are `InvalidInput` before any query.
- **Untrusted text is fenced.** Usernames, webhook addresses, webhook error text and audit actors/errors are tenant- or
  remote-written. Structured output carries them as `{ untrusted: true, flagged, reasons, text }`; text output wraps them in
  `<<<UNTRUSTED DATA … >>>END UNTRUSTED DATA`, with any fence marker inside the content defused. Instruction-like phrases set
  `flagged`. Flagging is a signal, not a filter: the text is still shown, inside the fence.
- **Audit bodies are not returned.** Request and response JSON and client addresses stay out of results.

## Limits worth knowing

- `/admin/audit-log` takes only `limit` (at most 500) and returns the most recent events. It has no time, actor or paging
  parameters, and this server does not add any (the plan forbids backend changes). `audit_query` filters the page it fetched and
  reports `mayBeTruncated` when the page was full.
- "Worker appears dead" uses the application's own threshold, **120 s** without a heartbeat on a `RUNNING` run
  (`PortfolioRiskService.STALE_AFTER`), measured by the database's clock. Up to 3 attempts, then the run is failed.
- It connects as the application role rather than a dedicated SELECT-only role (design decision D6; a dedicated role needs a
  migration and is an open question). The role has write privileges; the read-only transaction is what stops them being used.
- Residual risk: a model reading fenced text can still be influenced by it. The fence and the flag make that visible; they do not
  make it impossible. This server cannot start a run, change a setting or read another organization, so the worst a successful
  injection can do through *this* server is mislead a reader.

## Tests

`npm test` from `tools/mcp`:

- `test/tenant-ops.test.ts`: protocol tests over the real MCP transport with a fake store and fake HTTP (no network, no Docker).
- `test/postgres.test.ts`: a real Postgres 16 in Testcontainers with the application's own Flyway migrations applied, as the
  application role: tenant A never sees tenant B, a pool of one connection serves two tenants without carrying either's setting,
  an empty tenant value returns nothing, writes are rejected by the database, the worker role and a superuser are refused, a
  stale heartbeat is diagnosed. **Needs Docker; without it the file is skipped, loudly on stderr, never reported as passed.**
