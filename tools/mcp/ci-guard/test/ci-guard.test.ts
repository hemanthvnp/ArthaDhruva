import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import type { CallToolResult } from '@modelcontextprotocol/sdk/types.js';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { CommandRunner, Redactor, buildServer, connect, errorOf } from '@arthadhruva/mcp-kit';
import { gitRepo, type Repo } from '../src/git.js';
import { typicalTime, type GitHub, type Run } from '../src/history.js';
import { JOBS, type Job } from '../src/model.js';
import { Preflight } from '../src/preflight.js';
import { staticChecks } from '../src/static.js';
import { tools } from '../src/tools.js';
import { missingFromModel, readWorkflows } from '../src/workflows.js';

const REPO_ROOT = path.resolve(fileURLToPath(new URL('../../../../', import.meta.url)));

// ---------------------------------------------------------------- a throwaway repository and a toy CI

let dir: string;
const git = (...args: string[]) => execFileSync('git', args, { cwd: dir, encoding: 'utf8' });

const TOY: Job[] = [
  { id: 'backend', workflow: 'CI', githubName: 'backend', paths: ['src/'], fallbackSec: 300, steps: [{ name: 'Tests', commands: ['ok'] }] },
  { id: 'frontend', workflow: 'CI', githubName: 'frontend', paths: ['web/'], fallbackSec: 100, steps: [
    { name: 'Set up Node.js', ciOnly: 'set-up' },
    { name: 'Lint', commands: ['ok'] },
    { name: 'Build', commands: ['fail'] },
  ] },
  { id: 'deployment-files', workflow: 'CI', githubName: 'deployment-files', paths: ['deploy/'], fallbackSec: 60, steps: [
    { name: 'Alert rules pass their unit tests', commands: ['ok'], needsDocker: true },
    { name: 'Missing tool', commands: ['missing'] },
  ] },
  { id: 'secrets', workflow: 'Security', githubName: 'Secret scan', paths: [''], fallbackSec: 45, steps: [{ name: 'Scan', commands: ['ok'] }] },
  { id: 'loadtest', workflow: 'CI', githubName: 'loadtest', paths: [''], fallbackSec: 240, after: 'backend', optIn: 'boots the stack', steps: [{ name: 'Run k6', ciOnly: 'opt-in' }] },
  { id: 'slow', workflow: 'CI', githubName: 'slow', paths: [''], fallbackSec: 60, optIn: 'test only', steps: [{ name: 'Sleep', commands: ['slow'] }] },
];

let runner: CommandRunner;
let repo: Repo;

beforeAll(() => {
  dir = fs.mkdtempSync(path.join(os.tmpdir(), 'ci-guard-'));
  git('init', '-q', '-b', 'dev');
  git('config', 'user.email', 'test@example.test');
  git('config', 'user.name', 'test');
  git('config', 'core.autocrlf', 'false');
  fs.mkdirSync(path.join(dir, 'scripts'));
  fs.writeFileSync(path.join(dir, 'README.md'), 'toy\n');
  git('add', '.');
  git('commit', '-q', '-m', 'init');
  fs.writeFileSync(path.join(dir, 'slow.mjs'), `
import { spawn } from 'node:child_process';
const child = spawn(process.execPath, ['-e', 'setInterval(() => {}, 1000)'], { stdio: 'ignore' });
console.log('child ' + child.pid);
setInterval(() => {}, 1000);`);
  runner = new CommandRunner(dir, [
    { id: 'ok', command: process.execPath, args: ['-e', 'console.log("fine")'] },
    { id: 'fail', command: process.execPath, args: ['-e', 'console.error("type error in App.tsx"); process.exit(2)'] },
    { id: 'missing', command: 'definitely-not-a-real-tool-xyz', args: [] },
    { id: 'slow', command: process.execPath, args: ['slow.mjs'], timeoutMs: 60_000 },
  ]);
  repo = gitRepo(dir);
});

afterAll(() => fs.rmSync(dir, { recursive: true, force: true }));

const preflightWith = (docker: boolean) => new Preflight(repo, runner, TOY, { dockerUp: () => docker });

// ---------------------------------------------------------------- the model mirrors CI

describe('job model', () => {
  it('covers every job and named step in ci.yml and security.yml', () => {
    expect(missingFromModel(readWorkflows(REPO_ROOT))).toEqual([]);
  });

  it('names a step added to a workflow without updating the model', () => {
    const workflows = readWorkflows(REPO_ROOT).map((w) => (w.id === 'frontend' ? { ...w, steps: [...w.steps, 'Run Playwright'] } : w));
    expect(missingFromModel(workflows)).toEqual(["CI job 'frontend' step 'Run Playwright'"]);
    expect(missingFromModel([{ workflow: 'CI', id: 'e2e', steps: [] }])).toEqual(["CI job 'e2e'"]);
  });

  it('gives every local step allowlisted commands or a built-in check', () => {
    for (const job of JOBS) {
      for (const step of job.steps) {
        if ('ciOnly' in step) expect(step.ciOnly.length).toBeGreaterThan(10);
        else expect((step.commands?.length ?? 0) > 0 || step.check !== undefined).toBe(true);
      }
    }
  });
});

// ---------------------------------------------------------------- preflight

describe('preflight', () => {
  it('returns a run id at once, runs cheap jobs first and reports honest outcomes', async () => {
    const p = preflightWith(false);
    const started = p.start({ mode: 'full' });
    expect(started.state).toBe('running');
    expect(started.runId).toMatch(/^\d{14}-/);
    expect(started.plan.map((j) => j.job)).toEqual(['secrets', 'deployment-files', 'frontend', 'backend']);
    expect(started.results).toContainEqual({ job: 'loadtest', step: '(job)', status: 'skipped', reason: 'opt-in: boots the stack' });

    await p.finished(started.runId);
    const s = p.status(started.runId);
    const by = (step: string) => s.results.find((r) => r.step === step);
    expect(by('Alert rules pass their unit tests')).toMatchObject({ status: 'skipped', reason: 'Docker is not running' });
    expect(by('Missing tool')).toMatchObject({ status: 'skipped', reason: 'tool not installed (missing)' });
    expect(by('Build')).toMatchObject({ status: 'failed', exitCode: 2 });
    expect(by('Lint')).toMatchObject({ status: 'passed' });
    expect(by('Set up Node.js')).toBeUndefined();
    expect(s.verdict).toBe('FAIL: fix before pushing');
    expect(s.failureLogTail).toContain('type error in App.tsx');
  });

  it('says PASS with skipped checks when nothing failed but something was skipped', async () => {
    const p = preflightWith(false);
    const s0 = p.start({ mode: 'full', jobs: ['deployment-files', 'secrets'] });
    await p.finished(s0.runId);
    expect(p.status(s0.runId).verdict).toBe('PASS with skipped checks (see the reasons)');
  });

  it('limits a changed-mode run to the jobs the diff touches', () => {
    fs.mkdirSync(path.join(dir, 'web'), { recursive: true });
    fs.writeFileSync(path.join(dir, 'web', 'App.tsx'), 'export {}\n');
    const { jobs } = preflightWith(true).plan({ mode: 'changed' });
    expect(jobs.map((j) => j.id)).toEqual(['secrets', 'frontend']);
    fs.rmSync(path.join(dir, 'web'), { recursive: true, force: true });
  });

  it('cancels a run, kills its process tree and never calls it a pass', async () => {
    const p = preflightWith(true);
    const s0 = p.start({ mode: 'full', jobs: ['slow'], include: ['slow'] as never });
    await new Promise((r) => setTimeout(r, 1500));
    const log = fs.readFileSync(s0.logFile, 'utf8');
    const childPid = Number(/child (\d+)/.exec(log)?.[1]);
    expect(childPid).toBeGreaterThan(0);
    p.cancel(s0.runId);
    await p.finished(s0.runId);
    const s = p.status(s0.runId);
    expect(s.state).toBe('cancelled');
    expect(s.verdict).toMatch(/^CANCELLED/);
    let gone = false;
    for (let i = 0; i < 50 && !gone; i++) {
      try { process.kill(childPid, 0); await new Promise((r) => setTimeout(r, 100)); } catch { gone = true; }
    }
    expect(gone).toBe(true);
  });

  it('leaves the working tree and refs alone and writes only under .git/ci-guard', async () => {
    const before = { status: git('status', '--porcelain', '--untracked-files=all'), refs: git('show-ref'), head: git('rev-parse', 'HEAD') };
    const p = preflightWith(true);
    const s0 = p.start({ mode: 'full' });
    await p.finished(s0.runId);
    expect(git('status', '--porcelain', '--untracked-files=all')).toBe(before.status);
    expect(git('show-ref')).toBe(before.refs);
    expect(git('rev-parse', 'HEAD')).toBe(before.head);
    expect(path.relative(dir, s0.logFile).split(path.sep).slice(0, 2)).toEqual(['.git', 'ci-guard']);
  });
});

// ---------------------------------------------------------------- static checks

describe('static checks', () => {
  const fakeRepo = (files: Record<string, string>, tracked: string[] = Object.keys(files), branch = 'dev'): Repo => ({
    root: '/x',
    changedFiles: () => ({ base: 'origin/dev', files: Object.keys(files) }),
    trackedFiles: () => tracked,
    currentBranch: () => branch,
    read: (f) => (f in files ? Buffer.from(files[f]!, 'latin1') : undefined),
    stateDir: () => '/x/.git/ci-guard',
  });

  it('flags a CRLF shell script as high severity, naming it', () => {
    const r = fakeRepo({ 'scripts/deploy.sh': '#!/bin/sh\r\necho hi\r\n' });
    expect(staticChecks(r, ['scripts/deploy.sh'], '22.1.0')).toEqual([
      { severity: 'high', job: 'deployment-files', message: expect.stringContaining('scripts/deploy.sh has CRLF') },
    ]);
  });

  it('flags lockfile drift, case collisions, secrets paths, a Node mismatch and model artifacts', () => {
    const files = { 'frontend/package.json': '{}', '.env.local': 'X=1', 'backend/risk-engine/src/main/resources/model.onnx': '' };
    const r = fakeRepo(files, ['Docs/a.md', 'docs/a.md', ...Object.keys(files)]);
    const messages = staticChecks(r, Object.keys(files), '24.16.0').map((f) => `${f.severity} ${f.message}`);
    expect(messages).toEqual(expect.arrayContaining([
      expect.stringMatching(/^high frontend\/package.json changed but its package-lock.json did not/),
      expect.stringMatching(/^high Case-only path collision: Docs\/a.md and docs\/a.md/),
      expect.stringMatching(/^high .env.local is a secrets path/),
      expect.stringMatching(/^low Local Node is 24; CI uses 22/),
      expect.stringMatching(/^medium A model artifact/),
    ]));
  });

  it('does not flag .env.example or a clean change', () => {
    expect(staticChecks(fakeRepo({ '.env.example': 'X=' }), ['.env.example'], '22.0.0')).toEqual([]);
  });
});

// ---------------------------------------------------------------- typical time

describe('typical CI time', () => {
  const run = (jobs: Array<[string, number, string?]>, i: number): Run => ({
    id: i, workflow: 'CI', conclusion: 'success', createdAt: `2026-10-0${i}T00:00:00Z`,
    jobs: jobs.map(([name, seconds, conclusion]) => ({ name, seconds, conclusion: conclusion ?? 'success' })),
  });

  it('uses medians from history and labels sparse jobs as fallback guesses', () => {
    const runs = [1, 2, 3].map((i) => run([['backend', 200 + i * 10], ['frontend', 90], ...(i < 3 ? [['loadtest', 100] as [string, number]] : [])], i));
    const t = typicalTime(TOY, runs);
    const by = (id: string) => t.jobs.find((j) => j.job === id)!;
    expect(by('backend')).toMatchObject({ p50Sec: 220, samples: 3, source: 'history' });
    expect(by('loadtest')).toMatchObject({ samples: 2, source: 'fallback guess', p50Sec: 240 });
    expect(t.criticalPath).toEqual(['backend', 'loadtest']);
    expect(t.expectedTotalSec).toBe(220 + 240 + 15);
    expect(JSON.stringify(t)).not.toMatch(/probab|risk/i);
  });

  it('counts matrix jobs and ignores failed runs for duration', () => {
    const runs = [1, 2, 3].map((i) => run([['CodeQL (python)', 100], ['backend', 999, 'failure']], i));
    const jobs: Job[] = [{ ...TOY[0]! }, { id: 'codeql', workflow: 'Security', githubName: 'CodeQL', paths: [''], fallbackSec: 1, steps: [] }];
    const t = typicalTime(jobs, runs);
    expect(t.jobs.find((j) => j.job === 'codeql')).toMatchObject({ source: 'history', p50Sec: 100 });
    expect(t.jobs.find((j) => j.job === 'backend')).toMatchObject({ source: 'fallback guess', samples: 0 });
  });
});

// ---------------------------------------------------------------- the tools over the protocol

describe('ci-guard tools over MCP', () => {
  const noGh: GitHub = { recentRuns: () => { throw new Error('gh: command not found'); } };

  async function client(docker = false) {
    const preflight = preflightWith(docker);
    const server = buildServer({
      name: 'ci-guard', version: '0', instructions: '', tools: tools({ repo, preflight, github: noGh, jobs: TOY }),
      adapters: [preflight, repo, noGh], redactor: new Redactor(), sink: () => undefined,
    });
    return connect(server);
  }

  it('starts, polls, waits for and cancels a preflight', async () => {
    const { client: c, close } = await client();
    const started = (await c.callTool({ name: 'preflight', arguments: { jobs: ['secrets'] } })) as CallToolResult;
    const runId = (started.structuredContent as { runId: string }).runId;
    const progress: number[] = [];
    const waited = (await c.callTool({ name: 'preflight_wait', arguments: { runId, maxSeconds: 30 } }, undefined,
      { onprogress: (p) => progress.push(p.progress) })) as CallToolResult;
    expect((waited.structuredContent as { verdict: string }).verdict).toBe('PASS');
    const status = (await c.callTool({ name: 'preflight_status', arguments: { runId } })) as CallToolResult;
    expect((status.content[0] as { text: string }).text).toContain('passed  secrets > Scan');
    const unknown = (await c.callTool({ name: 'preflight_status', arguments: { runId: 'nope' } })) as CallToolResult;
    expect(errorOf(unknown)?.code).toBe('NotFound');
    const bad = (await c.callTool({ name: 'preflight', arguments: { jobs: ['nope'] } })) as CallToolResult;
    expect(errorOf(bad)?.code).toBe('InvalidInput');
    await close();
  });

  it('reports typical time as fallback guesses when gh is unavailable, and lists the model', async () => {
    const { client: c, close } = await client();
    const t = (await c.callTool({ name: 'typical_time', arguments: {} })) as CallToolResult;
    const data = t.structuredContent as { note: string; jobs: Array<{ source: string }> };
    expect(data.note).toContain('GitHub history unavailable');
    expect(data.jobs.every((j) => j.source === 'fallback guess')).toBe(true);
    const model = (await c.callTool({ name: 'job_model', arguments: {} })) as CallToolResult;
    expect((model.content[0] as { text: string }).text).toContain('Set up Node.js [CI only]');
    const s = (await c.callTool({ name: 'static_checks', arguments: {} })) as CallToolResult;
    expect(s.isError).toBeFalsy();
    await close();
  });

  it('declares annotations honestly', async () => {
    const { client: c, close } = await client();
    const { tools: listed } = await c.listTools();
    const ann = Object.fromEntries(listed.map((t) => [t.name, t.annotations]));
    expect(ann.preflight).toMatchObject({ readOnlyHint: false, destructiveHint: false });
    expect(ann.static_checks).toMatchObject({ readOnlyHint: true });
    expect(ann.typical_time).toMatchObject({ readOnlyHint: true, openWorldHint: true });
    await close();
  });
});
