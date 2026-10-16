import type { CallToolResult } from '@modelcontextprotocol/sdk/types.js';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { connect, errorOf } from '@arthadhruva/mcp-kit';
import { SESSION_EXPIRED } from '../src/http.js';
import { createServer, withoutTrailingSlashes } from '../src/index.js';
import { FakeApi, LOAN, RUN, SESSION } from './fake-api.js';

let api: FakeApi;
const stderr: string[] = [];

beforeEach(async () => {
  api = await new FakeApi().start();
  vi.spyOn(process.stderr, 'write').mockImplementation((chunk: string | Uint8Array) => { stderr.push(String(chunk)); return true; });
});
afterEach(async () => {
  vi.restoreAllMocks();
  stderr.length = 0;
  await api.stop();
});

async function session(env: Record<string, string> = {}) {
  return connect(createServer({ RISK_ENGINE_URL: api.url, RISK_ENGINE_SESSION: SESSION, RISK_ENGINE_TIMEOUT_MS: '2000', RISK_ENGINE_MAX_ROWS: '50', ...env }));
}

async function call(name: string, args: Record<string, unknown>, env?: Record<string, string>) {
  const { client, close } = await session(env);
  try {
    return (await client.callTool({ name, arguments: args })) as CallToolResult;
  } finally {
    await close();
  }
}

const SCORE = { rawProbability: 0.2, calibratedProbability: 0.0421, explanation: [{ feature: 'credit_score', contribution: 0.012 }, { feature: 'original_dti', contribution: -0.002 }, { feature: 'property_state', contribution: 0.004 }], reasonCodes: [{ code: 'R1', feature: 'credit_score', description: 'Credit score below typical' }], warnings: [], modelVersion: 'pd_24m@2026.10.01-1308', horizonMonths: 24, baselineProbability: 0.028 };

describe('scoring', () => {
  it('score_loan calls POST /v1/score and returns probability, version and warnings', async () => {
    api.on('POST', '/v1/score', { body: SCORE });
    const r = await call('score_loan', { loan: LOAN });
    expect(r.isError).toBeFalsy();
    expect(r.structuredContent).toMatchObject({ calibratedProbability: 0.0421, modelVersion: 'pd_24m@2026.10.01-1308', warnings: [], horizonMonths: 24 });
    expect(api.requests('POST', '/v1/score')[0]?.body).toEqual(LOAN);
  });

  it('explain_loan ranks contributions by size and counts the rest', async () => {
    api.on('POST', '/v1/score', { body: SCORE });
    const r = await call('explain_loan', { loan: LOAN, top: 2 });
    expect(r.structuredContent).toMatchObject({ contributions: [{ feature: 'credit_score' }, { feature: 'property_state' }], omitted: 1, baselineProbability: 0.028 });
    expect((r.content[0] as { text: string }).text).toContain('1 more rows');
  });

  it('rejects a malformed loan before calling the API', async () => {
    const r = await call('score_loan', { loan: { ...LOAN, creditScore: 9000 } });
    expect(r.isError).toBe(true);
    expect(api.requests('POST', '/v1/score')).toHaveLength(0);
  });
});

describe('authentication', () => {
  it('sends the session cookie and the matching CSRF header on a write, obtaining the token from the API', async () => {
    api.on('POST', '/v1/score', { body: SCORE });
    await call('score_loan', { loan: LOAN });
    const post = api.requests('POST', '/v1/score')[0]!;
    expect(post.headers.cookie).toBe(`ad_session=${SESSION}; XSRF-TOKEN=csrf-api`);
    expect(post.headers['x-xsrf-token']).toBe('csrf-api');
  });

  it('uses a configured CSRF token and sends none on reads', async () => {
    api.on('POST', '/v1/score', { body: SCORE }).on('GET', '/v1/risk/scenarios', { body: [] });
    const { client, close } = await session({ RISK_ENGINE_XSRF: 'configured-csrf-456' });
    await client.callTool({ name: 'list_scenarios', arguments: {} });
    await client.callTool({ name: 'score_loan', arguments: { loan: LOAN } });
    await close();
    expect(api.requests('GET', '/v1/csrf')).toHaveLength(0);
    expect(api.requests('GET', '/v1/risk/scenarios')[0]?.headers['x-xsrf-token']).toBeUndefined();
    expect(api.requests('POST', '/v1/score')[0]?.headers['x-xsrf-token']).toBe('configured-csrf-456');
  });

  it('reports an expired session as Forbidden and does not retry', async () => {
    api.on('GET', '/v1/models', { status: 401 });
    const r = await call('list_models', {});
    expect(errorOf(r)).toMatchObject({ code: 'Forbidden', message: SESSION_EXPIRED });
    expect(api.requests('GET', '/v1/models')).toHaveLength(1);
  });

  it('follows a rotated session and never shows either session value', async () => {
    const rotated = 'eyJhbGciOiJIUzI1NiJ9.eyJ2IjoyfQ.rotated-signature-xyz';
    api.on('GET', '/v1/risk/scenarios', (_req, n) => ({ body: [{ name: 'BASELINE', description: `echo ${n === 1 ? SESSION : rotated}` }], headers: n === 1 ? { 'Set-Cookie': `ad_session=${rotated}; Path=/; HttpOnly` } : {} }));
    const { client, close } = await session();
    const first = (await client.callTool({ name: 'list_scenarios', arguments: {} })) as CallToolResult;
    const second = (await client.callTool({ name: 'list_scenarios', arguments: {} })) as CallToolResult;
    await close();
    expect(api.requests('GET', '/v1/risk/scenarios')[1]?.headers.cookie).toBe(`ad_session=${rotated}`);
    const all = JSON.stringify([first, second]) + stderr.join('');
    expect(all).not.toContain(SESSION);
    expect(all).not.toContain('rotated-signature-xyz');
  });

  it('refuses to start without a session, naming the variable and printing no secret', () => {
    expect(() => createServer({ RISK_ENGINE_URL: api.url })).toThrowError(/RISK_ENGINE_SESSION is not set/);
    expect(() => createServer({ RISK_ENGINE_SESSION: 'not-a-token-secret' })).toThrowError(/RISK_ENGINE_SESSION is malformed/);
    try {
      createServer({ RISK_ENGINE_SESSION: 'not-a-token-secret' });
    } catch (e) {
      expect(String(e)).not.toContain('not-a-token-secret');
    }
  });
});

describe('portfolio runs', () => {
  it('returns the run from the 202 at once and polls separately, including a failed run', async () => {
    api.on('POST', '/v1/risk/portfolio/runs', { status: 202, body: RUN })
      .on('GET', `/v1/risk/portfolio/runs/${RUN.id}`, { body: { ...RUN, status: 'FAILED', attempt: 3, error: 'The worker stopped heartbeating; gave up after 3 attempts' } });
    const { client, close } = await session();
    const started = (await client.callTool({ name: 'portfolio_run_start', arguments: { scenarios: ['ADVERSE'] } })) as CallToolResult;
    expect(started.structuredContent).toMatchObject({ id: RUN.id, status: 'QUEUED' });
    const polled = (await client.callTool({ name: 'portfolio_run_status', arguments: { runId: RUN.id } })) as CallToolResult;
    await close();
    expect(polled.structuredContent).toMatchObject({ status: 'FAILED', attempt: 3, error: expect.stringContaining('heartbeating') });
    expect(api.requests('POST', '/v1/risk/portfolio/runs')[0]?.body).toEqual({ scenarios: ['ADVERSE'] });
  });

  it('returns a typed conflict carrying the active run id', async () => {
    api.on('POST', '/v1/risk/portfolio/runs', { status: 409, body: { ...RUN, status: 'RUNNING' } });
    const r = await call('portfolio_run_start', {});
    expect(errorOf(r)).toMatchObject({ code: 'Conflict', details: { runId: RUN.id, status: 'RUNNING' } });
  });

  it('reports a busy engine with its retry time', async () => {
    api.on('POST', '/v1/risk/portfolio/runs', { status: 503, headers: { 'Retry-After': '30' }, body: { error: 'Every worker is busy' } });
    const r = await call('portfolio_run_start', {});
    expect(errorOf(r)).toMatchObject({ code: 'UpstreamDown', retryAfterSec: 30 });
  });

  it('declares run start as the only non-read-only tool', async () => {
    const { client, close } = await session();
    const { tools } = await client.listTools();
    await close();
    const writers = tools.filter((t) => t.annotations?.readOnlyHint !== true).map((t) => t.name);
    expect(writers).toEqual(['portfolio_run_start']);
    expect(tools).toHaveLength(16);
  });
});

describe('idempotency', () => {
  it('reuses the key when the same call is repeated after a timeout, and not after a success', async () => {
    api.on('POST', '/v1/score', (_req, n) => (n === 1 ? { body: SCORE, delayMs: 3000 } : { body: SCORE }));
    const { client, close } = await session();
    const timedOut = (await client.callTool({ name: 'score_loan', arguments: { loan: LOAN } })) as CallToolResult;
    expect(errorOf(timedOut)?.code).toBe('Timeout');
    await client.callTool({ name: 'score_loan', arguments: { loan: LOAN } });
    await client.callTool({ name: 'score_loan', arguments: { loan: LOAN } });
    await close();
    const keys = api.requests('POST', '/v1/score').map((r) => r.headers['idempotency-key']);
    expect(keys).toHaveLength(3);
    expect(keys[0]).toBeTruthy();
    expect(keys[1]).toBe(keys[0]);
    expect(keys[2]).not.toBe(keys[1]);
  });
});

describe('limits', () => {
  it('turns 429 into RateLimited with the retry time and makes no second request', async () => {
    api.on('POST', '/v1/cvar', { status: 429, headers: { 'Retry-After': '7' } });
    const r = await call('cvar', { loans: [{ pd: 0.02, lgd: 0.3, ead: 100000 }] });
    expect(errorOf(r)).toMatchObject({ code: 'RateLimited', retryAfterSec: 7 });
    expect(api.requests('POST', '/v1/cvar')).toHaveLength(1);
  });

  it('turns an exhausted allowance into QuotaExceeded and makes no second request', async () => {
    api.on('POST', '/v1/expected-loss', { status: 402, body: { error: 'Monthly allowance used' } });
    const r = await call('expected_loss', { loan: LOAN });
    expect(errorOf(r)?.code).toBe('QuotaExceeded');
    expect(api.requests('POST', '/v1/expected-loss')).toHaveLength(1);
  });

  it('caps list results and says how many rows were left out', async () => {
    api.on('GET', '/v1/risk/portfolio/loans', (req) => {
      const limit = Number(new URL(`http://x${req.path}`).searchParams.get('limit'));
      return { body: { asOf: '2026-10-13', total: 1000, loans: Array.from({ length: limit }, (_, i) => ({ loanId: `L${i}`, state: 'CA', exposure: 1, pd12m: 0.01, pdLifetime: 0.05, eclLifetime: 10, eclIfrs9: 5, stage: 1 })) } };
    });
    const r = await call('portfolio_loans', { limit: 500 });
    expect(api.requests('GET', '/v1/risk/portfolio/loans')[0]?.path).toContain('limit=50');
    expect(r.structuredContent).toMatchObject({ total: 1000, omitted: 950 });
    expect((r.structuredContent as { loans: unknown[] }).loans).toHaveLength(50);
    expect((r.content[0] as { text: string }).text).toContain('950 more rows');
  });

  it('reports an unreachable backend without naming the host', async () => {
    const r = await call('list_models', {}, { RISK_ENGINE_URL: 'http://127.0.0.1:1' });
    expect(errorOf(r)?.code).toBe('UpstreamDown');
    expect(JSON.stringify(r)).not.toContain('127.0.0.1');
  });
});

describe('resources and prompt', () => {
  it('serves the model card the API serves, checksum included, and the scenario catalogue', async () => {
    api.on('GET', '/v1/models', { body: [{ id: 'pd_24m', name: 'Origination PD', validated: true }, { id: 'lgd', name: 'LGD', validated: false }] })
      .on('GET', '/v1/models/pd_24m', { body: { version: '2026.10.01-1308', artifact_sha256: 'ab'.repeat(32) } })
      .on('GET', '/v1/risk/scenarios', { body: [{ name: 'BASELINE' }] });
    const { client, close } = await session();
    const listed = await client.listResources();
    const card = await client.readResource({ uri: 'risk-engine://models/pd_24m' });
    const scenarios = await client.readResource({ uri: 'risk-engine://scenarios' });
    const prompt = await client.getPrompt({ name: 'explain_loan_decision', arguments: { loanId: 'F1' } });
    await close();
    expect(listed.resources.map((r) => r.uri)).toEqual(expect.arrayContaining(['risk-engine://models/pd_24m', 'risk-engine://scenarios']));
    expect(listed.resources.map((r) => r.uri)).not.toContain('risk-engine://models/lgd');
    expect((card.contents[0] as { text: string }).text).toContain('ab'.repeat(32));
    expect((scenarios.contents[0] as { text: string }).text).toContain('BASELINE');
    expect((prompt.messages[0]?.content as { text: string }).text).toContain('Explain the credit decision for loan F1');
  });
});

describe('configuration', () => {
  it('drops trailing slashes from the base URL, in linear time on a long run of them', () => {
    expect(withoutTrailingSlashes('http://localhost:8080//')).toBe('http://localhost:8080');
    expect(withoutTrailingSlashes('http://localhost:8080')).toBe('http://localhost:8080');
    expect(withoutTrailingSlashes('/'.repeat(200_000) + 'x')).toHaveLength(200_001);
  });
});
