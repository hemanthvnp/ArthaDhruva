import { defineTool, invalidInput, markMutating, ToolError, type AnyTool } from '@arthadhruva/mcp-kit';
import { z } from 'zod';
import type { Az } from './azure.js';
import { assess, type Evidence } from './health.js';
import { METRICS, METRIC_NAMES, type MetricName, type Prometheus } from './prometheus.js';
import type { WakeClient } from './wake.js';

/**
 * The deployed stack, read-only except for `wake`. Each source is optional and fails soft in `health` (a note says
 * what could not be asked), because a stack that is asleep cannot answer most of its own questions and that is not
 * an error. Nothing here starts, stops or changes Azure resources; the one thing that can start the VM is the wake
 * function, through `wake`, which is idempotent.
 */
const READ = { readOnlyHint: true, destructiveHint: false, idempotentHint: true, openWorldHint: true } as const;
/** Starts a stopped VM, which costs money: not read-only, not destructive, safe to repeat. */
const START = { readOnlyHint: false, destructiveHint: false, idempotentHint: true, openWorldHint: true } as const;

export interface Deps {
  az: Az | undefined;
  wake: WakeClient | undefined;
  prometheus: Prometheus;
  /** Asks whether the application answers; undefined when no application URL is configured. */
  appAnswers: (() => Promise<boolean>) | undefined;
  /** The longest any one network call here can take, for the descriptions. */
  maxCallSeconds: number;
}

const DOWN = new Set(['deallocated', 'stopped']);

export function tools(deps: Deps): AnyTool[] {
  const wake = deps.wake ? markMutating(deps.wake, ['wake']) : undefined;

  /** The VM's power state from the best source available, and notes on the ones that could not answer. */
  async function power(notes: string[]): Promise<{ state: string | null; source: 'az' | 'wake' | null; appUp?: boolean }> {
    if (deps.az) {
      const r = await deps.az.powerState();
      if (r.state) return { state: r.state, source: 'az' };
      notes.push(`Power state via az unavailable: ${r.reason}.`);
    }
    if (deps.wake) {
      try {
        const s = await deps.wake.status();
        return { state: s.state, source: 'wake', appUp: s.appUp };
      } catch (e) {
        notes.push(`Power state via the wake function unavailable: ${e instanceof ToolError ? e.message : 'failed'}`);
      }
    }
    return { state: null, source: null };
  }

  return [
    defineTool({
      name: 'health',
      title: 'Deployment health',
      description: `Where the deployed stack stands: asleep, starting, going to sleep, healthy, degraded or unhealthy. A deallocated VM is the `
        + `normal idle state and is reported as "asleep" with a hint, never as an error. Sources (power state via az or the wake function, the `
        + `application's answer, Prometheus) are each optional and fail soft with a note. At most about ${deps.maxCallSeconds} s per source.`,
      input: {},
      output: {
        state: z.enum(['healthy', 'degraded', 'asleep', 'starting', 'going-to-sleep', 'unhealthy', 'unknown']),
        normal: z.boolean(),
        hint: z.string(),
        powerState: z.string().nullable(),
        powerSource: z.enum(['az', 'wake']).nullable(),
        applicationAnswers: z.boolean().nullable(),
        backendUp: z.boolean().nullable(),
        firingAlerts: z.object({ critical: z.number(), other: z.number() }).nullable(),
        notes: z.array(z.string()),
      },
      annotations: READ,
      uses: ['powerState', 'status', 'instant', 'alerts'],
      handler: async () => {
        const notes: string[] = [];
        const p = await power(notes);
        const asleep = p.state !== null && (DOWN.has(p.state) || p.state === 'deallocating' || p.state === 'stopping' || p.state === 'starting');
        // An asleep or starting VM cannot answer: do not ask the application or Prometheus, and do not call that a failure.
        let appAnswers: boolean | null = null;
        if (!asleep && deps.appAnswers) appAnswers = p.appUp ?? await deps.appAnswers();
        else if (!asleep && p.appUp !== undefined) appAnswers = p.appUp;
        let backendUp: boolean | null = null;
        let firing: Evidence['firing'] = null;
        if (!asleep && appAnswers !== false) {
          try {
            const up = await deps.prometheus.instant('backend_up');
            backendUp = up.length === 0 ? null : up.every((s) => s.value === 1);
            const alerts = (await deps.prometheus.alerts()).filter((a) => a.state === 'firing');
            firing = { critical: alerts.filter((a) => a.severity === 'critical').length, other: alerts.filter((a) => a.severity !== 'critical').length };
          } catch (e) {
            notes.push(`Prometheus could not be asked: ${e instanceof ToolError ? e.message : 'failed'}`);
          }
        }
        const a = assess({ power: p.state, appAnswers, backendUp, firing });
        return {
          text: `${a.state.toUpperCase()}${a.normal ? '' : ' (needs attention)'}: ${a.hint}${notes.length ? `\n${notes.join('\n')}` : ''}`,
          data: { state: a.state, normal: a.normal, hint: a.hint, powerState: p.state, powerSource: p.source, applicationAnswers: appAnswers, backendUp, firingAlerts: firing, notes },
        };
      },
    }),

    defineTool({
      name: 'metric_query',
      title: 'Metric',
      description: `The current value of one named metric from Prometheus, with the time it was evaluated. Choose from: ${METRIC_NAMES.join(', ')}. `
        + 'Only these fixed queries exist; there is no free-form query. When the stack is asleep Prometheus has nothing to say and the result is UpstreamDown.',
      input: { metric: z.string().max(60).describe(`one of ${METRIC_NAMES.join(', ')}`) },
      output: {
        metric: z.string(), description: z.string(), unit: z.string(),
        samples: z.array(z.object({ labels: z.record(z.string(), z.string()), value: z.number().nullable(), at: z.string() })),
      },
      annotations: READ,
      uses: ['instant'],
      handler: async (args) => {
        if (!Object.hasOwn(METRICS, args.metric)) throw invalidInput(`Unknown metric "${args.metric.slice(0, 40)}". Choose from: ${METRIC_NAMES.join(', ')}.`);
        const name = args.metric as MetricName;
        const def = METRICS[name];
        const samples = await deps.prometheus.instant(name);
        return {
          text: samples.length === 0 ? `${name}: no data (Prometheus has no samples for it).`
            : `${name} (${def.unit}): ${samples.map((s) => `${s.value ?? 'NaN'} at ${s.at}`).join('; ')}. ${def.description}.`,
          data: { metric: name, description: def.description, unit: def.unit, samples },
        };
      },
    }),

    defineTool({
      name: 'alert_state',
      title: 'Alerts',
      description: 'Alerts Prometheus is evaluating: those firing now, and optionally those pending. Each has its name, labels, severity and the time it became active.',
      input: { includePending: z.boolean().optional().describe('also list alerts that are pending (default: firing only)') },
      output: {
        firing: z.array(z.object({ name: z.string(), severity: z.string().nullable(), labels: z.record(z.string(), z.string()), summary: z.string().nullable(), activeSince: z.string() })),
        pending: z.array(z.object({ name: z.string(), severity: z.string().nullable(), labels: z.record(z.string(), z.string()), summary: z.string().nullable(), activeSince: z.string() })),
      },
      annotations: READ,
      uses: ['alerts'],
      handler: async (args) => {
        const all = await deps.prometheus.alerts();
        const firing = all.filter((a) => a.state === 'firing');
        const pending = args.includePending ? all.filter((a) => a.state === 'pending') : [];
        const line = (a: (typeof all)[number]) => `${a.name} [${a.severity ?? 'no severity'}] since ${a.activeSince}${a.summary ? `: ${a.summary}` : ''}`;
        return {
          text: [firing.length ? `Firing (${firing.length}):\n${firing.map(line).join('\n')}` : 'No alert is firing.',
            ...(pending.length ? [`Pending (${pending.length}):\n${pending.map(line).join('\n')}`] : [])].join('\n'),
          data: { firing, pending },
        };
      },
    }),

    defineTool({
      name: 'wake',
      title: 'Wake the VM',
      description: 'Starts the VM if it is asleep, by calling the configured public wake URL (the same thing the wake page does). Idempotent: it '
        + 'reports whether the VM was already running, was already starting, or was started by this call, and calls the start only from a '
        + 'stopped or deallocated VM. Starting costs money while it runs: call it only when asked. It returns at once; poll `health`.',
      input: {},
      output: {
        outcome: z.enum(['already-running', 'already-starting', 'started-by-this-call', 'going-to-sleep']),
        powerStateBefore: z.string(),
        applicationAnswers: z.boolean(),
        hint: z.string(),
      },
      annotations: START,
      uses: ['status', 'wake'],
      handler: async () => {
        if (!wake) throw invalidInput('DEPLOY_OPS_WAKE_URL is not set, so there is nothing to call.');
        const before = await wake.status();
        if (before.state === 'running') {
          return { text: `${before.appUp ? 'Already running, and the application answers.' : 'Already running; the application does not answer yet (it may still be booting).'} ${before.appUp ? 'Nothing to do.' : 'Call \`health\` in a minute.'}`,
            data: { outcome: 'already-running' as const, powerStateBefore: before.state, applicationAnswers: before.appUp,
              hint: before.appUp ? 'Nothing to do.' : 'Call `health` in a minute.' } };
        }
        if (before.state === 'starting') {
          return { text: 'Already starting; nothing was called. Poll `health`; it is usually up in about two minutes.', data: { outcome: 'already-starting' as const, powerStateBefore: before.state, applicationAnswers: false, hint: 'Poll `health`; it is usually up in about two minutes.' } };
        }
        if (!DOWN.has(before.state)) {
          return { text: `The VM is ${before.state}; a start is not requested while it is going down. Wait a minute, then call \`wake\` again.`,
            data: { outcome: 'going-to-sleep' as const, powerStateBefore: before.state, applicationAnswers: false, hint: 'Wait a minute, then call `wake` again.' } };
        }
        await wake.wake();
        return { text: `The VM was ${before.state}; the start was requested once. It is starting. Poll \`health\`; it is usually up in about two minutes.`,
          data: { outcome: 'started-by-this-call' as const, powerStateBefore: before.state, applicationAnswers: false, hint: 'Poll `health`; it is usually up in about two minutes.' } };
      },
    }),
  ];
}
