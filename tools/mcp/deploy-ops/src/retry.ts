import { ToolError } from '@arthadhruva/mcp-kit';

/**
 * Every network call here has a short timeout and at most a fixed number of retries with doubling backoff, so the
 * longest a tool can take is known and stated: {@link maxTotalMs}. A retry is for something that may pass (no answer,
 * a timeout, a 5xx); an answer that will not change on a second asking (a 4xx) is returned at once.
 */
export interface RetryPolicy {
  timeoutMs: number;
  /** Retries after the first attempt, so attempts = retries + 1. */
  retries: number;
  backoffMs: number;
}

export const MAX_RETRIES = 3;

/** The longest a call under this policy can take: every attempt times out, with the backoff between them. */
export function maxTotalMs(p: RetryPolicy): number {
  let backoff = 0;
  for (let i = 0; i < p.retries; i++) backoff += p.backoffMs * 2 ** i;
  return (p.retries + 1) * p.timeoutMs + backoff;
}

export type Fetch = typeof fetch;
const sleep = (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms));

/** What one attempt reports: done with a value, or "try again" with the reason to give if it was the last. */
export type Attempt<T> = { done: T } | { retry: ToolError };

export async function withRetries<T>(policy: RetryPolicy, attempt: () => Promise<Attempt<T>>, signal?: AbortSignal): Promise<T> {
  let last: ToolError | undefined;
  for (let i = 0; i <= policy.retries; i++) {
    if (signal?.aborted) throw new ToolError('Timeout', 'Cancelled.');
    const result = await attempt();
    if ('done' in result) return result.done;
    last = result.retry;
    if (i < policy.retries) await sleep(policy.backoffMs * 2 ** i);
  }
  throw last ?? new ToolError('UpstreamDown', 'The service did not answer.');
}

/** One HTTP request as an attempt: a network error, timeout or 5xx is retryable; anything else is the answer. */
export async function httpAttempt(
  fetchImpl: Fetch, url: string, init: RequestInit, timeoutMs: number, what: string,
): Promise<Attempt<Response>> {
  try {
    const res = await fetchImpl(url, { ...init, signal: AbortSignal.timeout(timeoutMs), redirect: 'error' });
    if (res.status >= 500) {
      await res.body?.cancel();
      return { retry: new ToolError('UpstreamDown', `${what} answered HTTP ${res.status}.`) };
    }
    return { done: res };
  } catch (e) {
    const name = (e as Error).name;
    return {
      retry: name === 'TimeoutError' || name === 'AbortError'
        ? new ToolError('Timeout', `${what} did not answer within ${Math.round(timeoutMs / 1000)} s.`)
        : new ToolError('UpstreamDown', `${what} is not reachable.`),
    };
  }
}
