import type { ToolSpec } from './types.js';

/**
 * What one question may do, fixed when the session is made and never widened.
 *
 * - **The tenant is fixed.** It is the session's, set at construction. No tool takes a tenant, so a model that names one in a
 *   call is either repeating the session's own (stripped) or asking for another organization's (refused, and the call is not
 *   made). Nothing the model or a tool result says can change it.
 * - **Read-only by default.** Only tools their server marks read-only are offered; `portfolio_run_start`, which spends
 *   allowance and starts work, is offered only when the session sets `allowRunStart`, whatever its annotation says.
 * - **The set is frozen.** The tools offered are decided once, from the servers' listing at session start. A call to
 *   anything else is refused without reaching a server, so a tool result that suggests "now call X" cannot make X available.
 */
export const RUN_START = 'portfolio_run_start';

/** Argument names that would select an organization. */
const TENANT_KEY = /^(tenant|org|organi[sz]ation)(_?(id|slug|name|key))?$/i;

export interface SessionOptions {
  tenant: string;
  allowRunStart: boolean;
  specs: readonly ToolSpec[];
}

export type Verdict = { ok: true; args: Record<string, unknown> } | { ok: false; reason: string };

export class Session {
  readonly tenant: string;
  readonly allowRunStart: boolean;
  private readonly offered: ReadonlyMap<string, ToolSpec>;

  constructor(options: SessionOptions) {
    if (!options.tenant.trim()) throw new Error('A session needs a tenant.');
    this.tenant = options.tenant.trim();
    this.allowRunStart = options.allowRunStart;
    const offered = new Map<string, ToolSpec>();
    for (const spec of options.specs) {
      if (offered.has(spec.name)) throw new Error(`Two servers offer a tool named ${spec.name}; refusing to guess which one a call means.`);
      if (spec.name === RUN_START ? this.allowRunStart : spec.readOnly) offered.set(spec.name, Object.freeze({ ...spec }));
    }
    this.offered = offered;
    Object.freeze(this);
  }

  /** The tools shown to the model. */
  tools(): ToolSpec[] {
    return [...this.offered.values()];
  }

  has(name: string): boolean {
    return this.offered.has(name);
  }

  /** Decides a proposed call before anything is sent to a server. */
  check(name: string, input: unknown): Verdict {
    if (!this.offered.has(name)) {
      return { ok: false, reason: name === RUN_START
        ? `${RUN_START} is not available in this session: it starts work and spends allowance, and the session does not allow it.`
        : `${name} is not a tool available in this session.` };
    }
    if (typeof input !== 'object' || input === null || Array.isArray(input)) return { ok: false, reason: 'Tool arguments must be an object.' };
    const args = structuredClone(input) as Record<string, unknown>;
    const found: TenantKey[] = [];
    collectTenantKeys(args, '', found);
    for (const { parent, key, path } of found) {
      const value = parent[key];
      if (typeof value === 'string' && value.trim() === this.tenant) {
        delete parent[key];   // the session's own tenant: harmless, and not needed
      } else {
        return { ok: false, reason: `The tenant is fixed for this session (${this.tenant}); no tool takes a tenant, and "${path}" was refused. `
          + 'No call was made.' };
      }
    }
    return { ok: true, args };
  }
}

interface TenantKey {
  parent: Record<string, unknown>;
  key: string;
  path: string;
}

function collectTenantKeys(value: unknown, prefix: string, out: TenantKey[]): void {
  if (typeof value !== 'object' || value === null) return;
  if (Array.isArray(value)) {
    value.forEach((v, i) => collectTenantKeys(v, `${prefix}[${i}]`, out));
    return;
  }
  const record = value as Record<string, unknown>;
  for (const key of Object.keys(record)) {
    const path = prefix ? `${prefix}.${key}` : key;
    if (TENANT_KEY.test(key)) out.push({ parent: record, key, path });
    else collectTenantKeys(record[key], path, out);
  }
}
