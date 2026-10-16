import { createHash, randomUUID } from 'node:crypto';
import { ToolError, retryAfterSeconds, type Redactor } from '@arthadhruva/mcp-kit';

/**
 * The only door to the risk API. It authenticates the way the console does, with the session cookie (the API
 * accepts nothing else for these endpoints) and, on writes, the double-submit CSRF pair: the `XSRF-TOKEN`
 * cookie echoed in `X-XSRF-TOKEN`. GETs need no CSRF header. Neither value is ever written to a log, a file or
 * a result: both are registered with the redactor, including a session the API rotates mid-session.
 *
 * Errors become the kit's typed errors: 401 is an expired session (never retried), 429 is RateLimited with
 * the server's Retry-After, 402 is QuotaExceeded, 503 is UpstreamDown with its Retry-After, 409 is Conflict
 * carrying the response body. Nothing is retried here: a limit is reported, not waited out.
 */
export interface ApiConfig {
  baseUrl: string;
  session: string;
  /** Optional: when absent the adapter obtains one from the API, as the console does. */
  csrf?: string;
  timeoutMs: number;
}

export type Fetch = typeof fetch;

export const SESSION_COOKIE = 'ad_session';
export const CSRF_COOKIE = 'XSRF-TOKEN';
export const CSRF_HEADER = 'X-XSRF-TOKEN';
export const SESSION_EXPIRED = 'session expired, refresh the session cookie';

export class RiskApi {
  private session: string;
  private csrf: string | undefined;
  /** Idempotency keys of writes that failed without an answer (timeout, unreachable): a repeat reuses the key. */
  private readonly pendingKeys = new Map<string, string>();

  constructor(private readonly config: ApiConfig, private readonly redactor: Redactor, private readonly fetchImpl: Fetch = fetch) {
    this.session = config.session;
    this.csrf = config.csrf;
  }

  get<T>(path: string, query: Record<string, string | number | undefined> = {}): Promise<T> {
    const params = new URLSearchParams();
    for (const [k, v] of Object.entries(query)) if (v !== undefined) params.set(k, String(v));
    const qs = params.toString();
    return this.send<T>('GET', qs ? `${path}?${qs}` : path);
  }

  /**
   * A write. `logicalCall` identifies the tool call (tool name and arguments): when the same call failed last
   * time without a response, its idempotency key is reused, so the API runs it at most once.
   */
  async post<T>(path: string, body: unknown, logicalCall: string): Promise<{ status: number; data: T }> {
    const fingerprint = createHash('sha256').update(logicalCall).digest('hex');
    const key = this.pendingKeys.get(fingerprint) ?? randomUUID();
    this.pendingKeys.set(fingerprint, key);
    try {
      const result = await this.sendWithStatus<T>('POST', path, body, key);
      this.pendingKeys.delete(fingerprint);
      return result;
    } catch (e) {
      // Only an unanswered request keeps its key; an answer (even an error) means the API decided.
      if (!(e instanceof ToolError && (e.code === 'Timeout' || (e.code === 'UpstreamDown' && e.details?.answered !== true)))) {
        this.pendingKeys.delete(fingerprint);
      }
      throw e;
    }
  }

  /** Starts a portfolio run: the one call that consumes allowance and starts work, so it is marked mutating. */
  startRun<T>(body: unknown, logicalCall: string): Promise<{ status: number; data: T }> {
    return this.post<T>('/risk/portfolio/runs', body, logicalCall);
  }

  private async send<T>(method: 'GET', path: string): Promise<T> {
    return (await this.sendWithStatus<T>(method, path)).data;
  }

  private async sendWithStatus<T>(method: 'GET' | 'POST', path: string, body?: unknown, idempotencyKey?: string): Promise<{ status: number; data: T }> {
    if (method === 'POST' && !this.csrf) await this.obtainCsrf();
    const headers: Record<string, string> = { Accept: 'application/json', Cookie: this.cookieHeader() };
    if (method === 'POST') {
      headers['Content-Type'] = 'application/json';
      headers[CSRF_HEADER] = this.csrf!;
      if (idempotencyKey) headers['Idempotency-Key'] = idempotencyKey;
    }
    let res: Response;
    try {
      res = await this.fetchImpl(`${this.config.baseUrl}/v1${path}`, {
        method,
        headers,
        ...(body === undefined ? {} : { body: JSON.stringify(body) }),
        signal: AbortSignal.timeout(this.config.timeoutMs),
        redirect: 'error',
      });
    } catch (e) {
      const name = (e as Error).name;
      if (name === 'TimeoutError' || name === 'AbortError') {
        throw new ToolError('Timeout', `The risk API did not answer within ${Math.round(this.config.timeoutMs / 1000)} s.`);
      }
      throw new ToolError('UpstreamDown', 'The risk API is not reachable. Is the backend running?');
    }
    this.absorbCookies(res);
    const text = await res.text();
    if (res.ok) return { status: res.status, data: (text ? JSON.parse(text) : {}) as T };
    throw this.error(res, text);
  }

  private error(res: Response, text: string): ToolError {
    let parsed: unknown;
    try {
      parsed = text ? JSON.parse(text) : undefined;
    } catch {
      parsed = undefined;
    }
    const message = typeof parsed === 'object' && parsed !== null
      ? String((parsed as Record<string, unknown>).error ?? (parsed as Record<string, unknown>).message ?? res.statusText)
      : res.statusText || `HTTP ${res.status}`;
    const retry = retryAfterSeconds(res.headers.get('Retry-After'));
    const withRetry = retry === undefined ? {} : { retryAfterSec: retry };
    switch (res.status) {
      case 400:
      case 422:
        return new ToolError('InvalidInput', `The risk API refused the request: ${message}`);
      case 401:
        return new ToolError('Forbidden', SESSION_EXPIRED);
      case 402:
        return new ToolError('QuotaExceeded', `The organization's plan allowance is used up: ${message}`, withRetry);
      case 403:
        return new ToolError('Forbidden', 'This account is not allowed to do that (a role or plan restriction).');
      case 404:
        return new ToolError('NotFound', message === 'Not Found' || !message ? 'Not found.' : message);
      case 409:
        return new ToolError('Conflict', message, { details: typeof parsed === 'object' && parsed !== null ? (parsed as Record<string, unknown>) : {} });
      case 429:
        return /quota|allowance/i.test(message)
          ? new ToolError('QuotaExceeded', `The organization's plan allowance is used up: ${message}`, withRetry)
          : new ToolError('RateLimited', 'The risk API is rate limiting this caller.', withRetry);
      default:
        return new ToolError('UpstreamDown', res.status === 503 ? `The risk API is busy: ${message}` : `The risk API failed (HTTP ${res.status}).`,
          { ...withRetry, details: { answered: true } });
    }
  }

  private cookieHeader(): string {
    return [`${SESSION_COOKIE}=${this.session}`, ...(this.csrf ? [`${CSRF_COOKIE}=${this.csrf}`] : [])].join('; ');
  }

  /** Takes a rotated session or a new CSRF token from the response, in memory only. */
  private absorbCookies(res: Response): void {
    const cookies = typeof res.headers.getSetCookie === 'function' ? res.headers.getSetCookie() : [];
    for (const cookie of cookies) {
      const [pair] = cookie.split(';');
      const eq = pair?.indexOf('=') ?? -1;
      if (!pair || eq < 0) continue;
      const name = pair.slice(0, eq).trim();
      const value = pair.slice(eq + 1).trim();
      if (!value) continue;
      if (name === SESSION_COOKIE) {
        this.session = value;
        this.redactor.add(value);
      } else if (name === CSRF_COOKIE) {
        this.csrf = value;
        this.redactor.add(value);
      }
    }
  }

  /** The API attaches the CSRF cookie to any response, even a 401; GET /v1/csrf is the path the console uses. */
  private async obtainCsrf(): Promise<void> {
    try {
      const res = await this.fetchImpl(`${this.config.baseUrl}/v1/csrf`, {
        headers: { Cookie: this.cookieHeader() }, signal: AbortSignal.timeout(this.config.timeoutMs), redirect: 'error',
      });
      this.absorbCookies(res);
      await res.body?.cancel();
    } catch {
      throw new ToolError('UpstreamDown', 'The risk API is not reachable. Is the backend running?');
    }
    if (!this.csrf) throw new ToolError('UpstreamDown', 'The risk API did not issue a CSRF token.');
  }
}
