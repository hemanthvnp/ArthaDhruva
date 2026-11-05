import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import type { CallToolResult } from '@modelcontextprotocol/sdk/types.js';
import { PostgreSqlContainer, type StartedPostgreSqlContainer } from '@testcontainers/postgresql';
import pg from 'pg';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { ToolError, connect, errorOf } from '@arthadhruva/mcp-kit';
import { createServer } from '../src/index.js';
import { PgOpsStore, WORKER_ROLE, assertSafeRole } from '../src/store.js';

/**
 * The isolation guarantees, proved against a real Postgres with the application's own migrations applied, as the
 * application's own non-owner role: the same row-level security the backend relies on. A fake database would prove
 * only that the fake is isolated.
 *
 * It needs Docker. When Docker is not running the whole file is SKIPPED and says so on stderr; a skipped isolation
 * test is not a passed one, and the deterministic protocol tests in tenant-ops.test.ts still run.
 */
const MIGRATIONS = path.resolve(fileURLToPath(new URL('../../../../backend/risk-engine/src/main/resources/db/migration', import.meta.url)));
const dockerUp = spawnSync('docker', ['info'], { stdio: 'ignore', timeout: 20_000, windowsHide: true }).status === 0;
if (!dockerUp) {
  process.stderr.write('\n*** tenant-ops postgres.test.ts SKIPPED: Docker is not running. The cross-tenant isolation tests did NOT run. ***\n\n');
}

const APP_PASSWORD = 'app-test-password';
const WORKER_PASSWORD = 'worker-test-password';
const RUN_A = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
const RUN_B = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb';
const STALE = 'cccccccc-cccc-4ccc-8ccc-cccccccccccc';

function migrations(): Array<{ name: string; sql: string }> {
  return fs.readdirSync(MIGRATIONS)
    .map((name) => ({ name, version: Number(/^V(\d+)__/.exec(name)?.[1]) }))
    .filter((m) => Number.isFinite(m.version))
    .sort((a, b) => a.version - b.version)
    .map((m) => ({
      name: m.name,
      sql: fs.readFileSync(path.join(MIGRATIONS, m.name), 'utf8')
        .replaceAll('${appDbPassword}', APP_PASSWORD).replaceAll('${workerDbPassword}', WORKER_PASSWORD),
    }));
}

describe.skipIf(!dockerUp)('against a real Postgres with the application\'s migrations', () => {
  let container: StartedPostgreSqlContainer;
  let admin: pg.Client;
  let host: string;
  let port: number;
  let tenantA = 0;
  let tenantB = 0;

  const url = (user: string, password: string) => `postgres://${user}:${password}@${host}:${port}/${container.getDatabase()}`;
  const appUrl = () => url('arthadhruva_app', APP_PASSWORD);

  beforeAll(async () => {
    container = await new PostgreSqlContainer('postgres:16-alpine').withDatabase('ad').withUsername('postgres').withPassword('pw').start();
    host = container.getHost();
    port = container.getPort();
    admin = new pg.Client({ connectionString: url('postgres', 'pw') });
    await admin.connect();
    // Flyway keeps this table and V11 revokes on it; the migrations are otherwise plain SQL.
    await admin.query('CREATE TABLE flyway_schema_history (installed_rank int)');
    for (const m of migrations()) {
      try {
        await admin.query(m.sql);
      } catch (e) {
        throw new Error(`migration ${m.name} failed: ${(e as Error).message}`);
      }
    }
    tenantA = Number((await admin.query("INSERT INTO organization (slug, name) VALUES ('tenant-a', 'Tenant A') RETURNING id")).rows[0].id);
    tenantB = Number((await admin.query("INSERT INTO organization (slug, name) VALUES ('tenant-b', 'Tenant B') RETURNING id")).rows[0].id);
    const insertRun = `INSERT INTO portfolio_risk_run (id, tenant_id, requested_by, status, scenarios, loans_total, loans_done, heartbeat_at, attempt)
      VALUES ($1, $2, $3, $4, 'BASELINE', 100, $5, now() - ($6 || ' seconds')::interval, $7)`;
    // one active run per organization is a database rule: tenant A has a finished run and a stalled one
    await admin.query(insertRun, [RUN_A, tenantA, 'alice', 'COMPLETED', 100, '5', 1]);
    await admin.query(insertRun, [RUN_B, tenantB, 'bob', 'RUNNING', 10, '5', 1]);
    await admin.query(insertRun, [STALE, tenantA, 'alice', 'RUNNING', 70, '400', 1]);
    for (const [tenant, who] of [[tenantA, 'a'], [tenantB, 'b']] as const) {
      const sub = (await admin.query("INSERT INTO webhook_subscription (tenant_id, url, event_types, secret) VALUES ($1, $2, 'RUN_COMPLETED', 's') RETURNING id",
        [tenant, `https://hooks.example/${who}`])).rows[0].id;
      await admin.query("INSERT INTO webhook_outbox (event_id, tenant_id, subscription_id, event_type, payload, status, attempts, last_error) VALUES (gen_random_uuid(), $1, $2, 'RUN_COMPLETED', '{}', 'FAILED', 3, $3)",
        [tenant, sub, `endpoint of ${who} answered 500`]);
    }
  }, 300_000);

  afterAll(async () => {
    await admin?.end();
    await container?.stop();
  });

  const text = (r: CallToolResult) => (r.content[0] as { text: string }).text;

  it('a tool for tenant A never shows a row of tenant B', async () => {
    const store = await PgOpsStore.open(appUrl(), tenantA, { maxConnections: 2, statementTimeoutMs: 5000 });
    try {
      const ids = (await store.runList({ status: undefined, orderBy: 'requested_at', limit: 50 })).map((r) => r.id).sort();
      expect(ids).toEqual([RUN_A, STALE].sort());
      expect(await store.runDetail(RUN_B)).toBeNull();
      expect((await store.outboxRows({ status: undefined, limit: 50 })).map((r) => r.url)).toEqual(['https://hooks.example/a']);
      expect(await store.outboxCounts()).toMatchObject([{ status: 'FAILED', count: 1 }]);
    } finally {
      await store.close();
    }
  });

  it('through the protocol, tenant B\'s run id is NotFound for tenant A', async () => {
    const store = await PgOpsStore.open(appUrl(), tenantA, { maxConnections: 2, statementTimeoutMs: 5000 });
    const { client, close } = await connect(await createServer({
      TENANT_OPS_DATABASE_URL: appUrl(), TENANT_OPS_TENANT_ID: String(tenantA), RISK_ENGINE_SESSION: 'a.b.c',
    }, { store }));
    try {
      const r = (await client.callTool({ name: 'run_detail', arguments: { runId: RUN_B } })) as CallToolResult;
      expect(errorOf(r)?.code).toBe('NotFound');
      expect(JSON.stringify(r)).not.toContain('bob');
    } finally {
      await close();
      await store.close();
    }
  });

  it('two tenants sharing ONE pooled connection each see only their own rows, in any order', async () => {
    const pool = new pg.Pool({ connectionString: appUrl(), max: 1 });
    try {
      const a = new PgOpsStore(pool, tenantA);
      const b = new PgOpsStore(pool, tenantB);
      const filter = { status: undefined, orderBy: 'requested_at' as const, limit: 50 };
      const pids = new Set<number>();
      const pid = async () => pids.add(Number((await pool.query('SELECT pg_backend_pid() AS pid')).rows[0].pid));
      for (const turn of ['a', 'b', 'b', 'a', 'b', 'a']) {
        const store = turn === 'a' ? a : b;
        const rows = await store.runList(filter);
        expect(rows.map((r) => r.requestedBy), `turn ${turn}`).not.toContain(turn === 'a' ? 'bob' : 'alice');
        expect(rows.length).toBe(turn === 'a' ? 2 : 1);
        await pid();
      }
      expect(pids.size, 'a pool of one reuses the same backend, so this really tested reuse').toBe(1);
      // and the tenant does not stay behind on the connection once the call has ended
      const left = (await pool.query("SELECT current_setting('app.tenant_id', true) AS t")).rows[0].t;
      expect(left === null || left === '').toBe(true);
      expect((await pool.query('SELECT count(*)::int AS n FROM portfolio_risk_run')).rows[0].n, 'no tenant set means no rows').toBe(0);
    } finally {
      await pool.end();
    }
  });

  it('an empty tenant value returns no tenant rows', async () => {
    const pool = new pg.Pool({ connectionString: appUrl(), max: 1 });
    try {
      for (const table of ['portfolio_risk_run', 'webhook_outbox', 'webhook_subscription']) {
        const c = await pool.connect();
        try {
          await c.query('BEGIN READ ONLY');
          await c.query("SELECT set_config('app.tenant_id', '', true)");
          expect((await c.query(`SELECT count(*)::int AS n FROM ${table}`)).rows[0].n, table).toBe(0);
        } finally {
          await c.query('ROLLBACK').catch(() => undefined);
          c.release();
        }
      }
    } finally {
      await pool.end();
    }
  });

  it('a write through the connection is rejected by the database because the transaction is read-only', async () => {
    const store = await PgOpsStore.open(appUrl(), tenantA, { maxConnections: 1, statementTimeoutMs: 5000 });
    try {
      const attempts = [
        "INSERT INTO portfolio_risk_run (id, tenant_id, requested_by, status, scenarios, loans_total) VALUES (gen_random_uuid(), " + tenantA + ", 'x', 'QUEUED', 'B', 1)",
        "UPDATE portfolio_risk_run SET status = 'FAILED'",
        'DELETE FROM portfolio_risk_run',
        'CREATE TABLE should_not_exist (x int)',
      ];
      for (const sql of attempts) {
        const error = await (store as unknown as { inTenant: (w: (c: pg.PoolClient) => Promise<unknown>) => Promise<unknown> })
          .inTenant((c) => c.query(sql)).then(() => null, (e: unknown) => e);
        expect(error, sql).toBeInstanceOf(ToolError);
        expect((error as ToolError).details, sql).toMatchObject({ sqlState: '25006' });   // read_only_sql_transaction
      }
      const after = (await admin.query('SELECT count(*)::int AS n FROM portfolio_risk_run')).rows[0].n;
      expect(after).toBe(3);
      expect((await admin.query("SELECT to_regclass('should_not_exist') AS t")).rows[0].t).toBeNull();
    } finally {
      await store.close();
    }
  });

  it('refuses to start as the worker role, and as a superuser', async () => {
    await expect(PgOpsStore.open(url(WORKER_ROLE, WORKER_PASSWORD), tenantA, { maxConnections: 1, statementTimeoutMs: 5000 }))
      .rejects.toThrow(/arthadhruva_worker/);
    await expect(PgOpsStore.open(url('postgres', 'pw'), tenantA, { maxConnections: 1, statementTimeoutMs: 5000 }))
      .rejects.toThrow(/bypasses row-level security/);
    const pool = new pg.Pool({ connectionString: appUrl(), max: 1 });
    try {
      await expect(assertSafeRole(pool)).resolves.toBeUndefined();
    } finally {
      await pool.end();
    }
  });

  it('the worker role really can see every tenant, which is why it is refused', async () => {
    const pool = new pg.Pool({ connectionString: url(WORKER_ROLE, WORKER_PASSWORD), max: 1 });
    try {
      expect((await pool.query('SELECT count(*)::int AS n FROM portfolio_risk_run')).rows[0].n).toBe(3);
    } finally {
      await pool.end();
    }
  });

  it('diagnoses a stale heartbeat from the database\'s clock, and not a fresh one', async () => {
    const store = await PgOpsStore.open(appUrl(), tenantA, { maxConnections: 2, statementTimeoutMs: 5000 });
    const { client, close } = await connect(await createServer({
      TENANT_OPS_DATABASE_URL: appUrl(), TENANT_OPS_TENANT_ID: String(tenantA), RISK_ENGINE_SESSION: 'a.b.c',
    }, { store }));
    try {
      const stale = (await client.callTool({ name: 'run_detail', arguments: { runId: STALE } })) as CallToolResult;
      expect(stale.structuredContent).toMatchObject({ workerAppearsDead: true, status: 'RUNNING', loansDone: 70 });
      expect((stale.structuredContent as { heartbeatAgeSec: number }).heartbeatAgeSec).toBeGreaterThan(399);
      expect(text(stale)).toContain('WORKER APPEARS DEAD');
      const list = (await client.callTool({ name: 'run_list', arguments: { status: 'running' } })) as CallToolResult;
      expect(text(list)).toContain('1 with a worker that appears dead');
    } finally {
      await close();
      await store.close();
    }
  });

  it('does not flag a running run whose heartbeat is fresh', async () => {
    const store = await PgOpsStore.open(appUrl(), tenantB, { maxConnections: 2, statementTimeoutMs: 5000 });
    const { client, close } = await connect(await createServer({
      TENANT_OPS_DATABASE_URL: appUrl(), TENANT_OPS_TENANT_ID: String(tenantB), RISK_ENGINE_SESSION: 'a.b.c',
    }, { store }));
    try {
      const fresh = (await client.callTool({ name: 'run_detail', arguments: { runId: RUN_B } })) as CallToolResult;
      expect(fresh.structuredContent).toMatchObject({ workerAppearsDead: false, status: 'RUNNING' });
      expect((fresh.structuredContent as { heartbeatAgeSec: number }).heartbeatAgeSec).toBeLessThan(60);
    } finally {
      await close();
      await store.close();
    }
  });

  it('the webhook outbox error text comes back fenced', async () => {
    const store = await PgOpsStore.open(appUrl(), tenantA, { maxConnections: 2, statementTimeoutMs: 5000 });
    const { client, close } = await connect(await createServer({
      TENANT_OPS_DATABASE_URL: appUrl(), TENANT_OPS_TENANT_ID: String(tenantA), RISK_ENGINE_SESSION: 'a.b.c',
    }, { store }));
    try {
      const r = (await client.callTool({ name: 'webhook_outbox', arguments: {} })) as CallToolResult;
      expect(text(r)).toContain('<<<UNTRUSTED DATA (lastError)');
      expect(text(r)).toContain('endpoint of a answered 500');
      expect(text(r)).not.toContain('endpoint of b');
    } finally {
      await close();
      await store.close();
    }
  });
});
