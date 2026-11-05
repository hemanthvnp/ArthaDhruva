import { ToolError } from '@arthadhruva/mcp-kit';
import { httpAttempt, withRetries, type Fetch, type RetryPolicy } from './retry.js';

/**
 * The wake function (deploy/azure/wake) and the application, the only two places this server speaks to besides
 * Prometheus. The wake URL is configuration, and only its two fixed paths are ever requested:
 *
 *  - `POST /status` reads the VM's power state and starts nothing, so it is safe to ask as often as needed;
 *  - `POST /wake` starts the VM if it is down, and is idempotent (a start is requested only from `deallocated` or
 *    `stopped`; from anywhere else it just reports). It is the one action this server has, and `wake` is the only
 *    method marked mutating.
 *
 * Both need the `X-Wake: 1` header, which is how the function tells its own page from a crawler.
 */
export interface WakeAnswer {
  state: string;
  appUp: boolean;
}

export class WakeClient {
  constructor(private readonly baseUrl: string, private readonly policy: RetryPolicy, private readonly fetchImpl: Fetch = fetch) {}

  status(): Promise<WakeAnswer> {
    return this.post('/status');
  }

  wake(): Promise<WakeAnswer> {
    return this.post('/wake');
  }

  private async post(path: '/status' | '/wake'): Promise<WakeAnswer> {
    const res = await withRetries(this.policy, () => httpAttempt(
      this.fetchImpl, `${this.baseUrl}${path}`, { method: 'POST', headers: { 'X-Wake': '1', Accept: 'application/json' } }, this.policy.timeoutMs, 'The wake function'));
    const text = await res.text();
    if (!res.ok) throw new ToolError('UpstreamDown', `The wake function refused ${path} (HTTP ${res.status}).`);
    try {
      const body = JSON.parse(text) as Partial<WakeAnswer>;
      if (typeof body.state !== 'string') throw new Error('no state');
      return { state: body.state, appUp: body.appUp === true };
    } catch {
      throw new ToolError('UpstreamDown', 'The wake function answered something unreadable.');
    }
  }
}

/**
 * Whether the application answers. It is up when its API answers: 401 without a token is the normal answer from a
 * healthy one, as the wake function itself counts it. Any other answer, or none, is "not answering" and says nothing
 * about why (a VM that is asleep does not answer, which is normal).
 */
export async function appAnswers(baseUrl: string, policy: RetryPolicy, fetchImpl: Fetch = fetch): Promise<boolean> {
  try {
    const res = await fetchImpl(`${baseUrl}/v1/loans`, {
      headers: { 'User-Agent': 'arthadhruva-deploy-ops-mcp' }, signal: AbortSignal.timeout(policy.timeoutMs), redirect: 'manual',
    });
    await res.body?.cancel();
    return res.status === 401 || res.status === 200;
  } catch {
    return false;
  }
}
