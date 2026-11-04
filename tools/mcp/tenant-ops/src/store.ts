import pg from 'pg';
import { forbidden, ToolError } from '@arthadhruva/mcp-kit';
import type { OUTBOX_STATUSES, RUN_ORDER, RUN_STATUSES } from './filters.js';

/** The role the application's background jobs use. It reads every organization's rows; tooling must never be it. */
export const WORKER_ROLE = 'arthadhruva_worker';

export interface RunRow {
  id: string;
  status: string;
  scenarios: string;
  loansTotal: number;
  loansDone: number;
  requestedBy: string;
  requestedAt: string;
  startedAt: string | null;
  heartbeatAt: string;
  finishedAt: string | null;
  error: string | null;
  attempt: number;
  deferred: boolean;
  /** Seconds since the heartbeat, by the database's clock. */
  heartbeatAgeSec: number;
}

export interface OutboxCount {
  status: string;
  count: number;
  oldest: string;
}

export interface OutboxRow {
  id: number;
  eventType: string;
  status: string;
  attempts: number;
  nextAttemptAt: string;
  lastError: string | null;
  createdAt: string;
  deliveredAt: string | null;
  url: string;
}

/**
 * What the tools read, for one organization, as a port: the Postgres adapter below is the only implementation that
 * touches a database, and tests give the tools a fake. There is nothing here that changes data, and no method takes
 * SQL or a tenant: the tenant is fixed when the store is made.
 */
export interface OpsStore {
  runList(filter: { status: (typeof RUN_STATUSES)[number] | undefined; orderBy: (typeof RUN_ORDER)[keyof typeof RUN_ORDER]; limit: number }): Promise<RunRow[]>;
  runDetail(id: string): Promise<RunRow | null>;
  outboxCounts(): Promise<OutboxCount[]>;
  outboxRows(filter: { status: (typeof OUTBOX_STATUSES)[number] | undefined; limit: number }): Promise<OutboxRow[]>;
}

const RUN_COLUMNS = `id, status, scenarios, loans_total, loans_done, requested_by, requested_at, started_at, heartbeat_at, finished_at,
  error, attempt, deferred, EXTRACT(EPOCH FROM (now() - heartbeat_at))::float8 AS heartbeat_age_sec`;

/**
 * The fixed statements. A caller's value is only ever a bound parameter; the one thing that varies in text, the sort
 * column, is picked from RUN_ORDER by key (filters.ts) and so is one of three constants. Row-level security does the
 * tenant filtering, deliberately without a second `tenant_id =` here: the tests must show that the tenant setting
 * alone isolates, not an extra WHERE that would hide a policy that stopped working.
 */
const SQL = {
  runList: (orderColumn: string) =>
    `SELECT ${RUN_COLUMNS} FROM portfolio_risk_run WHERE ($1::text IS NULL OR status = $1) ORDER BY ${orderColumn} DESC NULLS LAST, id LIMIT $2`,
  runDetail: `SELECT ${RUN_COLUMNS} FROM portfolio_risk_run WHERE id = $1`,
  outboxCounts: `SELECT status, count(*)::int AS n, min(created_at) AS oldest FROM webhook_outbox GROUP BY status ORDER BY status`,
  outboxRows: `SELECT o.id, o.event_type, o.status, o.attempts, o.next_attempt_at, o.last_error, o.created_at, o.delivered_at, s.url
    FROM webhook_outbox o JOIN webhook_subscription s ON s.id = o.subscription_id
    WHERE ($1::text IS NULL OR o.status = $1) ORDER BY o.created_at DESC, o.id DESC LIMIT $2`,
} as const;

const iso = (v: unknown): string => (v instanceof Date ? v.toISOString() : String(v));
const isoOrNull = (v: unknown): string | null => (v === null || v === undefined ? null : iso(v));

function toRun(r: Record<string, unknown>): RunRow {
  return {
    id: String(r.id),
    status: String(r.status),
    scenarios: String(r.scenarios),
    loansTotal: Number(r.loans_total),
    loansDone: Number(r.loans_done),
    requestedBy: String(r.requested_by),
    requestedAt: iso(r.requested_at),
    startedAt: isoOrNull(r.started_at),
    heartbeatAt: iso(r.heartbeat_at),
    finishedAt: isoOrNull(r.finished_at),
    error: r.error === null || r.error === undefined ? null : String(r.error),
    attempt: Number(r.attempt),
    deferred: Boolean(r.deferred),
    heartbeatAgeSec: Number(r.heartbeat_age_sec),
  };
}

export interface PgOptions {
  maxConnections: number;
  statementTimeoutMs: number;
}

export class PgOpsStore implements OpsStore {
  /**
   * @param tenantId the organization this store reads, fixed for its lifetime; the pool may be shared, which is why the
   *                 tenant is set again, inside the transaction, for every call
   */
  constructor(private readonly pool: pg.Pool, private readonly tenantId: number, private readonly statementTimeoutMs = 5000) {
    if (!Number.isSafeInteger(tenantId) || tenantId <= 0) throw new Error('tenantId must be a positive integer');
  }

  /** Connects, refuses an unsafe role, and returns a store; the pool is closed with {@link close}. */
  static async open(connectionString: string, tenantId: number, options: PgOptions): Promise<PgOpsStore> {
    const pool = new pg.Pool({ connectionString, max: options.maxConnections, application_name: 'tenant-ops-mcp' });
    try {
      await assertSafeRole(pool);
    } catch (e) {
      await pool.end().catch(() => undefined);
      throw e;
    }
    return new PgOpsStore(pool, tenantId, options.statementTimeoutMs);
  }

  async close(): Promise<void> {
    await this.pool.end();
  }

  /**
   * One read-only transaction with the tenant set first. `set_config(..., true)` is local to the transaction, so
   * when it ends (always by ROLLBACK, there is nothing to commit) the connection goes back to the pool with no
   * tenant at all, and the next call, for whichever tenant, sets its own before it reads anything.
   */
  private async inTenant<T>(work: (client: pg.PoolClient) => Promise<T>): Promise<T> {
    let client: pg.PoolClient;
    try {
      client = await this.pool.connect();
    } catch {
      throw new ToolError('UpstreamDown', 'The database is not reachable.');
    }
    try {
      await client.query('BEGIN READ ONLY');
      await client.query(`SET LOCAL statement_timeout = ${Math.trunc(this.statementTimeoutMs)}`);
      await client.query("SELECT set_config('app.tenant_id', $1, true)", [String(this.tenantId)]);
      return await work(client);
    } catch (e) {
      if (e instanceof ToolError) throw e;
      const code = (e as { code?: string }).code;
      if (code === '57014') throw new ToolError('Timeout', `The database did not answer within ${this.statementTimeoutMs} ms.`);
      throw new ToolError('UpstreamDown', 'The database query failed.', { details: { sqlState: code ?? 'unknown' } });
    } finally {
      await client.query('ROLLBACK').catch(() => undefined);
      client.release();
    }
  }

  runList(filter: Parameters<OpsStore['runList']>[0]): Promise<RunRow[]> {
    return this.inTenant(async (c) => (await c.query(SQL.runList(filter.orderBy), [filter.status ?? null, filter.limit])).rows.map(toRun));
  }

  runDetail(id: string): Promise<RunRow | null> {
    return this.inTenant(async (c) => {
      const rows = (await c.query(SQL.runDetail, [id])).rows;
      return rows.length === 0 ? null : toRun(rows[0] as Record<string, unknown>);
    });
  }

  outboxCounts(): Promise<OutboxCount[]> {
    return this.inTenant(async (c) => (await c.query(SQL.outboxCounts)).rows.map((r) => ({ status: String(r.status), count: Number(r.n), oldest: iso(r.oldest) })));
  }

  outboxRows(filter: Parameters<OpsStore['outboxRows']>[0]): Promise<OutboxRow[]> {
    return this.inTenant(async (c) => (await c.query(SQL.outboxRows, [filter.status ?? null, filter.limit])).rows.map((r) => ({
      id: Number(r.id),
      eventType: String(r.event_type),
      status: String(r.status),
      attempts: Number(r.attempts),
      nextAttemptAt: iso(r.next_attempt_at),
      lastError: r.last_error === null ? null : String(r.last_error),
      createdAt: iso(r.created_at),
      deliveredAt: isoOrNull(r.delivered_at),
      url: String(r.url),
    })));
  }
}

/**
 * Refuses to start as a role that is not subject to row-level security: the worker role (it reads every tenant by
 * design), a superuser, or a role with BYPASSRLS. As any of them the tenant setting would filter nothing, and the
 * server's whole isolation would be a promise instead of a mechanism.
 */
export async function assertSafeRole(pool: pg.Pool): Promise<void> {
  let rows: Array<{ name: string; bypass: boolean }>;
  try {
    rows = (await pool.query(`SELECT current_user AS name, COALESCE(r.rolsuper OR r.rolbypassrls, false) AS bypass
      FROM pg_roles r WHERE r.rolname = current_user`)).rows;
  } catch {
    throw new ToolError('UpstreamDown', 'The database is not reachable, or the configured user cannot read its own role.');
  }
  const role = rows[0];
  if (!role) throw forbidden('Could not determine the database role.');
  if (role.name === WORKER_ROLE) {
    throw forbidden(`Refusing to start: the database user is ${WORKER_ROLE}, which reads every organization's rows. Use the application role.`);
  }
  if (role.bypass) {
    throw forbidden(`Refusing to start: the database user ${role.name} bypasses row-level security, so the tenant setting would isolate nothing. Use a plain application role.`);
  }
}
