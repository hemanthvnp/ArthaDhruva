import { clearStoredAuth, getStoredToken } from '../auth/AuthContext';
import type {
  AssistantChatRequest,
  AssistantChatResponse,
  AttachmentView,
  ApiKeyView,
  AuditLogEntry,
  AutomationRule,
  BorrowerSegment,
  BulkItemResult,
  CaseSearchParams,
  CreateRuleRequest,
  DashboardConfig,
  DeliveryMode,
  NoteTopic,
  NotificationPreferences,
  NotificationType,
  PageResult,
  SignupRequest,
  UsageView,
  WebhookDelivery,
  WebhookEvent,
  WebhookView,
  CachedScore,
  CreateUserRequest,
  CreateUserResponse,
  CvarRequest,
  CvarResult,
  EarlyWarningCatalogEntry,
  EarlyWarningFeatures,
  EarlyWarningResponse,
  ExpectedLossResponse,
  LoanCaseStatus,
  LoanCaseSummary,
  LoanCaseView,
  LoanFeatures,
  LoanScoreSummary,
  LoginAttemptEntry,
  LoginOutcome,
  LoginResponse,
  MessageResponse,
  MyLoanView,
  NotificationView,
  RecentNoteView,
  RegimeForecast,
  ScoreResponse,
  SegmentNeighbor,
  TotpConfirmOutcome,
  TotpSetupResponse,
  TotpStatusResponse,
  TrajectoryCatalogEntry,
  TrajectoryRequest,
  TrajectoryScoreResponse,
  UserStatusResponse,
  UserSummary,
} from './types';

const BASE_URL = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080';
// Every backend endpoint below is reachable under /v1 -- WebMvcConfig adds this prefix to every
// @RestController so a future breaking change can ship as /v2 without touching existing clients.
// /actuator/health and /error are the only backend paths deliberately left unprefixed, and
// neither is called from here.
const API_BASE = `${BASE_URL}/v1`;

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
  return fetch(`${API_BASE}${path}`, { ...options, headers: { ...headers, ...options?.headers } });
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
  const res = await fetch(`${API_BASE}${path}`, { ...options, headers: { ...headers, ...options?.headers } });
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

export function login(orgSlug: string, username: string, password: string, totpCode?: string): Promise<LoginOutcome> {
  return request('/login', { method: 'POST', body: JSON.stringify({ orgSlug, username, password, totpCode }) });
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

/** The real-loan catalog (backend/export_loan_catalog.py) -- lets an analyst pick an actual loan
 * to score instead of typing feature values by hand. Small enough to fetch in full. */
export function listLoanCatalog(): Promise<LoanFeatures[]> {
  return request('/loans');
}

/** Every loan anyone has scored so far, most recent first -- the portfolio view. */
export function listLoanScores(): Promise<LoanScoreSummary[]> {
  return request('/loan-scores');
}

export function earlyWarningScore(loan: EarlyWarningFeatures): Promise<EarlyWarningResponse> {
  return request('/early-warning-score', { method: 'POST', body: JSON.stringify(loan) });
}

/** Real currently-current-loan snapshots (backend/export_early_warning_catalog.py), each with the
 * real, later-observed outcome attached for comparison against the prediction. */
export function listEarlyWarningCatalog(): Promise<EarlyWarningCatalogEntry[]> {
  return request('/early-warning-loans');
}

/** Real loans' actual observed first-up-to-12-months trajectories (backend/export_trajectory_catalog.py). */
export function listTrajectoryCatalog(): Promise<TrajectoryCatalogEntry[]> {
  return request('/trajectory-loans');
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

export function getLoanCase(loanId: string): Promise<LoanCaseView> {
  return request(`/loans/${encodeURIComponent(loanId)}/case`);
}

export function updateLoanCase(
  loanId: string,
  status: LoanCaseStatus,
  assignedTo: string | null,
  flagged: boolean,
): Promise<LoanCaseView> {
  return request(`/loans/${encodeURIComponent(loanId)}/case`, {
    method: 'POST',
    body: JSON.stringify({ status, assignedTo, flagged }),
  });
}

export function addLoanNote(loanId: string, text: string): Promise<LoanCaseView> {
  return request(`/loans/${encodeURIComponent(loanId)}/notes`, { method: 'POST', body: JSON.stringify({ text }) });
}

/** Every loan case across the system -- backs the "My Cases" view. */
export function listLoanCases(): Promise<LoanCaseSummary[]> {
  return request('/loan-cases');
}

/** The cross-loan activity feed (most recent notes, newest first). */
export function listRecentNotes(): Promise<RecentNoteView[]> {
  return request('/loan-notes/recent');
}

/** Client account(s) (if any) this loan is linked to. */
export function getLoanClients(loanId: string): Promise<string[]> {
  return request(`/loans/${encodeURIComponent(loanId)}/clients`);
}

/** Completes a CLIENT invite (ActivatePage) -- fully public, no stored session exists yet, so
 * this bypasses `request()`'s auth/401 handling entirely, same reasoning as `setupRequest`. */
export function activateAccount(activationToken: string, password: string): Promise<LoginResponse> {
  return fetch(`${API_BASE}/activate`, {
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

export function chatWithAssistant(req: AssistantChatRequest): Promise<AssistantChatResponse> {
  return request('/assistant/chat', { method: 'POST', body: JSON.stringify(req) });
}

export function listNotifications(limit = 50): Promise<NotificationView[]> {
  return request(`/notifications?limit=${limit}`);
}

export async function unreadNotificationCount(): Promise<number> {
  const res = await request<{ count: number }>('/notifications/unread-count');
  return res.count;
}

export function markNotificationRead(id: number): Promise<MessageResponse> {
  return request(`/notifications/${id}/read`, { method: 'POST' });
}

export function listAttachments(loanId: string): Promise<AttachmentView[]> {
  return request(`/loans/${encodeURIComponent(loanId)}/attachments`);
}

/** Multipart upload -- deliberately bypasses `request()`/`authorizedFetch()`, which both hardcode
 * a `Content-Type: application/json` header; a multipart body needs the browser to set its own
 * `Content-Type` (with the form boundary), which it only does when no Content-Type is set
 * explicitly. */
export async function uploadAttachment(loanId: string, file: File): Promise<AttachmentView> {
  const token = getStoredToken();
  const headers: Record<string, string> = {};
  if (token) headers.Authorization = `Bearer ${token}`;
  const formData = new FormData();
  formData.append('file', file);

  const res = await fetch(`${API_BASE}/loans/${encodeURIComponent(loanId)}/attachments`, {
    method: 'POST',
    headers,
    body: formData,
  });
  if (res.status === 401) {
    clearStoredAuth();
    window.location.href = '/login';
    throw new ApiError(401, 'Session expired -- please log in again.');
  }
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
  return res.json();
}

/** Fetches a binary/attachment response with the auth header (a plain `<a href>` can't carry a
 * bearer token) and triggers a browser download via a temporary object URL. Used for both
 * attachment downloads and CSV exports. */
async function downloadBlob(path: string, fallbackFilename: string): Promise<void> {
  const res = await authorizedFetch(path);
  if (res.status === 401) {
    clearStoredAuth();
    window.location.href = '/login';
    throw new ApiError(401, 'Session expired -- please log in again.');
  }
  if (!res.ok) {
    throw new ApiError(res.status, `Request failed: ${res.status}`);
  }
  const disposition = res.headers.get('Content-Disposition');
  const filename = disposition?.match(/filename="?([^"]+)"?/)?.[1] ?? fallbackFilename;
  const blob = await res.blob();
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = filename;
  document.body.appendChild(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
}

export function downloadAttachment(loanId: string, attachmentId: number, filename: string): Promise<void> {
  return downloadBlob(`/loans/${encodeURIComponent(loanId)}/attachments/${attachmentId}/download`, filename);
}

export function downloadLoanScoresCsv(): Promise<void> {
  return downloadBlob('/loan-scores/export', 'loan-scores.csv');
}

export function downloadLoanCasesCsv(): Promise<void> {
  return downloadBlob('/loan-cases/export', 'loan-cases.csv');
}

// ---- account & growth ----------------------------------------------------------------------

/** Public endpoints (no session yet): bypass request()'s "401 -> session expired" handling. */
async function publicPost<T>(path: string, body: unknown): Promise<T> {
  const res = await fetch(`${API_BASE}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) {
    const fields = data.fields as Record<string, string> | undefined;
    const message = fields
      ? Object.entries(fields).map(([f, m]) => `${f}: ${m}`).join(', ')
      : (data.error as string | undefined) ?? `Request failed: ${res.status}`;
    throw new ApiError(res.status, message, fields);
  }
  return data as T;
}

export function signup(req: SignupRequest): Promise<{ organization: string; plan: string; trialDays: number; next: string }> {
  return publicPost('/signup', req);
}

export function requestPasswordReset(orgSlug: string, username: string): Promise<MessageResponse> {
  return publicPost('/password-reset/request', { orgSlug, username });
}

export function completePasswordReset(token: string, newPassword: string): Promise<MessageResponse> {
  return publicPost('/password-reset/complete', { token, newPassword });
}

export function getUsage(): Promise<UsageView> {
  return request('/admin/usage');
}

export function getNotificationPreferences(): Promise<NotificationPreferences> {
  return request('/notification-preferences');
}

export function setNotificationPreference(type: NotificationType, mode: DeliveryMode): Promise<NotificationPreferences> {
  return request('/notification-preferences', { method: 'PUT', body: JSON.stringify({ type, mode }) });
}

export function getDashboardConfig(): Promise<DashboardConfig> {
  return request('/dashboard/config');
}

export function saveDashboardConfig(widgets: string[]): Promise<DashboardConfig> {
  return request('/dashboard/config', { method: 'PUT', body: JSON.stringify({ widgets }) });
}

// ---- workflow depth ------------------------------------------------------------------------

export function searchCases(params: CaseSearchParams): Promise<PageResult<LoanCaseSummary>> {
  const q = new URLSearchParams();
  Object.entries(params).forEach(([k, v]) => {
    if (v !== undefined && v !== '') q.set(k, String(v));
  });
  return request(`/loan-cases/search?${q.toString()}`);
}

export function bulkUpdateCases(
  loanIds: string[],
  change: { status?: LoanCaseStatus; assignedTo?: string; flagged?: boolean },
): Promise<BulkItemResult[]> {
  return request('/loan-cases/bulk', { method: 'POST', body: JSON.stringify({ loanIds, ...change }) });
}

export function listAutomationRules(): Promise<AutomationRule[]> {
  return request('/admin/automation-rules');
}

export function createAutomationRule(rule: CreateRuleRequest): Promise<AutomationRule> {
  return request('/admin/automation-rules', { method: 'POST', body: JSON.stringify(rule) });
}

export function setAutomationRuleEnabled(id: number, value: boolean): Promise<{ enabled: boolean }> {
  return request(`/admin/automation-rules/${id}/enabled?value=${value}`, { method: 'POST' });
}

export async function deleteAutomationRule(id: number): Promise<void> {
  const res = await authorizedFetch(`/admin/automation-rules/${id}`, { method: 'DELETE' });
  if (!res.ok) throw new ApiError(res.status, `Request failed: ${res.status}`);
}

export function listApiKeys(): Promise<ApiKeyView[]> {
  return request('/admin/api-keys');
}

export function createApiKey(name: string): Promise<{ id: number; key: string; prefix: string }> {
  return request('/admin/api-keys', { method: 'POST', body: JSON.stringify({ name }) });
}

export async function revokeApiKey(id: number): Promise<void> {
  const res = await authorizedFetch(`/admin/api-keys/${id}`, { method: 'DELETE' });
  if (!res.ok) throw new ApiError(res.status, `Request failed: ${res.status}`);
}

export function listWebhooks(): Promise<WebhookView[]> {
  return request('/admin/webhooks');
}

export function createWebhook(url: string, events: WebhookEvent[]): Promise<WebhookView> {
  return request('/admin/webhooks', { method: 'POST', body: JSON.stringify({ url, events }) });
}

export async function deleteWebhook(id: number): Promise<void> {
  const res = await authorizedFetch(`/admin/webhooks/${id}`, { method: 'DELETE' });
  if (!res.ok) throw new ApiError(res.status, `Request failed: ${res.status}`);
}

export function listWebhookDeliveries(): Promise<PageResult<WebhookDelivery>> {
  return request('/admin/webhooks/deliveries?size=20');
}

export function getPortfolioStatus(): Promise<{ usingOwnPortfolio: boolean; loanCount: number }> {
  return request('/admin/portfolio');
}

export function uploadPortfolio(loans: unknown[]): Promise<{ accepted: number; rejected: number }> {
  return request('/admin/portfolio', { method: 'POST', body: JSON.stringify({ loans }) });
}

export function clearPortfolio(): Promise<{ removed: number }> {
  return request('/admin/portfolio', { method: 'DELETE' });
}

// ---- ML insights ---------------------------------------------------------------------------

export function noteTopics(refresh = false): Promise<{ computedAt: string; noteCount: number; topics: NoteTopic[] }> {
  return request(`/insights/note-topics?refresh=${refresh}`);
}

export function borrowerSegments(refresh = false): Promise<{ computedAt: string; loanCount: number; segments: BorrowerSegment[] }> {
  return request(`/insights/borrower-segments?refresh=${refresh}`);
}

/** Where the browser goes to start single sign-on for an organization (a full-page redirect to the IdP). */
export function ssoLoginUrl(orgSlug: string): string {
  return `${API_BASE}/sso/${encodeURIComponent(orgSlug)}/login`;
}

export function getSsoConfig(): Promise<{ configured: boolean; issuer?: string; clientId?: string; enabled?: boolean; enforced?: boolean }> {
  return request('/admin/sso');
}

export function saveSsoConfig(cfg: { issuer: string; clientId: string; clientSecret?: string; enabled: boolean; enforced: boolean }): Promise<{ saved: boolean }> {
  return request('/admin/sso', { method: 'PUT', body: JSON.stringify(cfg) });
}
