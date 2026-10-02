import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError, getLoan, getPortfolioRun, listModels, listScenarios, regimeForecast, score, startPortfolioRun, unreadNotificationCount } from './client';
import { DEFAULT_LOAN } from '../components/loanDefaults';

const STORAGE_KEY = 'arthadhruva-auth';
const MINUTE = 60_000;

/** A stored session's metadata, as if obtained `ageMs` ago and expiring `expiresInMs` from now. The
 * token itself is never in here -- it lives only in the (mocked, invisible-to-this-test) ad_session
 * cookie, so "is the client still treating itself as signed in" is observable only through this
 * metadata and through whether a request carries credentials:'include'. */
function signIn(ageMs: number, expiresInMs = 10 * MINUTE) {
  localStorage.setItem(STORAGE_KEY, JSON.stringify({
    username: 'ana',
    role: 'ANALYST',
    expiresAt: new Date(Date.now() + expiresInMs).toISOString(),
    sessionExpiresAt: new Date(Date.now() + 8 * 60 * MINUTE).toISOString(),
    obtainedAt: Date.now() - ageMs,
  }));
}

function json(body: unknown, status = 200, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json', ...headers } });
}

const fresh = { username: 'ana', role: 'ANALYST', expiresAt: new Date(Date.now() + 15 * MINUTE).toISOString(), sessionExpiresAt: new Date(Date.now() + 8 * 60 * MINUTE).toISOString() };

let fetchMock: ReturnType<typeof vi.fn<(url: string, init?: RequestInit) => Promise<Response>>>;
const calls = () => fetchMock.mock.calls.map(([url, init]) => ({ url: String(url), credentials: init?.credentials }));
const refreshes = () => calls().filter((c) => c.url.endsWith('/account/session/refresh'));
const storedExpiresAt = () => (JSON.parse(localStorage.getItem(STORAGE_KEY) ?? 'null') as { expiresAt?: string } | null)?.expiresAt;

beforeEach(() => {
  localStorage.clear();
  fetchMock = vi.fn(async (url: string) => (String(url).endsWith('/account/session/refresh') ? json(fresh) : json({})));
  vi.stubGlobal('fetch', fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('the sliding session', () => {
  it('leaves a session that is still fresh alone', async () => {
    signIn(5_000);
    const before = storedExpiresAt();
    await regimeForecast(6);
    expect(refreshes()).toHaveLength(0);
    expect(calls()[0].credentials).toBe('include');
    expect(storedExpiresAt()).toBe(before);
  });

  it('refreshes an aged session once, however many requests notice at the same time', async () => {
    signIn(2 * MINUTE);
    const before = storedExpiresAt();
    await Promise.all([regimeForecast(6), listScenarios(), listModels()]);
    expect(refreshes()).toHaveLength(1);
    const requests = calls().filter((c) => !c.url.endsWith('/account/session/refresh'));
    expect(requests).toHaveLength(3);
    expect(requests.every((c) => c.credentials === 'include')).toBe(true);
    // storeAuth was called with the refreshed metadata: the stored expiry moved to the mocked response's.
    expect(storedExpiresAt()).toBe(fresh.expiresAt);
    expect(storedExpiresAt()).not.toBe(before);
  });

  it('is not extended by polling the user did not ask for', async () => {
    signIn(2 * MINUTE);
    const before = storedExpiresAt();
    fetchMock.mockImplementation(async (url: string) => json(String(url).includes('unread-count') ? { count: 3 } : { id: 'r1', status: 'RUNNING' }));
    await getPortfolioRun('r1');
    expect(await unreadNotificationCount()).toBe(3);
    expect(refreshes()).toHaveLength(0);
    expect(storedExpiresAt()).toBe(before);
  });

  it('does not try to refresh a session that has already expired', async () => {
    signIn(20 * MINUTE, -MINUTE);
    vi.stubGlobal('location', { href: '', origin: 'http://localhost' });
    fetchMock.mockImplementation(async () => json({ error: 'Unauthorized' }, 401));
    await expect(regimeForecast(6)).rejects.toMatchObject({ status: 401 });
    expect(refreshes()).toHaveLength(0);
    expect(localStorage.getItem(STORAGE_KEY)).toBeNull();
    expect(window.location.href).toBe('/login');
  });

  it('keeps using the existing session when the refresh itself fails', async () => {
    signIn(2 * MINUTE);
    const before = storedExpiresAt();
    fetchMock.mockImplementation(async (url: string) => {
      if (String(url).endsWith('/account/session/refresh')) throw new TypeError('network down');
      return json({});
    });
    await listModels();
    expect(calls().at(-1)!.credentials).toBe('include');
    expect(storedExpiresAt()).toBe(before); // the failed refresh never called storeAuth
  });
});

describe('errors', () => {
  beforeEach(() => signIn(5_000));

  it('names the fields a validation error is about', async () => {
    fetchMock.mockImplementation(async () => json({ error: 'Validation failed', fields: { creditScore: 'must be at least 300' } }, 400));
    const error = await score(DEFAULT_LOAN).catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).message).toBe('creditScore: must be at least 300');
    expect((error as ApiError).fields).toEqual({ creditScore: 'must be at least 300' });
  });

  it('says when to retry a rate-limited request', async () => {
    fetchMock.mockImplementation(async () => new Response('', { status: 429, headers: { 'Retry-After': '7' } }));
    await expect(score(DEFAULT_LOAN)).rejects.toMatchObject({ status: 429, message: 'Too many requests. Try again in 7s.' });
  });

  it('answers a second start with the run already in progress, not an error', async () => {
    const active = { id: 'r9', status: 'RUNNING', scenarios: ['BASELINE'], loansTotal: 400, loansDone: 120 };
    fetchMock.mockImplementation(async () => json(active, 409));
    expect(await startPortfolioRun()).toEqual(active);
  });

  it('reads a loan that does not exist as null, not as a failure', async () => {
    fetchMock.mockImplementation(async () => json({ error: 'Not found' }, 404));
    expect(await getLoan('NOPE')).toBeNull();
  });
});
