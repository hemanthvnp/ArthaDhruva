/**
 * What the deployed stack's condition is, as a state and not an alarm. The VM that runs ArthaDhruva is deliberately
 * deallocated while nobody uses it, so "the application does not answer" is, much of the time, the system working as
 * designed. A health report therefore never calls an asleep VM unhealthy: `asleep` is a normal state with a hint, and
 * `normal` says whether anyone needs to do anything.
 */
export type HealthState = 'healthy' | 'degraded' | 'asleep' | 'starting' | 'going-to-sleep' | 'unhealthy' | 'unknown';

export interface Assessment {
  state: HealthState;
  /** False only when something is wrong that a person should look at. */
  normal: boolean;
  hint: string;
}

export interface Evidence {
  /** The VM's power state, or null when no source could say. */
  power: string | null;
  /** Whether the application's API answered; null when no application URL is configured. */
  appAnswers: boolean | null;
  /** Prometheus's view of the backend (min of up); null when unknown or not asked. */
  backendUp: boolean | null;
  /** Alerts firing right now, by severity; null when Prometheus could not be asked. */
  firing: { critical: number; other: number } | null;
}

const ASLEEP = new Set(['deallocated', 'stopped']);

export function assess(e: Evidence): Assessment {
  if (e.power && ASLEEP.has(e.power)) {
    return { state: 'asleep', normal: true, hint: 'The VM is switched off to save cost, which is its normal idle state. `wake` starts it; it is usually up in about two minutes.' };
  }
  if (e.power === 'deallocating' || e.power === 'stopping') {
    return { state: 'going-to-sleep', normal: true, hint: 'The VM is switching itself off after idling. If it is needed, wait a minute and call `wake`.' };
  }
  if (e.power === 'starting') {
    return { state: 'starting', normal: true, hint: 'The VM is starting. The application usually answers about two minutes after the start; call `health` again.' };
  }
  if (e.power === 'running' || e.power === null) {
    if (e.appAnswers === true) {
      if (e.backendUp === false) return { state: 'unhealthy', normal: false, hint: 'The application answers but Prometheus cannot scrape the backend: the monitoring path or the backend is broken.' };
      if (e.firing && (e.firing.critical > 0 || e.firing.other > 0)) {
        return { state: 'degraded', normal: false, hint: `${e.firing.critical} critical and ${e.firing.other} other alert(s) are firing; call \`alert_state\` for which.` };
      }
      return { state: 'healthy', normal: true, hint: 'The application answers and no alert is firing.' };
    }
    if (e.power === 'running') {
      return { state: 'unhealthy', normal: false, hint: 'The VM is running but the application does not answer. If it started within the last few minutes it may still be booting; otherwise look at the containers.' };
    }
    if (e.appAnswers === false) {
      return { state: 'unknown', normal: true, hint: 'The application does not answer and no power state is available. If the VM is deallocated that is normal; configure the wake URL or the az target so this can be told apart from a failure.' };
    }
    return { state: 'unknown', normal: true, hint: 'No application URL or power-state source is configured, so there is nothing to assess.' };
  }
  return { state: 'unknown', normal: true, hint: `The VM reports an unrecognised power state "${e.power}".` };
}
