import { ToolError, retryAfterSeconds, type Redactor } from '@arthadhruva/mcp-kit';

/**
 * The two administrator endpoints this server reads, /admin/audit-log and /admin/usage, through the API and not the
 * tables: the API is what applies the role check and the tenant, and what the application audits. GET only, so no
 * CSRF token is needed; the session cookie is the one the console uses (the value of `ad_session` for an
 * ADMINISTRATOR of the organization, since /admin/** is administrator-only). A session the API rotates is taken
 * and registered with the redactor; nothing here writes a cookie to a log, a file or a result.
 */
export interface ApiConfig {
  baseUrl: string;
  session: string;
  timeoutMs: number;
}

export type Fetch = typeof fetch;

export const SESSION_COOKIE = 'ad_session';

export class AdminApi {
  private session: string;

  constructor(private readonly config: ApiConfig, private readonly redactor: Redactor, private readonly fetchImpl: Fetch = fetch) {
    this.session = config.session;
  }

  async get<T>(path: string, query: Record<string, string | number | undefined> = {}): Promise<T> {
    const params = new URLSearchParams();
    for (const [k, v] of Object.entries(query)) if (v !== undefined) params.set(k, String(v));
    const qs = params.toString();
    let res: Response;
    try {
      res = await this.fetchImpl(`${this.config.baseUrl}/v1${path}${qs ? `?${qs}` : ''}`, {
        method: 'GET',
        headers: { Accept: 'application/json', Cookie: `${SESSION_COOKIE}=${this.session}` },
        signal: AbortSignal.timeout(this.config.timeoutMs),
        redirect: 'error',
      });
    } catch (e) {
      const name = (e as Error).name;
      if (name === 'TimeoutError' || name === 'AbortError') {
        throw new ToolError('Timeout', `The API did not answer within ${Math.round(this.config.timeoutMs / 1000)} s.`);
      }
      throw new ToolError('UpstreamDown', 'The API is not reachable. Is the backend running?');
    }
    this.absorbSession(res);
    const text = await res.text();
    if (res.ok) {
      try {
        return (text ? JSON.parse(text) : {}) as T;
      } catch {
        throw new ToolError('UpstreamDown', 'The API answered with something that is not JSON.');
      }
    }
    throw this.error(res, text);
  }

  private error(res: Response, text: string): ToolError {
    let message = res.statusText || `HTTP ${res.status}`;
    try {
      const parsed = JSON.parse(text) as Record<string, unknown>;
      message = String(parsed.error ?? parsed.message ?? message);
    } catch {
      // not JSON: keep the status text
    }
    const retry = retryAfterSeconds(res.headers.get('Retry-After'));
    const withRetry = retry === undefined ? {} : { retryAfterSec: retry };
    switch (res.status) {
      case 401:
        return new ToolError('Forbidden', 'session expired, refresh the session cookie');
      case 403:
        return new ToolError('Forbidden', 'This account is not allowed to read that: /admin endpoints need an administrator of the organization.');
      case 404:
        return new ToolError('NotFound', 'Not found.');
      case 429:
        return new ToolError('RateLimited', 'The API is rate limiting this caller.', withRetry);
      default:
        return new ToolError('UpstreamDown', res.status === 503 ? `The API is busy: ${message}` : `The API failed (HTTP ${res.status}).`, withRetry);
    }
  }

  private absorbSession(res: Response): void {
    const cookies = typeof res.headers.getSetCookie === 'function' ? res.headers.getSetCookie() : [];
    for (const cookie of cookies) {
      const [pair] = cookie.split(';');
      const eq = pair?.indexOf('=') ?? -1;
      if (!pair || eq < 0 || pair.slice(0, eq).trim() !== SESSION_COOKIE) continue;
      const value = pair.slice(eq + 1).trim();
      if (value) {
        this.session = value;
        this.redactor.add(value);
      }
    }
  }
}
