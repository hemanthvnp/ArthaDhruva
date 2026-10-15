import fs from 'node:fs';
import path from 'node:path';
import { notFound, type CommandRunner, type RunResult } from '@arthadhruva/mcp-kit';
import type { Repo } from './git.js';
import { isCiOnly, ORDER, type Job, type LocalStep } from './model.js';

/**
 * A local run of the CI checks. `start` returns at once with a run id; the steps run in the background, cheap
 * jobs first, and `status` reports the current step, elapsed time, an ETA and every result so far.
 *
 * Outcomes are honest: a step is passed only when every command exited 0. A step that needs Docker while
 * Docker is down, or whose tool is not installed, is skipped with that reason, and the verdict says so. The
 * run writes only under .git/ci-guard (its log and the step durations used for the next ETA).
 */
export type Outcome = 'passed' | 'failed' | 'skipped';

export interface StepResult {
  job: string;
  step: string;
  status: Outcome;
  reason?: string;
  seconds?: number;
  exitCode?: number;
}

export interface PlanEntry {
  job: string;
  steps: number;
}

export interface PreflightOptions {
  mode: 'full' | 'changed';
  jobs?: string[];
  /** Opt-in jobs to include (loadtest, failover, image). */
  include?: string[];
  failFast?: boolean;
}

export interface Status {
  runId: string;
  state: 'running' | 'done' | 'cancelled';
  current: string | null;
  plan: PlanEntry[];
  docker: boolean;
  elapsedSec: number;
  etaSec: number;
  results: StepResult[];
  verdict: string;
  failureLogTail?: string;
  logFile: string;
}

export interface Environment {
  dockerUp(): boolean;
}

interface RunState {
  id: string;
  startedAt: number;
  finishedAt?: number;
  state: Status['state'];
  current: string | null;
  plan: Job[];
  docker: boolean;
  results: StepResult[];
  logFile: string;
  controller: AbortController;
  done: Promise<void>;
}

const NOT_INSTALLED = 127;

export class Preflight {
  private readonly runs = new Map<string, RunState>();

  constructor(
    private readonly repo: Repo,
    private readonly runner: CommandRunner,
    private readonly jobs: readonly Job[],
    private readonly env: Environment,
  ) {}

  /** Which jobs a run with these options covers, and which are left out with their reasons. */
  plan(options: PreflightOptions): { jobs: Job[]; skipped: StepResult[] } {
    const files = options.mode === 'changed' ? this.repo.changedFiles().files : [];
    const touched = (j: Job) => j.paths.some((p) => (p === '' ? files.length > 0 : files.some((f) => f.startsWith(p))));
    const skipped: StepResult[] = [];
    const jobs = this.jobs.filter((j) => {
      if (options.jobs?.length && !options.jobs.includes(j.id)) return false;
      if (j.optIn && !options.include?.includes(j.id)) {
        skipped.push({ job: j.id, step: '(job)', status: 'skipped', reason: `opt-in: ${j.optIn}` });
        return false;
      }
      if (!j.steps.some((s) => !isCiOnly(s))) return false; // nothing to run locally (CodeQL, dependency review)
      if (options.mode === 'changed' && !touched(j)) return false;
      return true;
    });
    jobs.sort((a, b) => ORDER.indexOf(a.id) - ORDER.indexOf(b.id));
    return { jobs, skipped };
  }

  start(options: PreflightOptions): Status {
    const { jobs, skipped } = this.plan(options);
    const id = `${new Date().toISOString().replace(/[-:.TZ]/g, '').slice(0, 14)}-${Math.random().toString(36).slice(2, 6)}`;
    const run: RunState = {
      id, startedAt: Date.now(), state: 'running', current: null, plan: jobs, docker: this.env.dockerUp(),
      results: [...skipped], logFile: path.join(this.repo.stateDir(), 'runs', `${id}.log`),
      controller: new AbortController(), done: Promise.resolve(),
    };
    this.runs.set(id, run);
    run.done = this.execute(run, options.failFast ?? false);
    return this.status(id);
  }

  status(id: string): Status {
    const run = this.runs.get(id);
    if (!run) throw notFound(`No preflight run ${id} in this server (runs are kept in memory until the server restarts).`);
    const durations = this.durations();
    const done = new Set(run.results.map((r) => `${r.job}/${r.step}`));
    const remaining = run.plan.flatMap((j) => j.steps.filter((s) => !isCiOnly(s)).map((s) => ({ j, s })))
      .filter(({ j, s }) => !done.has(`${j.id}/${s.name}`))
      .reduce((sum, { j, s }) => sum + (durations[`${j.id}/${s.name}`] ?? j.fallbackSec / Math.max(1, j.steps.length)), 0);
    const failed = run.results.filter((r) => r.status === 'failed');
    const skippedRun = run.results.some((r) => r.status === 'skipped');
    return {
      runId: run.id,
      state: run.state,
      current: run.current,
      plan: run.plan.map((j) => ({ job: j.id, steps: j.steps.filter((s) => !isCiOnly(s)).length })),
      docker: run.docker,
      elapsedSec: Math.round(((run.finishedAt ?? Date.now()) - run.startedAt) / 1000),
      etaSec: run.state === 'running' ? Math.round(remaining) : 0,
      results: run.results,
      verdict: run.state === 'running' ? 'running'
        : run.state === 'cancelled' ? 'CANCELLED: incomplete, not a pass'
          : failed.length ? 'FAIL: fix before pushing'
            : skippedRun ? 'PASS with skipped checks (see the reasons)' : 'PASS',
      ...(failed.length ? { failureLogTail: tail(run.logFile, 40) } : {}),
      logFile: run.logFile,
    };
  }

  cancel(id: string): Status {
    const run = this.runs.get(id);
    if (!run) throw notFound(`No preflight run ${id}.`);
    run.controller.abort();
    return this.status(id);
  }

  /** Resolves when the run has finished (or been cancelled and its processes killed). */
  async finished(id: string): Promise<void> {
    const run = this.runs.get(id);
    if (!run) throw notFound(`No preflight run ${id}.`);
    await run.done;
  }

  private async execute(run: RunState, failFast: boolean): Promise<void> {
    const durations = this.durations();
    const log = fs.createWriteStream(run.logFile, { flags: 'a' });
    try {
      outer: for (const job of run.plan) {
        for (const step of job.steps) {
          if (run.controller.signal.aborted) break outer;
          if (isCiOnly(step)) continue;
          const result = await this.runStep(run, job, step, log);
          run.results.push(result);
          if (result.status === 'passed' && result.seconds !== undefined) durations[`${job.id}/${step.name}`] = result.seconds;
          if (result.status === 'failed' && failFast) break outer;
        }
      }
    } finally {
      run.current = null;
      run.state = run.controller.signal.aborted ? 'cancelled' : 'done';
      run.finishedAt = Date.now();
      await new Promise<void>((r) => log.end(r));
      fs.writeFileSync(path.join(this.repo.stateDir(), 'local-durations.json'), JSON.stringify(durations));
    }
  }

  private async runStep(run: RunState, job: Job, step: LocalStep, log: fs.WriteStream): Promise<StepResult> {
    const base = { job: job.id, step: step.name };
    if (step.needsDocker && !run.docker) return { ...base, status: 'skipped', reason: 'Docker is not running' };
    run.current = `${job.id} > ${step.name}`;
    const started = Date.now();
    if (step.check === 'dev-only-files') return { ...base, ...this.devOnlyFiles(), seconds: 0 };
    for (const id of step.commands ?? []) {
      log.write(`\n$ [${id}]\n`);
      const r: RunResult = await this.runner.run(id, { signal: run.controller.signal, onLine: (line) => log.write(`${line}\n`) });
      if (r.cancelled) return { ...base, status: 'skipped', reason: 'cancelled' };
      if (r.timedOut) return { ...base, status: 'failed', reason: 'timed out', seconds: secs(started) };
      if (notInstalled(r)) {
        return { ...base, status: 'skipped', reason: `tool not installed (${id})` };
      }
      if (r.code !== 0) return { ...base, status: 'failed', exitCode: r.code ?? -1, seconds: secs(started) };
    }
    return { ...base, status: 'passed', seconds: secs(started) };
  }

  /** CI's dev-only check: on staging, prod or a release branch, nothing under deploy/azure/network is tracked. */
  private devOnlyFiles(): Pick<StepResult, 'status' | 'reason'> {
    const branch = this.repo.currentBranch();
    if (!/^(release\/|staging$|prod$)/.test(branch)) return { status: 'passed', reason: `not a promotion branch (${branch || 'detached'})` };
    const found = this.repo.trackedFiles('deploy/azure/network');
    return found.length ? { status: 'failed', reason: `deploy/azure/network/ is tracked on ${branch}: ${found.slice(0, 5).join(', ')}` } : { status: 'passed' };
  }

  private durations(): Record<string, number> {
    try {
      return JSON.parse(fs.readFileSync(path.join(this.repo.stateDir(), 'local-durations.json'), 'utf8')) as Record<string, number>;
    } catch {
      return {};
    }
  }
}

/** A missing tool: spawn failed (127), or cmd.exe could not find it and the command printed nothing else. */
function notInstalled(r: RunResult): boolean {
  if (r.code === NOT_INSTALLED && /spawn error/.test(r.stderr)) return true;
  return r.code === 1 && r.stdout.trim() === '' && /is not recognized as an internal or external command/.test(r.stderr);
}

const secs = (since: number) => Math.round((Date.now() - since) / 1000);

function tail(file: string, n: number): string {
  try {
    return fs.readFileSync(file, 'utf8').split('\n').slice(-n).join('\n');
  } catch {
    return '';
  }
}
