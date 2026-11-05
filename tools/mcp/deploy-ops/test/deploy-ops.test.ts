import type { CallToolResult } from '@modelcontextprotocol/sdk/types.js';
import { describe, expect, it } from 'vitest';
import type { RunResult } from '@arthadhruva/mcp-kit';
import { connect, errorOf } from '@arthadhruva/mcp-kit';
import { Az, POWER_STATE_ID, allowlist, powerStateArgs } from '../src/azure.js';
import { assess } from '../src/health.js';
import { createServer } from '../src/index.js';
import { METRICS } from '../src/prometheus.js';
import { maxTotalMs } from '../src/retry.js';

const PROM = 'http://prom.test:9090';
const APP = 'https://app.test';
const WAKE = 'https://wake.test/api/open';
const ENV = {
  DEPLOY_OPS_PROMETHEUS_URL: PROM, DEPLOY_OPS_APP_URL: APP, DEPLOY_OPS_WAKE_URL: WAKE,
  DEPLOY_OPS_AZ_RESOURCE_GROUP: 'rg-artha', DEPLOY_OPS_AZ_VM: 'vm-artha', DEPLOY_OPS_TIMEOUT_MS: '200', DEPLOY_OPS_RETRIES: '2',
};

interface Seen { method: string; url: string; headers: Headers }

/** A routable fake network: answers by `METHOD url-prefix`, records every request. */
function network(routes: Record<string, (() => Response | Promise<Response>) | 'hang' | 'fail'>) {
  const seen: Seen[] = [];
  const fetchImpl = (async (input: string | URL | Request, init?: RequestInit) => {
    const url = String(input);
    const method = init?.method ?? 'GET';
    seen.push({ method, url, headers: new Headers(init?.headers) });
    const key = Object.keys(routes).find((k) => `${method} ${url}`.startsWith(k));
    if (!key) return new Response('{"error":"no route"}', { status: 404 });
    const handler = routes[key]!;
    const answer = typeof handler === 'function' ? handler() : handler;
    if (answer === 'fail') throw new TypeError('fetch failed');
    if (answer === 'hang') {
      return new Promise<Response>((_, reject) => {
        init?.signal?.addEventListener('abort', () => reject(Object.assign(new Error('timed out'), { name: 'TimeoutError' })));
      });
    }
    return answer;
  }) as typeof fetch;
  return { fetchImpl, seen };
}

const json = (body: unknown, status = 200) => () => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
const prom = (result: unknown[]) => json({ status: 'success', data: { resultType: 'vector', result } });
const alerts = (list: unknown[]) => json({ status: 'success', data: { alerts: list } });

function azRunner(powerState: string | null, code = 0) {
  const calls: string[] = [];
  return {
    calls,
    runner: {
      run: async (id: string): Promise<RunResult> => {
        calls.push(id);
        const statuses = [{ code: 'ProvisioningState/succeeded' }, ...(powerState ? [{ code: `PowerState/${powerState}` }] : [])];
        return { id, code, stdout: code === 0 ? JSON.stringify(statuses) : '', stderr: '', durationMs: 5, timedOut: false, cancelled: false };
      },
    },
  };
}

async function call(env: Record<string, string | undefined>, injected: Parameters<typeof createServer>[1], name: string, args: Record<string, unknown> = {}) {
  const { client, close } = await connect(createServer({ ...ENV, ...env }, { backoffMs: 1, ...injected }));
  try {
    return (await client.callTool({ name, arguments: args })) as CallToolResult;
  } finally {
    await close();
  }
}
const text = (r: CallToolResult) => (r.content[0] as { text: string }).text;

describe('observability queries', () => {
  it('alert_state lists a firing alert with its name, labels and when it began', async () => {
    const net = network({
      'GET http://prom.test:9090/api/v1/alerts': alerts([
        { labels: { alertname: 'HighServerErrorRate', severity: 'critical', job: 'risk-engine' }, annotations: { summary: 'More than 1% of API requests are failing' }, state: 'firing', activeAt: '2026-11-04T08:00:00Z' },
        { labels: { alertname: 'HeapNearlyFull', severity: 'warning' }, annotations: {}, state: 'pending', activeAt: '2026-11-04T08:20:00Z' },
      ]),
    });
    const r = await call({}, { fetchImpl: net.fetchImpl }, 'alert_state');
    expect(r.structuredContent).toMatchObject({ firing: [{ name: 'HighServerErrorRate', severity: 'critical', labels: { job: 'risk-engine' }, activeSince: '2026-11-04T08:00:00Z' }], pending: [] });
    expect(text(r)).toContain('HighServerErrorRate');
    const withPending = await call({}, { fetchImpl: net.fetchImpl }, 'alert_state', { includePending: true });
    expect((withPending.structuredContent as { pending: unknown[] }).pending).toHaveLength(1);
  });

  it('metric_query sends the catalog\'s own query and returns each value with its timestamp', async () => {
    const net = network({ 'GET http://prom.test:9090/api/v1/query': prom([{ metric: {}, value: [1762243200, '0.0042'] }]) });
    const r = await call({}, { fetchImpl: net.fetchImpl }, 'metric_query', { metric: 'api_error_ratio' });
    expect(r.structuredContent).toMatchObject({ metric: 'api_error_ratio', unit: 'ratio', samples: [{ value: 0.0042, at: '2025-11-04T08:00:00.000Z' }] });
    expect(new URL(net.seen[0]!.url).searchParams.get('query')).toBe(METRICS.api_error_ratio.query);
  });

  it('an unknown metric, or an attempt to pass PromQL, is InvalidInput and nothing is requested', async () => {
    const net = network({});
    for (const metric of ['up', 'sum(rate(x[1m]))', '} or vector(1)', 'api_error_ratio; drop']) {
      expect(errorOf(await call({}, { fetchImpl: net.fetchImpl }, 'metric_query', { metric }))?.code, metric).toBe('InvalidInput');
    }
    expect(net.seen).toHaveLength(0);
  });
});

describe('the az allowlist', () => {
  const target = { resourceGroup: 'rg-artha', vm: 'vm-artha' };

  it('refuses every az command that is not the one read, before anything is spawned', async () => {
    const { runner, calls } = azRunner('running');
    const az = new Az(runner, target);
    const refused = [
      ['vm', 'deallocate', '--resource-group', 'rg-artha', '--name', 'vm-artha'],
      ['vm', 'start', '-g', 'rg-artha', '-n', 'vm-artha'],
      ['vm', 'stop', '-g', 'rg-artha', '-n', 'vm-artha'],
      ['vm', 'delete', '-g', 'rg-artha', '-n', 'vm-artha', '--yes'],
      ['group', 'delete', '--name', 'rg-artha'],
      ['deployment', 'group', 'create', '-g', 'rg-artha', '--template-file', 'x.bicep'],
      ['vm', 'list'],
      ['account', 'show'],
      [...powerStateArgs(target), '--debug'],
      [...powerStateArgs(target).slice(0, -1), 'yaml'],
      [],
    ];
    for (const argv of refused) {
      await expect(az.exec(argv), argv.join(' ')).rejects.toMatchObject({ code: 'Forbidden' });
    }
    expect(calls, 'nothing was spawned').toEqual([]);
  });

  it('says whether a refused command would have changed something', async () => {
    const az = new Az(azRunner('running').runner, target);
    await expect(az.exec(['vm', 'deallocate', '-g', 'x', '-n', 'y'])).rejects.toThrow(/would change Azure resources/);
    await expect(az.exec(['vm', 'list'])).rejects.toThrow(/not on this server's allowlist/);
  });

  it('runs exactly the power-state read, and parses the state', async () => {
    const { runner, calls } = azRunner('deallocated');
    expect(await new Az(runner, target).powerState()).toEqual({ state: 'deallocated' });
    expect(calls).toEqual([POWER_STATE_ID]);
  });

  it('the process the runner would start is az with the fixed arguments and nothing from a caller', () => {
    const [entry, ...rest] = allowlist(target, false);
    expect(rest).toEqual([]);
    expect(entry).toMatchObject({ id: POWER_STATE_ID, command: 'az', args: powerStateArgs(target) });
    expect(entry!.args.filter((a) => a.startsWith('--')).sort()).toEqual(['--name', '--output', '--query', '--resource-group']);
  });

  it('a resource group or VM name that could smuggle an option is refused at configuration', () => {
    expect(() => createServer({ ...ENV, DEPLOY_OPS_AZ_VM: 'vm --output yaml' })).toThrow(/letters, digits/);
    expect(() => createServer({ ...ENV, DEPLOY_OPS_AZ_RESOURCE_GROUP: '-x' })).toThrow(/letters, digits/);
    expect(() => createServer({ ...ENV, DEPLOY_OPS_AZ_VM: undefined })).toThrow(/together or not at all/);
  });

  it('reports why az could not answer instead of failing', async () => {
    expect((await new Az(azRunner(null, 127).runner, target).powerState()).reason).toContain('not installed');
    expect((await new Az(azRunner(null, 1).runner, target).powerState()).reason).toContain('az login');
    expect((await new Az(azRunner(null, 0).runner, target).powerState()).reason).toContain('no power state');
  });
});

describe('a deallocated VM is a normal state', () => {
  it('health says asleep with a hint about wake, is not an error, and does not look for an application that cannot answer', async () => {
    const net = network({});
    const r = await call({}, { fetchImpl: net.fetchImpl, runner: azRunner('deallocated').runner }, 'health');
    expect(r.isError).toBeFalsy();
    expect(r.structuredContent).toMatchObject({ state: 'asleep', normal: true, powerState: 'deallocated', powerSource: 'az', applicationAnswers: null });
    expect(text(r)).toContain('wake');
    expect(net.seen, 'neither the application nor Prometheus was asked').toHaveLength(0);
  });

  it('is the same through the wake function when az is not configured', async () => {
    const net = network({ 'POST https://wake.test/api/open/status': json({ state: 'deallocated', appUp: false }) });
    const r = await call({ DEPLOY_OPS_AZ_VM: undefined, DEPLOY_OPS_AZ_RESOURCE_GROUP: undefined }, { fetchImpl: net.fetchImpl }, 'health');
    expect(r.structuredContent).toMatchObject({ state: 'asleep', normal: true, powerSource: 'wake' });
  });

  it('falls back to the wake function, with a note, when az cannot answer', async () => {
    const net = network({ 'POST https://wake.test/api/open/status': json({ state: 'stopped', appUp: false }) });
    const r = await call({}, { fetchImpl: net.fetchImpl, runner: azRunner(null, 127).runner }, 'health');
    expect(r.structuredContent).toMatchObject({ state: 'asleep', powerSource: 'wake' });
    expect(text(r)).toContain('az command-line tool is not installed');
  });

  it('starting and stopping are normal states too', () => {
    expect(assess({ power: 'starting', appAnswers: false, backendUp: null, firing: null })).toMatchObject({ state: 'starting', normal: true });
    expect(assess({ power: 'deallocating', appAnswers: false, backendUp: null, firing: null })).toMatchObject({ state: 'going-to-sleep', normal: true });
  });

  it('a running VM whose application does not answer IS flagged', () => {
    expect(assess({ power: 'running', appAnswers: false, backendUp: null, firing: null })).toMatchObject({ state: 'unhealthy', normal: false });
  });

  it('with no power source at all, an unanswering application is "unknown", not a failure', () => {
    expect(assess({ power: null, appAnswers: false, backendUp: null, firing: null })).toMatchObject({ state: 'unknown', normal: true });
  });

  it('a healthy stack, a degraded one (alerts) and one Prometheus cannot see', async () => {
    const app = { 'GET https://app.test/v1/loans': () => new Response('', { status: 401 }) };
    const healthy = network({ ...app, 'GET http://prom.test:9090/api/v1/query': prom([{ metric: {}, value: [1, '1'] }]), 'GET http://prom.test:9090/api/v1/alerts': alerts([]) });
    expect((await call({}, { fetchImpl: healthy.fetchImpl, runner: azRunner('running').runner }, 'health')).structuredContent).toMatchObject({ state: 'healthy', normal: true, backendUp: true, firingAlerts: { critical: 0, other: 0 } });
    const degraded = network({ ...app, 'GET http://prom.test:9090/api/v1/query': prom([{ metric: {}, value: [1, '1'] }]),
      'GET http://prom.test:9090/api/v1/alerts': alerts([{ labels: { alertname: 'X', severity: 'critical' }, annotations: {}, state: 'firing', activeAt: 'now' }]) });
    expect((await call({}, { fetchImpl: degraded.fetchImpl, runner: azRunner('running').runner }, 'health')).structuredContent).toMatchObject({ state: 'degraded', normal: false });
    const blind = network({ ...app, 'GET http://prom.test:9090': 'fail' });
    const r = await call({}, { fetchImpl: blind.fetchImpl, runner: azRunner('running').runner }, 'health');
    expect(r.structuredContent).toMatchObject({ state: 'healthy', backendUp: null, firingAlerts: null });
    expect(text(r)).toContain('Prometheus could not be asked');
  });
});

describe('wake is the only action', () => {
  const STATUS = 'POST https://wake.test/api/open/status';
  const WAKE_CALL = 'POST https://wake.test/api/open/wake';

  it('wakes a deallocated VM with exactly one start call and says it is starting', async () => {
    const net = network({ [STATUS]: json({ state: 'deallocated', appUp: false }), [WAKE_CALL]: json({ state: 'starting', appUp: false }) });
    const r = await call({}, { fetchImpl: net.fetchImpl }, 'wake');
    expect(r.structuredContent).toMatchObject({ outcome: 'started-by-this-call', powerStateBefore: 'deallocated' });
    expect(text(r)).toContain('health');
    expect(net.seen.filter((s) => s.url.endsWith('/wake'))).toHaveLength(1);
    expect(net.seen.every((s) => s.headers.get('X-Wake') === '1')).toBe(true);
  });

  it('is idempotent: a VM that is running or already starting is not started again', async () => {
    const running = network({ [STATUS]: json({ state: 'running', appUp: true }), [WAKE_CALL]: json({ state: 'running', appUp: true }) });
    expect((await call({}, { fetchImpl: running.fetchImpl }, 'wake')).structuredContent).toMatchObject({ outcome: 'already-running', applicationAnswers: true });
    expect(running.seen.some((s) => s.url.endsWith('/wake'))).toBe(false);
    const starting = network({ [STATUS]: json({ state: 'starting', appUp: false }), [WAKE_CALL]: json({ state: 'starting', appUp: false }) });
    expect((await call({}, { fetchImpl: starting.fetchImpl }, 'wake')).structuredContent).toMatchObject({ outcome: 'already-starting' });
    expect(starting.seen.some((s) => s.url.endsWith('/wake'))).toBe(false);
  });

  it('does not ask for a start while the VM is going down', async () => {
    const net = network({ [STATUS]: json({ state: 'deallocating', appUp: false }), [WAKE_CALL]: json({ state: 'starting', appUp: false }) });
    expect((await call({}, { fetchImpl: net.fetchImpl }, 'wake')).structuredContent).toMatchObject({ outcome: 'going-to-sleep' });
    expect(net.seen.some((s) => s.url.endsWith('/wake'))).toBe(false);
  });

  it('only ever requests the configured wake URL\'s two fixed paths', async () => {
    const net = network({ [STATUS]: json({ state: 'stopped', appUp: false }), [WAKE_CALL]: json({ state: 'starting', appUp: false }) });
    await call({}, { fetchImpl: net.fetchImpl }, 'wake');
    expect(net.seen.map((s) => s.url).sort()).toEqual(['https://wake.test/api/open/status', 'https://wake.test/api/open/wake']);
  });

  it('without a wake URL there is nothing to call', async () => {
    const net = network({});
    expect(errorOf(await call({ DEPLOY_OPS_WAKE_URL: undefined }, { fetchImpl: net.fetchImpl }, 'wake'))?.code).toBe('InvalidInput');
    expect(net.seen).toHaveLength(0);
  });

  it('is the only tool that is not read-only, and it is not destructive', async () => {
    const { client, close } = await connect(createServer(ENV, { fetchImpl: network({}).fetchImpl, runner: azRunner('running').runner }));
    const { tools } = await client.listTools();
    expect(tools.filter((t) => !t.annotations?.readOnlyHint).map((t) => t.name)).toEqual(['wake']);
    expect(tools.find((t) => t.name === 'wake')!.annotations).toMatchObject({ destructiveHint: false, idempotentHint: true });
    await close();
  });
});

describe('bounded retries', () => {
  it('Prometheus down: UpstreamDown after exactly retries + 1 attempts, within the stated maximum time', async () => {
    const net = network({ 'GET http://prom.test:9090': 'fail' });
    const started = Date.now();
    const r = await call({}, { fetchImpl: net.fetchImpl }, 'metric_query', { metric: 'backend_up' });
    expect(errorOf(r)?.code).toBe('UpstreamDown');
    expect(net.seen).toHaveLength(3);
    expect(Date.now() - started).toBeLessThan(maxTotalMs({ timeoutMs: 200, retries: 2, backoffMs: 1 }));
  });

  it('no answer at all is Timeout after the retries, and the time is bounded', async () => {
    const net = network({ 'GET http://prom.test:9090': 'hang' });
    const started = Date.now();
    const r = await call({ DEPLOY_OPS_TIMEOUT_MS: '60', DEPLOY_OPS_RETRIES: '1' }, { fetchImpl: net.fetchImpl }, 'alert_state');
    expect(errorOf(r)?.code).toBe('Timeout');
    expect(net.seen).toHaveLength(2);
    expect(Date.now() - started).toBeLessThan(maxTotalMs({ timeoutMs: 60, retries: 1, backoffMs: 1 }) + 400);
  });

  it('a 5xx is retried and a recovery is used; a 4xx is not retried', async () => {
    let n = 0;
    const flaky = network({ 'GET http://prom.test:9090/api/v1/query': () => (++n < 3 ? new Response('busy', { status: 503 }) : prom([{ metric: {}, value: [1, '1'] }])()) });
    expect((await call({}, { fetchImpl: flaky.fetchImpl }, 'metric_query', { metric: 'backend_up' })).structuredContent).toMatchObject({ samples: [{ value: 1 }] });
    expect(flaky.seen).toHaveLength(3);
    const refused = network({ 'GET http://prom.test:9090/api/v1/query': json({ status: 'error', error: 'bad query' }, 400) });
    expect(errorOf(await call({}, { fetchImpl: refused.fetchImpl }, 'metric_query', { metric: 'backend_up' }))?.code).toBe('UpstreamDown');
    expect(refused.seen).toHaveLength(1);
  });

  it('the retry count is capped', () => {
    expect(() => createServer({ ...ENV, DEPLOY_OPS_RETRIES: '9' })).toThrow(/0 to 3/);
    expect(maxTotalMs({ timeoutMs: 3000, retries: 2, backoffMs: 200 })).toBe(9600);
  });
});
