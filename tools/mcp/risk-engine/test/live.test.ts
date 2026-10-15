import type { CallToolResult } from '@modelcontextprotocol/sdk/types.js';
import { describe, expect, it } from 'vitest';
import { connect, errorOf } from '@arthadhruva/mcp-kit';
import { createServer } from '../src/index.js';
import { LOAN } from './fake-api.js';

/**
 * Opt-in smoke test against a real local backend: every tool once, through the protocol. It costs API calls
 * (audited and metered), so it runs only with RISK_ENGINE_LIVE=1 and RISK_ENGINE_SESSION set, and says so
 * when skipped. RISK_ENGINE_LIVE_RUN=1 also starts a portfolio run (minutes of compute).
 */
const live = process.env.RISK_ENGINE_LIVE === '1' && !!process.env.RISK_ENGINE_SESSION;
if (!live) process.stderr.write('risk-engine live smoke test SKIPPED: set RISK_ENGINE_LIVE=1, RISK_ENGINE_URL and RISK_ENGINE_SESSION to run it.\n');

describe.skipIf(!live)('risk-engine against a live backend', () => {
  it('answers every tool', async () => {
    const { client, close } = await connect(createServer(process.env));
    const outcomes: Record<string, string> = {};
    const run = async (name: string, args: Record<string, unknown>) => {
      const r = (await client.callTool({ name, arguments: args }, undefined, { timeout: 300_000 })) as CallToolResult;
      outcomes[name] = r.isError ? `error ${errorOf(r)?.code}: ${errorOf(r)?.message}` : 'ok';
      return r;
    };
    const page = await run('portfolio_loans', { limit: 3 });
    await run('list_scenarios', {});
    await run('list_models', {});
    await run('model_detail', { id: 'pd_24m' });
    await run('regime_forecast', { monthsAhead: 6 });
    await run('portfolio_summary', {});
    await run('get_loan', { loanId: (page.structuredContent as { loans?: Array<{ loanId: string }> })?.loans?.[0]?.loanId ?? LOAN.loanId });
    await run('score_loan', { loan: LOAN });
    await run('explain_loan', { loan: LOAN, top: 5 });
    await run('expected_loss', { loan: LOAN });
    await run('term_structure', { loan: LOAN, scenario: 'ADVERSE' });
    await run('compare_term_structure', { loan: LOAN, scenarios: ['BASELINE', 'ADVERSE'], weights: { BASELINE: 0.7, ADVERSE: 0.3 } });
    await run('cvar', { loans: [{ pd: 0.02, lgd: 0.3, ead: 250000 }, { pd: 0.05, lgd: 0.35, ead: 180000 }], numScenarios: 5000, seed: 7 });
    if (process.env.RISK_ENGINE_LIVE_RUN === '1') {
      const started = await run('portfolio_run_start', { scenarios: ['ADVERSE'] });
      const runId = (started.structuredContent as { id?: string })?.id ?? (errorOf(started)?.details as { runId?: string } | undefined)?.runId;
      if (runId) {
        for (let i = 0; i < 120; i++) {
          const s = await run('portfolio_run_status', { runId });
          if (!['QUEUED', 'RUNNING'].includes((s.structuredContent as { status: string }).status)) break;
          await new Promise((r) => setTimeout(r, 5000));
        }
      }
      await run('loss_distribution', { numScenarios: 5000, seed: 7 });
    }
    await close();
    process.stderr.write(`live outcomes:\n${Object.entries(outcomes).map(([k, v]) => `  ${k}: ${v}`).join('\n')}\n`);
    // A run already active is the documented Conflict (carrying its id, which is then polled), not a failure.
    const expected = (tool: string, v: string) => v === 'ok' || (tool === 'portfolio_run_start' && v.startsWith('error Conflict'));
    expect(Object.entries(outcomes).filter(([tool, v]) => !expected(tool, v))).toEqual([]);
  }, 900_000);
});
