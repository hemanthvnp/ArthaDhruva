import { clearStoredAuth, getStoredToken } from '../auth/AuthContext';
import type {
  AuditLogEntry,
  CachedScore,
  CvarRequest,
  CvarResult,
  ExpectedLossResponse,
  LoanFeatures,
  LoginResponse,
  RegimeForecast,
  ScoreResponse,
  SegmentNeighbor,
  TrajectoryRequest,
  TrajectoryScoreResponse,
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

export function login(username: string, password: string): Promise<LoginResponse> {
  return request('/login', { method: 'POST', body: JSON.stringify({ username, password }) });
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
