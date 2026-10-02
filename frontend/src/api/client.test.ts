import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError, getLoan, getPortfolioRun, listModels, listScenarios, regimeForecast, score, startPortfolioRun, unreadNotificationCount } from './client';
import { DEFAULT_LOAN } from '../components/loanDefaults';

const STORAGE_KEY = 'arthadhruva-auth';
const MINUTE = 60_000;

/** A stored session whose token was obtained `ageMs` ago and expires `expiresInMs` from now. */
function signIn(ageMs: number, expiresInMs = 10 * MINUTE) {
  localStorage.setItem(STORAGE_KEY, JSON.stringify({
    token: 'old',
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

const fresh = { token: 'new', username: 'ana', role: 'ANALYST', expiresAt: new Date(Date.now() + 15 * MINUTE).toISOString(), sessionExpiresAt: new Date(Date.now() + 8 * 60 * MINUTE).toISOString() };

let fetchMock: ReturnType<typeof vi.fn<(url: string, init?: RequestInit) => Promise<Response>>>;
const calls = () => fetchMock.mock.calls.map(([url, init]) => ({ url: String(url), auth: (init?.headers as Record<string, string> | undefined)?.Authorization }));
const refreshes = () => calls().filter((c) => c.url.endsWith('/account/session/refresh'));

beforeEach(() => {
  localStorage.clear();
  fetchMock = vi.fn(async (url: string) => (String(url).endsWith('/account/session/refresh') ? json(fresh) : json({})));
  vi.stubGlobal('fetch', fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('the sliding session', () => {
  it('uses a token that is still fresh as it is', async () => {
    signIn(5_000);
    await regimeForecast(6);
    expect(refreshes()).toHaveLength(0);
    expect(calls()[0].auth).toBe('Bearer old');
  });

  it('exchanges an aged token once, however many requests notice at the same time', async () => {
    signIn(2 * MINUTE);
    await Promise.all([regimeForecast(6), listScenarios(), listModels()]);
    expect(refreshes()).toHaveLength(1);
    expect(refreshes()[0].auth).toBe('Bearer old');
    const requests = calls().filter((c) => !c.url.endsWith('/account/session/refresh'));
    expect(requests).toHaveLength(3);
    expect(requests.every((c) => c.auth === 'Bearer new')).toBe(true);
    expect(JSON.parse(localStorage.getItem(STORAGE_KEY)!).token).toBe('new');
  });

  it('is not extended by polling the user did not ask for', async () => {
    signIn(2 * MINUTE);
    fetchMock.mockImplementation(async (url: string) => json(String(url).includes('unread-count') ? { count: 3 } : { id: 'r1', status: 'RUNNING' }));
    await getPortfolioRun('r1');
    expect(await unreadNotificationCount()).toBe(3);
    expect(refreshes()).toHaveLength(0);
    expect(calls().every((c) => c.auth === 'Bearer old')).toBe(true);
  });

  it('does not try to refresh a token that has already expired', async () => {
    signIn(20 * MINUTE, -MINUTE);
    vi.stubGlobal('location', { href: '', origin: 'http://localhost' });
    fetchMock.mockImplementation(async () => json({ error: 'Unauthorized' }, 401));
    await expect(regimeForecast(6)).rejects.toMatchObject({ status: 401 });
    expect(refreshes()).toHaveLength(0);
    expect(localStorage.getItem(STORAGE_KEY)).toBeNull();
    expect(window.location.href).toBe('/login');
  });

  it('carries on with the old token when the refresh itself fails', async () => {
    signIn(2 * MINUTE);
    fetchMock.mockImplementation(async (url: string) => {
      if (String(url).endsWith('/account/session/refresh')) throw new TypeError('network down');
      return json({});
    });
    await listModels();
    expect(calls().at(-1)!.auth).toBe('Bearer old');
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
