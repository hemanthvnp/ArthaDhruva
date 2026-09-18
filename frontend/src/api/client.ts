import { clearStoredAuth, getStoredToken } from '../auth/AuthContext';
import type {
  AuditLogEntry,
  CachedScore,
  CreateUserRequest,
  CreateUserResponse,
  CvarRequest,
  CvarResult,
  ExpectedLossResponse,
  LoanFeatures,
  LoginAttemptEntry,
  LoginOutcome,
  LoginResponse,
  MessageResponse,
  MyLoanView,
  RegimeForecast,
  ScoreResponse,
  SegmentNeighbor,
  TotpConfirmOutcome,
  TotpSetupResponse,
  TotpStatusResponse,
  TrajectoryRequest,
  TrajectoryScoreResponse,
  UserStatusResponse,
  UserSummary,
} from './types';

const BASE_URL = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080';

export class ApiError extends Error {
  status: number;
  fields?: Record<string, string>;

  constructor(status: number, message: string, fields?: Record<string, string>) {
    super(message);
    this.status = status;
    this.fields = fields;
  }
}

function authorizedFetch(path: string, options?: RequestInit): Promise<Response> {
  const token = getStoredToken();
  const headers: Record<string, string> = { 'Content-Type': 'application/json' };
  if (token) headers.Authorization = `Bearer ${token}`;
  return fetch(`${BASE_URL}${path}`, { ...options, headers: { ...headers, ...options?.headers } });
}

async function request<T>(path: string, options?: RequestInit): Promise<T> {
  const res = await authorizedFetch(path, options);

  if (res.status === 401 && path !== '/login') {
    clearStoredAuth();
    window.location.href = '/login';
    throw new ApiError(401, 'Session expired -- please log in again.');
  }

  if (!res.ok) {
    let message = `Request failed: ${res.status}`;
    let fields: Record<string, string> | undefined;
    try {
      const body = await res.json();
      if (body.fields) {
        fields = body.fields;
        message = Object.entries(body.fields as Record<string, string>)
          .map(([field, msg]) => `${field}: ${msg}`)
          .join(', ');
      } else if (body.error) {
        message = body.error;
      }
    } catch {
      // response body wasn't JSON -- keep the generic message
    }
    throw new ApiError(res.status, message, fields);
  }

  return res.json() as Promise<T>;
}

/** Bypasses stored auth entirely -- used only for the narrow setup-token flow (Setup2faPage),
 * where the caller isn't fully logged in yet and a 401 (e.g. a wrong confirmation code) must
 * just show an error, not trigger the normal "session expired -> redirect to /login" handling
 * that `request()` applies to every other endpoint. */
async function setupRequest<T>(token: string, path: string, options?: RequestInit): Promise<T> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` };
  const res = await fetch(`${BASE_URL}${path}`, { ...options, headers: { ...headers, ...options?.headers } });
  if (!res.ok) {
    let message = `Request failed: ${res.status}`;
    try {
      const body = await res.json();
      if (body.error) message = body.error;
    } catch {
      // response body wasn't JSON -- keep the generic message
    }
    throw new ApiError(res.status, message);
  }
  return res.json() as Promise<T>;
}

export function login(username: string, password: string, totpCode?: string): Promise<LoginOutcome> {
  return request('/login', { method: 'POST', body: JSON.stringify({ username, password, totpCode }) });
}

export function totpStatus(): Promise<TotpStatusResponse> {
  return request('/account/2fa/status');
}

export function totpSetup(): Promise<TotpSetupResponse> {
  return request('/account/2fa/setup', { method: 'POST' });
}

export function totpConfirm(code: string): Promise<TotpConfirmOutcome> {
  return request('/account/2fa/confirm', { method: 'POST', body: JSON.stringify({ code }) });
}

export function totpDisable(code: string): Promise<MessageResponse> {
  return request('/account/2fa/disable', { method: 'POST', body: JSON.stringify({ code }) });
}

export function resetUserTotp(username: string): Promise<MessageResponse> {
  return request(`/admin/users/${encodeURIComponent(username)}/reset-2fa`, { method: 'POST' });
}

/** Setup-scoped equivalents of totpSetup/totpConfirm -- used by Setup2faPage, which authenticates
 * with the short-lived setupToken from a `setupRequired` login response rather than the normal
 * stored session. */
export function totpSetupWithToken(token: string): Promise<TotpSetupResponse> {
  return setupRequest(token, '/account/2fa/setup', { method: 'POST' });
}

export function totpConfirmWithToken(token: string, code: string): Promise<TotpConfirmOutcome> {
  return setupRequest(token, '/account/2fa/confirm', { method: 'POST', body: JSON.stringify({ code }) });
}

export function score(loan: LoanFeatures): Promise<ScoreResponse> {
  return request('/score', { method: 'POST', body: JSON.stringify(loan) });
}

export async function getCachedScore(loanId: string): Promise<CachedScore | null> {
  const res = await authorizedFetch(`/score/${encodeURIComponent(loanId)}`);
  if (res.status === 404) return null;
  if (!res.ok) throw new ApiError(res.status, `Request failed: ${res.status}`);
  return res.json();
}

export function regimeForecast(monthsAhead: number): Promise<RegimeForecast> {
  return request(`/regime-forecast?monthsAhead=${monthsAhead}`);
}

export function simulateCvar(req: CvarRequest): Promise<CvarResult> {
  return request('/cvar', { method: 'POST', body: JSON.stringify(req) });
}

export function expectedLoss(loan: LoanFeatures): Promise<ExpectedLossResponse> {
  return request('/expected-loss', { method: 'POST', body: JSON.stringify(loan) });
}

export function trajectoryScore(req: TrajectoryRequest): Promise<TrajectoryScoreResponse> {
  return request('/trajectory-score', { method: 'POST', body: JSON.stringify(req) });
}

export function listSegments(): Promise<string[]> {
  return request('/segments');
}

export function segmentNeighbors(state: string, maxHops: number): Promise<SegmentNeighbor[]> {
  return request(`/segments/${encodeURIComponent(state)}/neighbors?maxHops=${maxHops}`);
}

export function auditLog(limit = 50): Promise<AuditLogEntry[]> {
  return request(`/admin/audit-log?limit=${limit}`);
}

export function myLoans(): Promise<MyLoanView[]> {
  return request('/my/loans');
}

export function createUser(req: CreateUserRequest): Promise<CreateUserResponse> {
  return request('/admin/users', { method: 'POST', body: JSON.stringify(req) });
}

export function addLoanToUser(username: string, loanId: string): Promise<CreateUserResponse> {
  return request(`/admin/users/${encodeURIComponent(username)}/loans`, {
    method: 'POST',
    body: JSON.stringify({ loanId }),
  });
}

export function loginAttempts(limit = 50): Promise<LoginAttemptEntry[]> {
  return request(`/admin/login-attempts?limit=${limit}`);
}

export function changePassword(currentPassword: string, newPassword: string): Promise<MessageResponse> {
  return request('/account/password', { method: 'POST', body: JSON.stringify({ currentPassword, newPassword }) });
}

export function resetUserPassword(username: string, newPassword: string): Promise<MessageResponse> {
  return request(`/admin/users/${encodeURIComponent(username)}/reset-password`, {
    method: 'POST',
    body: JSON.stringify({ newPassword }),
  });
}

export function deactivateUser(username: string): Promise<UserStatusResponse> {
  return request(`/admin/users/${encodeURIComponent(username)}/deactivate`, { method: 'POST' });
}

export function activateUser(username: string): Promise<UserStatusResponse> {
  return request(`/admin/users/${encodeURIComponent(username)}/activate`, { method: 'POST' });
}

/** The user directory -- every account, admin-only. */
export function listUsers(): Promise<UserSummary[]> {
  return request('/admin/users');
}

/** Completes a CLIENT invite (ActivatePage) -- fully public, no stored session exists yet, so
 * this bypasses `request()`'s auth/401 handling entirely, same reasoning as `setupRequest`. */
export function activateAccount(activationToken: string, password: string): Promise<LoginResponse> {
  return fetch(`${BASE_URL}/activate`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ activationToken, password }),
  }).then(async (res) => {
    if (!res.ok) {
      let message = `Request failed: ${res.status}`;
      try {
        const body = await res.json();
        if (body.error) message = body.error;
      } catch {
        // response body wasn't JSON -- keep the generic message
      }
      throw new ApiError(res.status, message);
    }
    return res.json() as Promise<LoginResponse>;
  });
}
