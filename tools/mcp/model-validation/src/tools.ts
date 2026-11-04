import { defineTool, invalidInput, type AnyTool, type RunResult } from '@arthadhruva/mcp-kit';
import { z } from 'zod';
import { advise } from './advisory.js';
import type { ReportApi } from './api.js';
import { verify } from './artifacts.js';
import { ALLOWED_TEST, assertAllowedTest } from './forbidden.js';
import { interpret, type GoldenReport } from './golden.js';
import type { Repo } from './git.js';

/**
 * Checks on the committed model artifacts, never changes to them. Nothing here exports, retrains or regenerates
 * anything: the only process started is TermStructureGoldenTest, and when a golden script needs re-running the result
 * says that the owner must do it. The two reports are the API's own, passed through as it computed them.
 */
const READ = { readOnlyHint: true, destructiveHint: false, idempotentHint: true, openWorldHint: false } as const;
/** Runs a test through Maven: it writes build output under target/ and nothing tracked, so it is not read-only, and it is not destructive. */
const RUN = { readOnlyHint: false, destructiveHint: false, idempotentHint: true, openWorldHint: false } as const;

export interface Deps {
  repo: Repo;
  api: ReportApi;
  /** Whether RISK_ENGINE_SESSION was given; the two reports cannot be fetched without it. */
  sessionConfigured: boolean;
  runGolden: (signal?: AbortSignal) => Promise<RunResult>;
}

const report = z.object({ source: z.string(), fetchedAt: z.string(), report: z.record(z.string(), z.unknown()) });

export function tools(deps: Deps): AnyTool[] {
  async function fetchReport(path: string): Promise<{ source: string; fetchedAt: string; report: Record<string, unknown> }> {
    if (!deps.sessionConfigured) throw invalidInput('RISK_ENGINE_SESSION is not set, so the API cannot be asked for this report.');
    const body = await deps.api.get<Record<string, unknown>>(path);
    return { source: `/v1${path}`, fetchedAt: new Date().toISOString(), report: body };
  }

  return [
    defineTool({
      name: 'verify_artifacts',
      title: 'Verify artifact checksums',
      description: 'Computes the SHA-256 of each committed model artifact and compares it with the checksum its model card records '
        + '(the same contract the service enforces at start-up, checked without starting it). Each artifact is match, mismatch or missing; '
        + 'a mismatch shows both checksums, and any mismatch or missing file makes the whole result a failure.',
      input: {},
      output: {
        passed: z.boolean(),
        artifacts: z.array(z.object({
          model: z.string(), artifact: z.string(), card: z.string(), verdict: z.enum(['match', 'mismatch', 'missing']),
          declared: z.string().nullable(), actual: z.string().nullable(), detail: z.string().nullable(),
        })),
      },
      annotations: READ,
      uses: ['read'],
      handler: async () => {
        const { passed, outcomes } = verify(deps.repo);
        const bad = outcomes.filter((o) => o.verdict !== 'match');
        return {
          text: [passed ? `All ${outcomes.length} artifact checksums match their cards.` : `FAILED: ${bad.length} of ${outcomes.length} artifact checksum(s) do not match.`,
            ...bad.map((o) => (o.verdict === 'mismatch'
              ? `  MISMATCH ${o.artifact} (${o.model}): card ${o.card} declares ${o.declared}, the file is ${o.actual}`
              : `  MISSING ${o.artifact} (${o.model}): ${o.detail}`))].join('\n'),
          data: { passed, artifacts: outcomes },
        };
      },
    }),

    defineTool({
      name: 'run_golden_test',
      title: 'Run TermStructureGoldenTest',
      description: `Runs ${ALLOWED_TEST} through the Maven wrapper (it holds the Java engine to the independent Python implementation to 1e-6) `
        + 'with a 10-minute limit. Reports passed, failed or skipped; skipped (never passed) when Maven cannot run here. This is the only test '
        + 'it runs. Asking for an export, the pipeline or any other script is refused: the owner runs those.',
      input: { test: z.string().max(300).optional().describe(`only ${ALLOWED_TEST}`) },
      output: {
        status: z.enum(['passed', 'failed', 'skipped']),
        durationMs: z.number(),
        testsRun: z.number().nullable(),
        reason: z.string().nullable(),
        output: z.string().nullable(),
      },
      annotations: RUN,
      uses: ['runGolden'],
      handler: async (args, ctx) => {
        assertAllowedTest(args.test ?? ALLOWED_TEST);
        await ctx.progress.report(`Running ${ALLOWED_TEST} through Maven`);
        const result: GoldenReport = interpret(await deps.runGolden(ctx.progress.signal));
        const seconds = (result.durationMs / 1000).toFixed(1);
        return {
          text: result.status === 'passed' ? `PASSED: ${ALLOWED_TEST}, ${result.testsRun} test(s) in ${seconds} s.`
            : result.status === 'skipped' ? `SKIPPED (not passed): ${result.reason}.` + (result.output ? `\n${result.output}` : '')
              : `FAILED: ${result.reason}.\n${result.output ?? ''}`,
          data: result,
        };
      },
    }),

    defineTool({
      name: 'rerun_advisory',
      title: 'Golden scripts the owner must re-run',
      description: 'Compares the working tree with a base ref (default HEAD; for a branch use e.g. origin/dev) and, when a model artifact or the '
        + 'script that exports it changed, says which golden script the OWNER must re-run and which Java test then checks it. It never runs a '
        + 'golden script.',
      input: { base: z.string().max(100).optional().describe('a branch, tag or commit, default HEAD') },
      output: {
        base: z.string(),
        changedFiles: z.number(),
        rerunNeeded: z.boolean(),
        advice: z.array(z.object({ model: z.string(), script: z.string(), test: z.string(), changed: z.array(z.string()), message: z.string() })),
      },
      annotations: READ,
      uses: ['changedSince'],
      handler: async (args) => {
        const base = args.base ?? 'HEAD';
        const changed = deps.repo.changedSince(base);
        const advice = advise(changed);
        return {
          text: advice.length ? ['Golden scripts to re-run (by the owner):', ...advice.map((a) => `- ${a.message}`)].join('\n')
            : `No model artifact or export script differs from ${base}; the golden fixtures are not made stale by this change.`,
          data: { base, changedFiles: changed.length, rerunNeeded: advice.length > 0, advice },
        };
      },
    }),

    defineTool({
      name: 'drift_report',
      title: 'PD model drift',
      description: 'The population-stability report for the origination PD model, exactly as GET /v1/models/pd_24m/drift returns it, with its source and '
        + 'the time it was fetched. Metric names and thresholds are the API\'s own; nothing is recomputed or rounded here.',
      input: {},
      output: report.shape,
      annotations: READ,
      uses: ['get'],
      handler: async () => {
        const r = await fetchReport('/models/pd_24m/drift');
        return { text: `Drift report from ${r.source} at ${r.fetchedAt}:\n${JSON.stringify(r.report, null, 2)}`, data: r };
      },
    }),

    defineTool({
      name: 'survival_backtest',
      title: 'Survival model backtest',
      description: 'The survival model\'s backtest, exactly as GET /v1/models/survival/backtest returns it, with its source and the time it was fetched.',
      input: {},
      output: report.shape,
      annotations: READ,
      uses: ['get'],
      handler: async () => {
        const r = await fetchReport('/models/survival/backtest');
        return { text: `Backtest from ${r.source} at ${r.fetchedAt}:\n${JSON.stringify(r.report, null, 2)}`, data: r };
      },
    }),
  ];
}

