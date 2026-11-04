import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { Redactor } from '@arthadhruva/mcp-kit';
import { ask } from '../src/agent.js';
import { KNOWN_PRICES } from '../src/budget.js';
import { FIXTURE_LIMITS, judge, runFixture } from '../src/fixtures.js';
import { RecordedHost, RecordingHost, RecordingModel, ScriptedModel, toFixture, type Fixture } from '../src/replay.js';
import { Session } from '../src/session.js';
import { call, ok, PORTFOLIO, say, SPECS } from './helpers.js';

/**
 * The deterministic suite: every fixture in test/fixtures is replayed through the real loop (session, neutraliser, verifier, budgets)
 * with no model and no network, and judged against what it says should happen. This is the suite CI runs. It reports pass or fail
 * per fixture, by name.
 */
const DIR = path.resolve(fileURLToPath(new URL('./fixtures', import.meta.url)));
const files = fs.readdirSync(DIR).filter((f) => f.endsWith('.json')).sort();

describe('replay fixtures', () => {
  beforeEach(() => {
    // the suite must not touch a network: any attempt to fetch is a failure
    vi.stubGlobal('fetch', () => { throw new Error('the deterministic suite must not use the network'); });
  });
  afterEach(() => vi.unstubAllGlobals());

  it('has a fixture for each verifier rule', () => {
    const names = files.map((f) => f.replace('.json', ''));
    for (const rule of ['verifier-exact-match', 'verifier-rounding-within-tolerance', 'verifier-unit-conversion-bps', 'verifier-percent-vs-fraction',
      'verifier-uncited-then-fixed', 'verifier-mismatch-then-fixed', 'verifier-retry-exhaustion']) expect(names).toContain(rule);
  });

  it.each(files.map((f) => [f.replace('.json', ''), f] as const))('%s', async (_name, file) => {
    const fx = JSON.parse(fs.readFileSync(path.join(DIR, file), 'utf8')) as Fixture;
    expect(judge(fx, await runFixture(fx))).toEqual([]);
  });
});

describe('record mode', () => {
  const PLANTED = 'eyJhbGciOiJIUzI1NiJ9.RECORDSECRET.sig';

  async function record() {
    const redactor = new Redactor();
    redactor.add(PLANTED);
    const inner = new RecordedHost(SPECS, [{ name: 'portfolio_summary', args: {}, outcome: ok({ ...PORTFOLIO, note: `session ${PLANTED}` }) }]);
    const host = new RecordingHost(inner);
    const model = new RecordingModel(new ScriptedModel([call('a', 'portfolio_summary'), say('There are 400 loans [r1].')]));
    const outcome = await ask('How many loans?', {
      model, host, session: new Session({ tenant: '7', allowRunStart: false, specs: host.tools() }), limits: FIXTURE_LIMITS, pricing: KNOWN_PRICES['claude-opus-5-5']!,
    });
    return { outcome, fixture: toFixture('recorded', 'How many loans?', { tenant: '7', allowRunStart: false }, model, host,
      { status: outcome.status, verdicts: outcome.figures.map((f) => f.verdict) }, redactor) };
  }

  it('a recorded run replays to the same verdict, offline', async () => {
    const { outcome, fixture } = await record();
    const replayed = await runFixture(JSON.parse(JSON.stringify(fixture)) as Fixture);
    expect(replayed.outcome.status).toBe(outcome.status);
    expect(replayed.outcome.figures.map((f) => f.verdict)).toEqual(outcome.figures.map((f) => f.verdict));
    expect(judge(fixture, replayed)).toEqual([]);
  });

  it('a recorded fixture holds no credential', async () => {
    const { fixture } = await record();
    expect(JSON.stringify(fixture)).not.toContain('RECORDSECRET');
  });

  it('a replay that is asked for more than was recorded fails loudly, not silently', async () => {
    const { fixture } = await record();
    const shorter = { ...fixture, model: fixture.model.slice(0, 1) };
    await expect(runFixture(shorter)).resolves.toMatchObject({ outcome: { status: 'error' } });
  });
});
