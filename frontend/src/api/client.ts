import { clearStoredAuth, getStoredAuth, getStoredToken, sessionFrom, storeAuth, type AuthState } from '../auth/session';
import type {
  AssistantChatRequest,
  AssistantChatResponse,
  AttachmentView,
  ApiKeyView,
  AuditLogEntry,
  AutomationRule,
  BorrowerSegment,
  BulkItemResult,
  CaseEventView,
  CaseSearchParams,
  CreateRuleRequest,
  DashboardConfig,
  DeliveryMode,
  DriftReport,
  LifetimeRiskInput,
  LoanResultPage,
  LoanResultSort,
  LossParameters,
  ModelCard,
  ModelEntry,
  NoteTopic,
  NotificationPreferences,
  NotificationType,
  PageResult,
  PortfolioLossDistribution,
  PortfolioRun,
  PortfolioSnapshot,
  PortfolioView,
  AccessRequestSubmission,
  ActivationSignInRequired,
  Scenario,
  ScenarioComparison,
  SessionRotated,
  SurvivalBacktest,
  TermStructureResponse,
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
  /** The parsed error body, for errors that carry more than a message (a 409 carries the current state). */
  body?: unknown;

  constructor(status: number, message: string, fields?: Record<string, string>, body?: unknown) {
    super(message);
    this.status = status;
    this.fields = fields;
    this.body = body;
  }
}

// ---- session ---------------------------------------------------------------------------------
//
// Access tokens are short-lived (minutes). While the user is working, the token is exchanged for a
// fresh one about once a minute, up to the session's absolute limit, so the time left always counts
// from the last thing the user did; a user who walks away simply lets it lapse, which is the idle
// timeout. Only requests the user caused keep a session alive -- background polling (the notification
// bell, watching a run) must not, or an unattended tab would never time out.

const REFRESH_AFTER_MS = 60_000;
let refreshing: Promise<void> | null = null;

async function refreshSession(auth: AuthState): Promise<void> {
  try {
    const res = await fetch(`${API_BASE}/account/session/refresh`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${auth.token}` },
    });
    if (res.ok) storeAuth(sessionFrom((await res.json()) as LoginResponse));
  } catch {
    // Offline or the server is down: the request that follows reports it.
  }
}

/** One refresh at a time, however many requests notice the token has aged. */
async function ensureFreshSession(): Promise<void> {
  const auth = getStoredAuth();
  if (!auth?.expiresAt || !auth.obtainedAt) return;
  const expired = Date.parse(auth.expiresAt) <= Date.now();
  if (expired || Date.now() - auth.obtainedAt < REFRESH_AFTER_MS) return; // gone (the request will say so), or fresh
  refreshing ??= refreshSession(auth).finally(() => {
    refreshing = null;
  });
  await refreshing;
}

function sessionExpired(): never {
  clearStoredAuth();
  window.location.href = '/login';
  throw new ApiError(401, 'Your session has expired. Please sign in again.');
}

/** Ends the session on the server (every device), then locally. Never blocks sign-out on the network. */
export async function signOut(): Promise<void> {
  const token = getStoredToken();
  clearStoredAuth();
  if (token) {
    await fetch(`${API_BASE}/account/logout`, { method: 'POST', headers: { Authorization: `Bearer ${token}` } }).catch(() => {});
  }
}

/** @param background true for polling the user did not initiate: it does not extend the session. */
async function authorizedFetch(path: string, options?: RequestInit, background = false): Promise<Response> {
  if (!background) await ensureFreshSession();
  const token = getStoredToken();
  const headers: Record<string, string> = { 'Content-Type': 'application/json' };
  if (token) headers.Authorization = `Bearer ${token}`;
  return fetch(`${API_BASE}${path}`, { ...options, headers: { ...headers, ...options?.headers } });
}

async function errorFrom(res: Response): Promise<ApiError> {
  let message = `Request failed: ${res.status}`;
  let fields: Record<string, string> | undefined;
  let body: unknown;
  try {
    body = await res.json();
    const parsed = body as { fields?: Record<string, string>; error?: string };
    if (parsed.fields) {
      fields = parsed.fields;
      message = Object.entries(parsed.fields)
        .map(([field, msg]) => `${field}: ${msg}`)
        .join(', ');
    } else if (parsed.error) {
      message = parsed.error;
    }
  } catch {
    // response body wasn't JSON -- keep the generic message
  }
  if (res.status === 429) {
    const retryAfter = res.headers.get('Retry-After');
    message = `Too many requests${retryAfter ? `. Try again in ${retryAfter}s` : ''}.`;
  }
  return new ApiError(res.status, message, fields, body);
}

async function request<T>(path: string, options?: RequestInit, background = false): Promise<T> {
  const res = await authorizedFetch(path, options, background);
  if (res.status === 401 && path !== '/login') sessionExpired();
  if (!res.ok) throw await errorFrom(res);
  return res.json() as Promise<T>;
}

/** Bypasses stored auth entirely -- used only for the narrow setup-token flow (Setup2faPage),
 * where the caller isn't fully logged in yet and a 401 (e.g. a wrong confirmation code) must
 * just show an error, not trigger the normal "session expired -> redirect to /login" handling
 * that `request()` applies to every other endpoint. */
async function setupRequest<T>(token: string, path: string, options?: RequestInit): Promise<T> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` };
  const res = await fetch(`${API_BASE}${path}`, { ...options, headers: { ...headers, ...options?.headers } });
  if (!res.ok) throw await errorFrom(res);
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

/** A wrong code answers 401, which must show as an error here, not end the session. */
async function secondFactorRequest<T>(path: string, code: string): Promise<T> {
  const res = await authorizedFetch(path, { method: 'POST', body: JSON.stringify({ code }) });
  if (!res.ok) throw await errorFrom(res);
  return res.json() as Promise<T>;
}

export function totpConfirm(code: string): Promise<TotpConfirmOutcome> {
  return secondFactorRequest('/account/2fa/confirm', code);
}

export function totpDisable(code: string): Promise<SessionRotated> {
  return secondFactorRequest('/account/2fa/disable', code);
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
  if (res.status === 401) sessionExpired();
  if (res.status === 404) return null;
  if (!res.ok) throw await errorFrom(res);
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

// ---- lifetime risk ---------------------------------------------------------------------------

export function listScenarios(): Promise<Scenario[]> {
  return request('/risk/scenarios');
}

/** One loan's month-by-month default / prepayment / survival curve and ECL under one scenario. */
export function termStructure(input: LifetimeRiskInput, scenario: string): Promise<TermStructureResponse> {
  return request('/risk/term-structure', { method: 'POST', body: JSON.stringify({ ...input, scenario }) });
}

/** The same loan under every built-in scenario, in one call. */
export function compareScenarios(input: LifetimeRiskInput): Promise<ScenarioComparison> {
  return request('/risk/term-structure/compare', { method: 'POST', body: JSON.stringify(input) });
}

// ---- portfolio risk --------------------------------------------------------------------------

/** The latest snapshot of every scenario, and the latest run (so a reload can resume watching it). */
export function getPortfolioRisk(): Promise<PortfolioView> {
  return request('/risk/portfolio');
}

/**
 * Starts a projection of the whole portfolio. It runs in the background: the returned run is polled
 * with {@link getPortfolioRun}. If one is already in progress for the organization, that one is returned.
 */
export async function startPortfolioRun(scenarios?: string[]): Promise<PortfolioRun> {
  const res = await authorizedFetch('/risk/portfolio/runs', { method: 'POST', body: JSON.stringify(scenarios ? { scenarios } : {}) });
  if (res.status === 401) sessionExpired();
  if (res.status === 409) return res.json();
  if (!res.ok) throw await errorFrom(res);
  return res.json();
}

/** Progress of a run. A background request: watching a run must not keep an unattended session alive. */
export function getPortfolioRun(id: string): Promise<PortfolioRun> {
  return request(`/risk/portfolio/runs/${encodeURIComponent(id)}`, undefined, true);
}

export function portfolioRiskHistory(scenario: string, limit = 36): Promise<PortfolioSnapshot[]> {
  return request(`/risk/portfolio/history?scenario=${encodeURIComponent(scenario)}&limit=${limit}`);
}

/** The latest run's baseline results loan by loan, one page of them. */
export function portfolioLoanResults(options: { stage?: number; sort?: LoanResultSort; limit?: number; offset?: number } = {}): Promise<LoanResultPage> {
  const q = new URLSearchParams({ sort: options.sort ?? 'eclLifetime', limit: String(options.limit ?? 25), offset: String(options.offset ?? 0) });
  if (options.stage !== undefined) q.set('stage', String(options.stage));
  return request(`/risk/portfolio/loans?${q.toString()}`);
}

export function downloadPortfolioLoanResultsCsv(): Promise<void> {
  return downloadBlob('/risk/portfolio/loans/export', 'portfolio-ecl-by-loan.csv');
}

/** Simulates the portfolio's one-year loss distribution from the loans of its latest run. */
export function portfolioLossDistribution(parameters: LossParameters): Promise<PortfolioLossDistribution> {
  return request('/risk/portfolio/loss-distribution', { method: 'POST', body: JSON.stringify(parameters) });
}

// ---- model governance ------------------------------------------------------------------------

export function listModels(): Promise<ModelEntry[]> {
  return request('/models');
}

/** The model card as exported by the training script; `T` names the card the caller expects for that id. */
export function modelCard<T extends ModelCard = ModelCard>(id: string): Promise<T> {
  return request(`/models/${encodeURIComponent(id)}`);
}

export function survivalBacktest(): Promise<SurvivalBacktest> {
  return request('/models/survival/backtest');
}

export function pdDrift(): Promise<DriftReport> {
  return request('/models/pd_24m/drift');
}

// ---- portfolio and catalog -------------------------------------------------------------------

/** The portfolio (the organization's own loans, or the demo catalog until it uploads any), first page. */
export function listLoanCatalog(limit = 500): Promise<LoanFeatures[]> {
  return request(`/loans?limit=${limit}`);
}

/** Loans whose id contains the given text: for pickers over portfolios too large to load whole. */
export function searchLoans(idContains: string, limit = 20): Promise<LoanFeatures[]> {
  return request(`/loans?limit=${limit}&q=${encodeURIComponent(idContains)}`);
}

export async function getLoan(loanId: string): Promise<LoanFeatures | null> {
  const res = await authorizedFetch(`/loans/${encodeURIComponent(loanId)}`);
  if (res.status === 401) sessionExpired();
  if (res.status === 404) return null;
  if (!res.ok) throw await errorFrom(res);
  return res.json();
}

/** The most recently scored loans (up to 500, the server's cap), newest first. The CSV export has all of them. */
export function listLoanScores(): Promise<LoanScoreSummary[]> {
  return request('/loan-scores?limit=500');
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

/** Every state and every correlation edge (each once), in one request. */
export function segmentGraph(): Promise<{ states: string[]; edges: [string, string][] }> {
  return request('/segments/graph');
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

/** Changing the password signs every other session out and returns a new session for this one. */
export async function changePassword(currentPassword: string, newPassword: string): Promise<SessionRotated> {
  // A wrong current password answers 401, which must show as an error, not end the session.
  const res = await authorizedFetch('/account/password', { method: 'POST', body: JSON.stringify({ currentPassword, newPassword }) });
  if (!res.ok) throw await errorFrom(res);
  return res.json();
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

export interface CaseChange {
  status: LoanCaseStatus;
  assignedTo: string | null;
  flagged: boolean;
  /** Required when escalating, clearing an escalation or reopening. */
  reason?: string;
  /** The version the change was based on; a newer one on the server answers 409 with the current case. */
  expectedVersion?: number;
}

export function updateLoanCase(loanId: string, change: CaseChange): Promise<LoanCaseView> {
  return request(`/loans/${encodeURIComponent(loanId)}/case`, { method: 'POST', body: JSON.stringify(change) });
}

export function loanCaseHistory(loanId: string, limit = 100): Promise<CaseEventView[]> {
  return request(`/loans/${encodeURIComponent(loanId)}/case/history?limit=${limit}`);
}

export function addLoanNote(loanId: string, text: string): Promise<LoanCaseView> {
  return request(`/loans/${encodeURIComponent(loanId)}/notes`, { method: 'POST', body: JSON.stringify({ text }) });
}

/** The most recently changed cases (up to 500, the server's cap); case search pages through all of them. */
export function listLoanCases(): Promise<LoanCaseSummary[]> {
  return request('/loan-cases?limit=500');
}

/** The cross-loan activity feed (most recent notes, newest first). */
export function listRecentNotes(): Promise<RecentNoteView[]> {
  return request('/loan-notes/recent');
}

/** Client account(s) (if any) this loan is linked to. */
export function getLoanClients(loanId: string): Promise<string[]> {
  return request(`/loans/${encodeURIComponent(loanId)}/clients`);
}

export function chatWithAssistant(req: AssistantChatRequest): Promise<AssistantChatResponse> {
  return request('/assistant/chat', { method: 'POST', body: JSON.stringify(req) });
}

export function listNotifications(limit = 50): Promise<NotificationView[]> {
  return request(`/notifications?limit=${limit}`);
}

/** Polled by the bell: a background request, so it never extends the session. */
export async function unreadNotificationCount(): Promise<number> {
  const res = await request<{ count: number }>('/notifications/unread-count', undefined, true);
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
  await ensureFreshSession();
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
  if (res.status === 401) sessionExpired();
  if (!res.ok) throw await errorFrom(res);
  return res.json();
}

/** Fetches a binary/attachment response with the auth header (a plain `<a href>` can't carry a
 * bearer token) and triggers a browser download via a temporary object URL. Used for both
 * attachment downloads and CSV exports. */
async function downloadBlob(path: string, fallbackFilename: string): Promise<void> {
  const res = await authorizedFetch(path);
  if (res.status === 401) sessionExpired();
  if (!res.ok) throw await errorFrom(res);
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

/** Admin only: removes the stored file and its record. */
export function deleteAttachment(loanId: string, attachmentId: number): Promise<void> {
  return remove(`/admin/loans/${encodeURIComponent(loanId)}/attachments/${attachmentId}`);
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
  if (!res.ok) throw await errorFrom(res);
  return res.json() as Promise<T>;
}

/** Completes an invite (ActivatePage): public, no stored session exists yet. */
export function activateAccount(activationToken: string, password: string): Promise<LoginResponse | ActivationSignInRequired> {
  return publicPost('/activate', { activationToken, password });
}

/** Trades the single-use code the SSO callback put in the URL fragment for a session. */
export function exchangeSsoCode(code: string): Promise<LoginResponse> {
  return publicPost('/sso/exchange', { code });
}

export function requestAccess(req: AccessRequestSubmission): Promise<{ status: string; next: string }> {
  return publicPost('/access-requests', req);
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
  change: { status?: LoanCaseStatus; assignedTo?: string; flagged?: boolean; reason?: string },
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

async function remove(path: string): Promise<void> {
  const res = await authorizedFetch(path, { method: 'DELETE' });
  if (res.status === 401) sessionExpired();
  if (!res.ok) throw await errorFrom(res);
}

export function deleteAutomationRule(id: number): Promise<void> {
  return remove(`/admin/automation-rules/${id}`);
}

export function listApiKeys(): Promise<ApiKeyView[]> {
  return request('/admin/api-keys');
}

export function createApiKey(name: string): Promise<{ id: number; key: string; prefix: string }> {
  return request('/admin/api-keys', { method: 'POST', body: JSON.stringify({ name }) });
}

export function revokeApiKey(id: number): Promise<void> {
  return remove(`/admin/api-keys/${id}`);
}

export function listWebhooks(): Promise<WebhookView[]> {
  return request('/admin/webhooks');
}

export function createWebhook(url: string, events: WebhookEvent[]): Promise<WebhookView> {
  return request('/admin/webhooks', { method: 'POST', body: JSON.stringify({ url, events }) });
}

export function deleteWebhook(id: number): Promise<void> {
  return remove(`/admin/webhooks/${id}`);
}

export function listWebhookDeliveries(): Promise<PageResult<WebhookDelivery>> {
  return request('/admin/webhooks/deliveries?size=20');
}

export function getPortfolioStatus(): Promise<{ usingOwnPortfolio: boolean; loanCount: number }> {
  return request('/admin/portfolio');
}

export interface PortfolioUploadResult {
  accepted: number;
  rejected: number;
  results: { index: number; loanId: string | null; ok: boolean; error: string | null }[];
}

export function uploadPortfolio(loans: unknown[]): Promise<PortfolioUploadResult> {
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

/** The redirect URI an identity provider must be told about: always absolute, also when the API is same-origin. */
export function ssoCallbackUrl(): string {
  return new URL(`${API_BASE}/sso/callback`, window.location.origin).toString();
}

export function getSsoConfig(): Promise<{ configured: boolean; issuer?: string; clientId?: string; enabled?: boolean; enforced?: boolean }> {
  return request('/admin/sso');
}

export function saveSsoConfig(cfg: { issuer: string; clientId: string; clientSecret?: string; enabled: boolean; enforced: boolean }): Promise<{ saved: boolean }> {
  return request('/admin/sso', { method: 'PUT', body: JSON.stringify(cfg) });
}
