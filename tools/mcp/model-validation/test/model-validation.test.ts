import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import type { CallToolResult } from '@modelcontextprotocol/sdk/types.js';
import { afterAll, describe, expect, it } from 'vitest';
import { connect, errorOf, type RunResult } from '@arthadhruva/mcp-kit';
import { advise } from '../src/advisory.js';
import { CHECKS, RESOURCES, verify, type Reader } from '../src/artifacts.js';
import { assertAllowedTest } from '../src/forbidden.js';
import { allowlist, interpret } from '../src/golden.js';
import { gitRepo } from '../src/git.js';
import { createServer } from '../src/index.js';

const REAL_ROOT = path.resolve(fileURLToPath(new URL('../../../..', import.meta.url)));
const sha = (b: Buffer | string) => createHash('sha256').update(b).digest('hex');

/** A reader over an in-memory file set. */
function files(entries: Record<string, string | Buffer>): Reader {
  return { read: (rel) => (rel in entries ? Buffer.from(entries[rel]!) : undefined) };
}

/** A consistent fixture: every artifact present, every card declaring the right checksum. */
function consistent(): Record<string, string | Buffer> {
  const out: Record<string, string | Buffer> = {};
  const cards = new Map<string, Record<string, string>>();
  for (const c of CHECKS) {
    const bytes = Buffer.from(`artifact bytes of ${c.artifact}`);
    out[`${RESOURCES}/${c.artifact}`] = bytes;
    cards.set(c.card, { ...(cards.get(c.card) ?? {}), [c.field]: sha(bytes) });
  }
  for (const [card, body] of cards) out[`${RESOURCES}/${card}`] = JSON.stringify(body);
  return out;
}

function run(code: number | null, stdout = '', stderr = '', over: Partial<RunResult> = {}): RunResult {
  return { id: 'golden-test', code, stdout, stderr, durationMs: 4200, timedOut: false, cancelled: false, ...over };
}

const PASS_OUT = '[INFO] Running com.arthadhruva.riskengine.survival.TermStructureGoldenTest\n[INFO] Tests run: 13, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 4.1 s -- in com.arthadhruva.riskengine.survival.TermStructureGoldenTest\n[INFO] BUILD SUCCESS';

async function call(name: string, args: Record<string, unknown>, opts: { repo?: Reader & { changedSince(b: string): string[] }; golden?: () => Promise<RunResult>; fetchImpl?: typeof fetch; env?: Record<string, string> } = {}) {
  const repo = (opts.repo ?? { read: () => undefined, changedSince: () => [], root: '/x' }) as never;
  const server = createServer('/x', { RISK_ENGINE_URL: 'http://api.test', RISK_ENGINE_SESSION: 'a.b.c', ...opts.env },
    { repo, ...(opts.fetchImpl ? { fetchImpl: opts.fetchImpl } : {}), ...(opts.golden ? { runGolden: opts.golden } : {}) });
  const { client, close } = await connect(server);
  try {
    return (await client.callTool({ name, arguments: args })) as CallToolResult;
  } finally {
    await close();
  }
}
const text = (r: CallToolResult) => (r.content[0] as { text: string }).text;

describe('checksum check against the model cards', () => {
  it('every artifact matches in a consistent set', () => {
    const r = verify(files(consistent()));
    expect(r.passed).toBe(true);
    expect(r.outcomes.every((o) => o.verdict === 'match')).toBe(true);
    expect(r.outcomes).toHaveLength(CHECKS.length);
  });

  it('a tampered artifact is a mismatch with both checksums, and the whole result fails', async () => {
    const set = consistent();
    const tampered = `${RESOURCES}/survival_model.onnx`;
    const declared = sha(set[tampered]!);
    set[tampered] = Buffer.from('not the model');
    const r = await call('verify_artifacts', {}, { repo: { ...files(set), changedSince: () => [], root: '/x' } });
    const out = r.structuredContent as { passed: boolean; artifacts: Array<{ artifact: string; verdict: string; declared: string; actual: string }> };
    expect(out.passed).toBe(false);
    const bad = out.artifacts.filter((a) => a.verdict !== 'match');
    expect(bad).toEqual([expect.objectContaining({ artifact: 'survival_model.onnx', verdict: 'mismatch', declared, actual: sha('not the model') })]);
    expect(text(r)).toContain('MISMATCH survival_model.onnx');
    expect(text(r)).toContain(declared);
    expect(r.isError).toBeFalsy();
  });

  it('a missing artifact, a missing card, and a card without the field are all "missing" and fail', () => {
    const set = consistent();
    delete set[`${RESOURCES}/model.onnx`];
    delete set[`${RESOURCES}/hmm_regime_card.json`];
    set[`${RESOURCES}/segment_graph_card.json`] = '{}';
    const r = verify(files(set));
    expect(r.passed).toBe(false);
    expect(r.outcomes.filter((o) => o.verdict === 'missing').map((o) => o.artifact).sort()).toEqual(['hmm_regime.json', 'model.onnx', 'segment_graph.json']);
    expect(r.outcomes.find((o) => o.artifact === 'hmm_regime.json')!.detail).toContain('card');
  });

  it('the repository\'s own committed artifacts match their cards, today', () => {
    const r = verify(gitRepo(REAL_ROOT));
    expect(r.outcomes.filter((o) => o.verdict !== 'match'), JSON.stringify(r.outcomes.filter((o) => o.verdict !== 'match'))).toEqual([]);
    expect(r.passed).toBe(true);
  });
});

describe('the golden test runner', () => {
  it('runs exactly one command: TermStructureGoldenTest through the Maven wrapper in the engine\'s directory', () => {
    const list = allowlist(false);
    expect(list).toHaveLength(1);
    expect(list[0]).toMatchObject({ command: './mvnw', cwd: 'backend/risk-engine' });
    expect(list[0]!.args).toContain('-Dtest=TermStructureGoldenTest');
    expect(list[0]!.args.filter((a) => a.startsWith('-D')).length).toBe(2);
  });

  it('on Windows the wrapper is a relative path, since cmd.exe may not search the current directory for a bare name', () => {
    expect(allowlist(true)[0]!.command).toBe('.\\mvnw.cmd');
  });

  it('passed: Maven exits 0 and reports the tests, with the duration', async () => {
    const r = await call('run_golden_test', {}, { golden: async () => run(0, PASS_OUT) });
    expect(r.structuredContent).toMatchObject({ status: 'passed', testsRun: 13, durationMs: 4200, output: null });
    expect(text(r)).toContain('PASSED');
    expect(text(r)).toContain('4.2 s');
  });

  it('failed: shows the relevant output', () => {
    const out = '[ERROR] Tests run: 13, Failures: 2, Errors: 0, Skipped: 0, Time elapsed: 4 s <<< FAILURE! -- in com.arthadhruva.riskengine.survival.TermStructureGoldenTest\n[ERROR] pd12m expected 0.0123 but was 0.0125\n[INFO] BUILD FAILURE';
    const r = interpret(run(1, out));
    expect(r).toMatchObject({ status: 'failed', testsRun: 13 });
    expect(r.output).toContain('pd12m expected 0.0123 but was 0.0125');
  });

  it('skipped, never passed, when the Maven wrapper cannot run', () => {
    for (const r of [run(127, '', 'spawn error: ENOENT'), run(1, '', 'The JAVA_HOME environment variable is not defined correctly'),
      run(1, '', "'mvnw.cmd' is not recognized as an internal or external command")]) {
      expect(interpret(r), r.stderr).toMatchObject({ status: 'skipped' });
      expect(interpret(r).reason).toContain('could not run');
    }
  });

  it('a compile error is a failure, not a skip; so is a run that reports no result, and a timeout', () => {
    expect(interpret(run(1, '[ERROR] COMPILATION ERROR :\n[INFO] BUILD FAILURE'))).toMatchObject({ status: 'failed' });
    expect(interpret(run(0, '[INFO] BUILD SUCCESS'))).toMatchObject({ status: 'failed', reason: expect.stringContaining('nothing was verified') });
    expect(interpret(run(null, '', '', { timedOut: true }))).toMatchObject({ status: 'failed', reason: expect.stringContaining('10 minutes') });
  });

  it('zero tests run, or a test skipped, is not a pass', () => {
    const none = '[INFO] Tests run: 0, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1 s -- in com.arthadhruva.riskengine.survival.TermStructureGoldenTest';
    expect(interpret(run(0, none))).toMatchObject({ status: 'failed' });
    expect(interpret(run(0, none.replace('Tests run: 0', 'Tests run: 3').replace('Skipped: 0', 'Skipped: 1')))).toMatchObject({ status: 'failed' });
  });
});

describe('forbidden commands', () => {
  const refused = ['export_survival_model.py', 'python export_model.py', 'fetch_macro.py', 'build_survival_dataset.py', 'golden_term_structure.py',
    'python -m pipeline.run', 'docker compose --profile notebook up', 'the notebooks', 'export the survival model', 'validate_regime_model.py', 'SomeOtherTest'];

  it('every one is Forbidden before anything runs, and the owner-only ones say the owner must run it', async () => {
    let started = 0;
    for (const test of refused) {
      const r = await call('run_golden_test', { test }, { golden: async () => { started++; return run(0, PASS_OUT); } });
      expect(errorOf(r)?.code, test).toBe('Forbidden');
      if (test !== 'SomeOtherTest') expect(errorOf(r)?.message, test).toContain('Only the owner runs those');
    }
    expect(started).toBe(0);
  });

  it('the one allowed name is accepted', () => {
    expect(() => assertAllowedTest('TermStructureGoldenTest')).not.toThrow();
  });
});

describe('re-run advisory', () => {
  it('names the golden script when an export script differs from the base', () => {
    const a = advise(['backend/export_survival_model.py', 'README.md']);
    expect(a.map((x) => x.script)).toEqual(['golden_term_structure.py', 'golden_multistate.py']);
    expect(a[0]!.message).toContain('the owner must re-run backend/golden_term_structure.py');
    expect(a[0]!.message).toContain('does not run');
  });

  it('says nothing when only unrelated files changed, and picks each model\'s own script', () => {
    expect(advise(['frontend/src/App.tsx', 'MODELS.md'])).toEqual([]);
    expect(advise(['backend/risk-engine/src/main/resources/early_warning_model.onnx']).map((x) => x.script)).toEqual(['golden_early_warning.py']);
    expect(advise(['backend/risk-engine/src/main/resources/model.onnx']).map((x) => x.script)).toEqual(['golden_pd_model.py']);
  });

  describe('in a real git repository', () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'mv-advisory-'));
    afterAll(() => fs.rmSync(dir, { recursive: true, force: true }));
    const git = (...args: string[]) => execFileSync('git', ['-c', 'user.name=t', '-c', 'user.email=t@example.test', '-c', 'commit.gpgsign=false', ...args], { cwd: dir, stdio: 'pipe', encoding: 'utf8' });
    fs.mkdirSync(path.join(dir, 'backend'), { recursive: true });
    fs.writeFileSync(path.join(dir, 'backend/export_survival_model.py'), 'v1\n');
    fs.writeFileSync(path.join(dir, 'README.md'), 'readme\n');
    git('init', '-q', '-b', 'main');
    git('add', '-A');
    git('commit', '-q', '-m', 'base');

    it('an unchanged tree needs no re-run', async () => {
      const r = await call('rerun_advisory', {}, { repo: gitRepo(dir) });
      expect(r.structuredContent).toMatchObject({ base: 'HEAD', rerunNeeded: false, advice: [] });
    });

    it('a changed export script says the owner must re-run golden_term_structure.py, and runs nothing', async () => {
      fs.writeFileSync(path.join(dir, 'backend/export_survival_model.py'), 'v2\n');
      const r = await call('rerun_advisory', {}, { repo: gitRepo(dir) });
      expect(r.structuredContent).toMatchObject({ rerunNeeded: true });
      const advice = (r.structuredContent as { advice: Array<{ script: string; test: string }> }).advice;
      expect(advice).toContainEqual(expect.objectContaining({ script: 'golden_term_structure.py', test: 'TermStructureGoldenTest' }));
      expect(advice.map((x) => x.script)).toContain('golden_multistate.py');   // the delinquency fixture reuses the survival engine
      expect(text(r)).toContain('golden_term_structure.py');
      expect(text(r)).toContain('owner');
    });

    it('a base that is not a commit, or that looks like an option, is InvalidInput', async () => {
      for (const base of ['no-such-ref', '--output=/tmp/x', 'a b', 'main..HEAD', '$(whoami)']) {
        expect(errorOf(await call('rerun_advisory', { base }, { repo: gitRepo(dir) }))?.code, base).toBe('InvalidInput');
      }
    });
  });
});

describe('drift and backtest reports', () => {
  const DRIFT = { population_stability_index: 0.0412, threshold: 0.25, features: [{ name: 'credit_score', psi: 0.01 }] };

  it('returns the API\'s report unaltered with its source and fetch time', async () => {
    const seen: Array<{ url: string; cookie: string | null }> = [];
    const fetchImpl = (async (url: string | URL | Request, init?: RequestInit) => {
      seen.push({ url: String(url), cookie: new Headers(init?.headers).get('Cookie') });
      return new Response(JSON.stringify(DRIFT), { status: 200 });
    }) as typeof fetch;
    const r = await call('drift_report', {}, { fetchImpl });
    expect(r.structuredContent).toMatchObject({ source: '/v1/models/pd_24m/drift', report: DRIFT });
    expect(Date.parse((r.structuredContent as { fetchedAt: string }).fetchedAt)).not.toBeNaN();
    expect(seen).toEqual([{ url: 'http://api.test/v1/models/pd_24m/drift', cookie: 'ad_session=a.b.c' }]);
    expect(text(r)).toContain('0.0412');
  });

  it('the survival backtest comes from its own endpoint', async () => {
    const fetchImpl = (async () => new Response('{"origins":3}', { status: 200 })) as unknown as typeof fetch;
    expect((await call('survival_backtest', {}, { fetchImpl })).structuredContent).toMatchObject({ source: '/v1/models/survival/backtest', report: { origins: 3 } });
  });

  it('without a session it says so, instead of reporting an expired one', async () => {
    const r = await call('drift_report', {}, { env: { RISK_ENGINE_SESSION: '' } });
    expect(errorOf(r)?.code).toBe('InvalidInput');
    expect(errorOf(r)?.message).toContain('RISK_ENGINE_SESSION is not set');
  });

  it('an expired session is reported as such', async () => {
    const fetchImpl = (async () => new Response('', { status: 401 })) as unknown as typeof fetch;
    expect(errorOf(await call('drift_report', {}, { fetchImpl }))).toMatchObject({ code: 'Forbidden', message: expect.stringContaining('session expired') });
  });
});

describe('declarations', () => {
  it('only the golden test runner is not read-only, and none is destructive', async () => {
    const server = createServer('/x', { RISK_ENGINE_SESSION: 'a.b.c' }, { repo: { read: () => undefined, changedSince: () => [], root: '/x' } });
    const { client, close } = await connect(server);
    const { tools } = await client.listTools();
    expect(tools.map((t) => t.name).sort()).toEqual(['drift_report', 'rerun_advisory', 'run_golden_test', 'survival_backtest', 'verify_artifacts']);
    expect(tools.filter((t) => !t.annotations?.readOnlyHint).map((t) => t.name)).toEqual(['run_golden_test']);
    expect(tools.every((t) => t.annotations?.destructiveHint === false)).toBe(true);
    await close();
  });
});
