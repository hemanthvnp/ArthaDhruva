import type { Fixture } from '../src/replay.js';
import { ResultStore } from '../src/store.js';
import type { ModelReply, ToolOutcome, ToolSpec, Usage } from '../src/types.js';

export const USAGE: Usage = { inputTokens: 1000, outputTokens: 200, cacheReadTokens: 0, cacheWriteTokens: 0 };
const MODEL = 'claude-opus-5-5';

export const say = (text: string, usage: Usage = USAGE): ModelReply =>
  ({ content: [{ type: 'text', text }], stopReason: 'end_turn', usage, model: MODEL });

export const call = (id: string, name: string, input: Record<string, unknown> = {}, usage: Usage = USAGE): ModelReply =>
  ({ content: [{ type: 'tool_use', id, name, input }], stopReason: 'tool_use', usage, model: MODEL });

export const stop = (reason: string, details?: ModelReply['stopDetails']): ModelReply =>
  ({ content: [{ type: 'text', text: 'partial' }], stopReason: reason, ...(details ? { stopDetails: details } : {}), usage: USAGE, model: MODEL });

export const spec = (name: string, server = 'risk-engine', readOnly = true): ToolSpec =>
  ({ name, description: `${name} tool`, inputSchema: { type: 'object', properties: {} }, readOnly, server });

export const ok = (structured: unknown, text?: string): ToolOutcome => ({ isError: false, text: text ?? JSON.stringify(structured), structured });
export const failed = (code: string, message: string): ToolOutcome =>
  ({ isError: true, text: `${code}: ${message}`, structured: { error: { code, message } }, errorCode: code });

/** The tools of a typical session: reads from two servers, a run start that its server marks read-only, and two that write. */
export const SPECS: ToolSpec[] = [
  spec('portfolio_summary'), spec('portfolio_loans'), spec('expected_loss'),
  spec('audit_query', 'tenant-ops'), spec('run_detail', 'tenant-ops'), spec('webhook_outbox', 'tenant-ops'),
  spec('portfolio_run_start', 'risk-engine', true),   // annotated read-only on purpose: the session must not trust the annotation for this one
  spec('wake', 'deploy-ops', false), spec('run_golden_test', 'model-validation', false),
];

export const PORTFOLIO = {
  snapshots: [{ scenario: 'BASELINE', loans: 400, pd12m: 0.00312, pdLifetime: 0.0412, eclLifetime: 1234567.89, exposure: 98_000_000 }],
  portfolioLoans: 400,
};

export function storeOf(...results: unknown[]): ResultStore {
  const store = new ResultStore();
  for (const r of results) {
    store.add('t', {}, r instanceof Object && 'isError' in (r as object) ? (r as ToolOutcome) : ok(r));
  }
  return store;
}

export function fixture(over: Partial<Fixture> & Pick<Fixture, 'name' | 'model' | 'expect'>): Fixture {
  return { question: 'How is the portfolio doing?', session: { tenant: '7', allowRunStart: false }, tools: SPECS, toolResults: [], ...over };
}
