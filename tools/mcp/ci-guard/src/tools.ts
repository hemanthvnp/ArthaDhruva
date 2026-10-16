import { defineTool, invalidInput, markMutating, type AnyTool } from '@arthadhruva/mcp-kit';
import { z } from 'zod';
import type { Repo } from './git.js';
import { typicalTime, type GitHub } from './history.js';
import { isCiOnly, JOBS, type Job } from './model.js';
import type { Preflight, Status } from './preflight.js';
import { staticChecks } from './static.js';

export interface Deps {
  repo: Repo;
  preflight: Preflight;
  github: GitHub;
  jobs?: readonly Job[];
}

const stepResult = z.object({
  job: z.string(), step: z.string(), status: z.enum(['passed', 'failed', 'skipped']),
  reason: z.string().optional(), seconds: z.number().optional(), exitCode: z.number().optional(),
});

const statusShape = {
  runId: z.string(),
  state: z.enum(['running', 'done', 'cancelled']),
  current: z.string().nullable(),
  plan: z.array(z.object({ job: z.string(), steps: z.number() })),
  docker: z.boolean(),
  elapsedSec: z.number(),
  etaSec: z.number(),
  results: z.array(stepResult),
  verdict: z.string(),
  failureLogTail: z.string().optional(),
  logFile: z.string(),
};

const fmt = (s: number) => (s >= 60 ? `${Math.floor(s / 60)}m${String(Math.round(s % 60)).padStart(2, '0')}s` : `${Math.round(s)}s`);

function describe(s: Status): string {
  const lines = [`Preflight ${s.runId}: ${s.verdict}`, `elapsed ${fmt(s.elapsedSec)}${s.state === 'running' ? `, about ${fmt(s.etaSec)} left, now: ${s.current ?? 'starting'}` : ''}`];
  if (!s.docker) lines.push('Docker is not running: steps that need it are skipped, not passed.');
  for (const r of s.results) lines.push(`  ${r.status.padEnd(7)} ${r.job} > ${r.step}${r.reason ? ` (${r.reason})` : ''}${r.seconds !== undefined ? ` ${fmt(r.seconds)}` : ''}`);
  if (s.failureLogTail) lines.push('', 'Last lines of the log:', s.failureLogTail);
  return lines.join('\n');
}

export function tools(deps: Deps): AnyTool[] {
  const jobs = deps.jobs ?? JOBS;
  const preflight = markMutating(deps.preflight, ['start', 'cancel']);
  const ids = jobs.map((j) => j.id);

  return [
    defineTool({
      name: 'preflight',
      title: 'Run the CI checks locally',
      description: 'Starts the CI checks on the working tree and returns a run id at once; poll preflight_status, or call preflight_wait '
        + 'for progress. Default: every job that can run locally, cheapest first, with the k6 load test, the failover test and the '
        + 'container scan skipped (opt in with include). mode "changed" limits it to jobs whose paths the diff touches. Steps that need '
        + 'Docker or a missing tool are reported as skipped, never as passed. Never pushes, commits or edits tracked files; note that '
        + '"Install dependencies" runs npm ci, which reinstalls frontend/node_modules.',
      input: {
        mode: z.enum(['full', 'changed']).default('full'),
        jobs: z.array(z.string()).optional().describe(`only these jobs: ${ids.join(', ')}`),
        include: z.array(z.enum(['loadtest', 'failover', 'image'])).optional().describe('opt-in jobs to run as well'),
        failFast: z.boolean().default(false),
      },
      output: statusShape,
      annotations: { readOnlyHint: false, destructiveHint: false, idempotentHint: false, openWorldHint: false },
      uses: ['start'],
      handler: async ({ mode, jobs: only, include, failFast }) => {
        const unknown = (only ?? []).filter((j) => !ids.includes(j));
        if (unknown.length) throw invalidInput(`Unknown job(s) ${unknown.join(', ')}; known: ${ids.join(', ')}.`);
        const s = preflight.start({ mode, failFast, ...(only ? { jobs: only } : {}), ...(include ? { include } : {}) });
        return { text: describe(s), data: s };
      },
    }),
    defineTool({
      name: 'preflight_status',
      title: 'Preflight progress',
      description: 'Current step, elapsed time, an ETA from this machine\'s past step durations, every result so far and the verdict.',
      input: { runId: z.string() },
      output: statusShape,
      annotations: { readOnlyHint: true, destructiveHint: false, idempotentHint: true },
      uses: ['status'],
      handler: async ({ runId }) => {
        const s = preflight.status(runId);
        return { text: describe(s), data: s };
      },
    }),
    defineTool({
      name: 'preflight_wait',
      title: 'Wait for a preflight',
      description: 'Waits up to maxSeconds for a run to finish, sending MCP progress notifications as steps complete. Cancelling this '
        + 'call cancels the run and kills its processes.',
      input: { runId: z.string(), maxSeconds: z.number().int().min(1).max(3600).default(600) },
      output: statusShape,
      annotations: { readOnlyHint: false, destructiveHint: false, idempotentHint: false },
      uses: ['status', 'finished', 'cancel'],
      handler: async ({ runId, maxSeconds }, { progress }) => {
        let s = preflight.status(runId);
        const total = s.plan.reduce((a, p) => a + p.steps, 0);
        const until = Date.now() + maxSeconds * 1000;
        const onAbort = () => preflight.cancel(runId);
        progress.signal.addEventListener('abort', onAbort, { once: true });
        try {
          while (s.state === 'running' && Date.now() < until && !progress.signal.aborted) {
            await progress.report(s.current ?? 'starting', s.results.filter((r) => r.job !== undefined && r.step !== '(job)').length, total);
            await Promise.race([preflight.finished(runId), new Promise((r) => setTimeout(r, 1000))]);
            s = preflight.status(runId);
          }
          if (progress.signal.aborted) await preflight.finished(runId);
        } finally {
          progress.signal.removeEventListener('abort', onAbort);
        }
        s = preflight.status(runId);
        return { text: describe(s), data: s };
      },
    }),
    defineTool({
      name: 'preflight_cancel',
      title: 'Cancel a preflight',
      description: 'Stops a run and kills the process tree of the step in progress (Maven, npm). An incomplete run is never a pass.',
      input: { runId: z.string() },
      output: statusShape,
      annotations: { readOnlyHint: false, destructiveHint: false, idempotentHint: true },
      uses: ['cancel', 'finished', 'status'],
      handler: async ({ runId }) => {
        preflight.cancel(runId);
        await preflight.finished(runId);
        const s = preflight.status(runId);
        return { text: describe(s), data: s };
      },
    }),
    defineTool({
      name: 'static_checks',
      title: 'Platform-drift checks',
      description: 'Concrete findings that make CI fail where a local run passes: CRLF shell scripts or mvnw, package.json changed '
        + 'without its lockfile, case-only path collisions, committed secrets paths, a Node major different from CI\'s, model '
        + 'artifacts changed without their card, dev-only files on a release branch. Takes seconds and runs nothing.',
      input: {},
      output: {
        base: z.string(), changedFiles: z.number(),
        findings: z.array(z.object({ severity: z.enum(['high', 'medium', 'low']), job: z.string(), message: z.string() })),
      },
      annotations: { readOnlyHint: true, destructiveHint: false, idempotentHint: true },
      uses: ['changedFiles', 'trackedFiles', 'read', 'currentBranch'],
      handler: async () => {
        const { base, files } = deps.repo.changedFiles();
        const findings = staticChecks(deps.repo, files);
        const text = findings.length
          ? [`${findings.length} finding(s) against ${base} (${files.length} changed files):`, ...findings.map((f) => `  [${f.severity}] ${f.job}: ${f.message}`)].join('\n')
          : `No findings against ${base} (${files.length} changed files).`;
        return { text, data: { base, changedFiles: files.length, findings } };
      },
    }),
    defineTool({
      name: 'typical_time',
      title: 'Typical CI time',
      description: 'Per-job median and 90th percentile of successful GitHub Actions runs, and the longest chain as the expected total. '
        + 'A job with fewer than three successful runs is labelled "fallback guess". Gives no failure probabilities: use '
        + 'static_checks for concrete risks. Needs the gh CLI signed in; without it every job is a fallback guess.',
      input: { runs: z.number().int().min(5).max(100).default(30) },
      output: {
        jobs: z.array(z.object({ job: z.string(), p50Sec: z.number(), p90Sec: z.number(), samples: z.number(), source: z.enum(['history', 'fallback guess']) })),
        expectedTotalSec: z.number(), p90TotalSec: z.number(), criticalPath: z.array(z.string()), runsRead: z.number(), note: z.string().optional(),
      },
      annotations: { readOnlyHint: true, destructiveHint: false, idempotentHint: true, openWorldHint: true },
      uses: ['recentRuns'],
      handler: async ({ runs }) => {
        let history: ReturnType<GitHub['recentRuns']> = [];
        let note: string | undefined;
        try {
          history = deps.github.recentRuns(runs);
        } catch (e) {
          note = `GitHub history unavailable (${String((e as Error).message).split('\n')[0]}); all jobs use fallback guesses.`;
        }
        const t = typicalTime(jobs, history, note);
        const text = [`Expected CI time ${fmt(t.expectedTotalSec)} (p90 ${fmt(t.p90TotalSec)}), critical path ${t.criticalPath.join(' -> ')}, from ${t.runsRead} runs.`,
          ...t.jobs.map((j) => `  ${j.job}: ${fmt(j.p50Sec)} (p90 ${fmt(j.p90Sec)}, ${j.source}, n=${j.samples})`), ...(note ? [note] : [])].join('\n');
        return { text, data: t };
      },
    }),
    defineTool({
      name: 'job_model',
      title: 'CI job model',
      description: 'Every CI job and step this server knows, what runs locally and why the rest is CI-only.',
      input: {},
      output: {
        jobs: z.array(z.object({
          id: z.string(), workflow: z.string(), optIn: z.string().optional(),
          steps: z.array(z.object({ name: z.string(), local: z.boolean(), needsDocker: z.boolean(), ciOnly: z.string().optional() })),
        })),
      },
      annotations: { readOnlyHint: true, destructiveHint: false, idempotentHint: true },
      uses: [],
      handler: async () => {
        const data = {
          jobs: jobs.map((j) => ({
            id: j.id, workflow: j.workflow, ...(j.optIn ? { optIn: j.optIn } : {}),
            steps: j.steps.map((s) => isCiOnly(s)
              ? { name: s.name, local: false, needsDocker: false, ciOnly: s.ciOnly }
              : { name: s.name, local: true, needsDocker: s.needsDocker ?? false }),
          })),
        };
        const text = data.jobs.map((j) => `${j.workflow}/${j.id}${j.optIn ? ' (opt-in)' : ''}: ${j.steps.map((s) => `${s.name}${s.local ? '' : ' [CI only]'}`).join('; ') || 'no named steps'}`).join('\n');
        return { text, data };
      },
    }),
  ];
}
