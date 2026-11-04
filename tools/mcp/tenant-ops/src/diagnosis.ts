/**
 * Whether a portfolio run's worker appears to be dead. The threshold is the application's own, from
 * PortfolioRiskService: a RUNNING run whose heartbeat has not been written for two minutes is put back in the queue
 * by the leading replica, under the next attempt, up to three attempts, after which it is failed. The age is
 * measured by the database's clock (the one that wrote the heartbeat), so a skewed clock here changes nothing.
 */
export const STALE_AFTER_SEC = 120;
export const MAX_ATTEMPTS = 3;

export interface RunFacts {
  status: string;
  attempt: number;
  deferred: boolean;
  loansTotal: number;
  loansDone: number;
  /** Seconds since the last heartbeat, by the database's clock. */
  heartbeatAgeSec: number;
}

export interface Diagnosis {
  workerAppearsDead: boolean;
  summary: string;
}

export function diagnose(run: RunFacts): Diagnosis {
  const progress = `${run.loansDone} of ${run.loansTotal} loans`;
  switch (run.status) {
    case 'RUNNING': {
      if (run.heartbeatAgeSec > STALE_AFTER_SEC) {
        const next = run.attempt >= MAX_ATTEMPTS
          ? `it has used its ${MAX_ATTEMPTS} attempts, so the leading replica will fail it instead of restarting it`
          : `the leading replica puts it back in the queue as attempt ${run.attempt + 1} of ${MAX_ATTEMPTS}`;
        return {
          workerAppearsDead: true,
          summary: `Worker appears dead: no heartbeat for ${Math.round(run.heartbeatAgeSec)} s (threshold ${STALE_AFTER_SEC} s) at ${progress}; ${next}.`,
        };
      }
      return { workerAppearsDead: false, summary: `Running, attempt ${run.attempt}; last heartbeat ${Math.round(run.heartbeatAgeSec)} s ago; ${progress}.` };
    }
    case 'QUEUED':
      return {
        workerAppearsDead: false,
        summary: run.deferred
          ? 'Queued as deferred work: it starts when a replica is idle or in the off-peak window, never ahead of an interactive run.'
          : 'Queued: it starts when a replica has a free worker.',
      };
    case 'COMPLETED':
      return { workerAppearsDead: false, summary: `Completed (${progress}).` };
    case 'FAILED':
      return { workerAppearsDead: false, summary: `Failed at ${progress}, attempt ${run.attempt}.` };
    default:
      return { workerAppearsDead: false, summary: `Status ${run.status}.` };
  }
}
