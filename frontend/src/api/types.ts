// Mirrors the Java DTOs in backend/risk-engine exactly -- field names are a direct mapping,
// since Jackson already serializes the record component names as camelCase (verified against
// live responses during backend development, e.g. calibratedProbability, valueAtRiskConfidenceInterval).

export type Role = 'ANALYST' | 'ADMIN' | 'CLIENT' | 'PLATFORM_ADMIN';

export interface LoanFeatures {
  loanId?: string;
  creditScore: number;
  originalDti: number;
  originalUpb: number;
  originalCltv: number;
  originalLtv: number;
  originalInterestRate: number;
  originalLoanTerm: number;
  numberOfBorrowers: number;
  numberOfUnits: number;
  miPercent: number;
  occupancyStatus: string;
  propertyType: string;
  loanPurpose: string;
  channel: string;
  firstTimeHomebuyerFlag: string;
  propertyState: string;
}

export interface Attribution {
  feature: string;
  contribution: number;
}

export interface ScoreResponse {
  rawProbability: number;
  calibratedProbability: number;
  explanation?: Attribution[];
}

/** One row of GET /loan-scores -- every loan anyone has scored so far, most recent first. */
export interface LoanScoreSummary {
  loanId: string;
  rawProbability: number;
  calibratedProbability: number;
  computedAt: string;
}

export interface CachedScore {
  score: ScoreResponse;
  computedAt: string;
}

export interface RegimeForecast {
  asOfMonth: string;
  forecastMonth: string;
  monthsAhead: number;
  regimeProbabilities: { calm: number; stressed: number };
}

export interface LoanRiskProfile {
  loanId?: string;
  pd: number;
  lgd: number;
  ead: number;
}

export interface CvarRequest {
  loans: LoanRiskProfile[];
  confidenceLevel?: number;
  numScenarios?: number;
}

export interface CvarResult {
  valueAtRisk: number;
  conditionalValueAtRisk: number;
  valueAtRiskConfidenceInterval: [number, number];
  conditionalValueAtRiskConfidenceInterval: [number, number];
  meanLoss: number;
  numLoans: number;
  numScenarios: number;
  confidenceLevel: number;
}

export interface ExpectedLossResponse {
  pd: number;
  lgd: number;
  ead: number;
  expectedLoss: number;
}

export interface MonthlyRecord {
  currentLoanDelinquencyStatus: string;
  currentActualUpb: number;
  modificationFlag: string;
}

export interface TrajectoryRequest {
  originalUpb: number;
  months: MonthlyRecord[];
}

export interface TrajectoryScoreResponse {
  probability: number;
}

/** One entry in the real-trajectory catalog (GET /trajectory-loans). */
export interface TrajectoryCatalogEntry {
  label: string;
  request: TrajectoryRequest;
}

export interface SegmentNeighbor {
  state: string;
  hops: number;
}

/** A currently-current loan's monthly snapshot (early_warning_delinquency.ipynb v3 features).
 * The trend/regime fields (eltv, upbPaydownRatio, rateLockSeverity and their 3m/6m changes,
 * hmmRegime) are not derived here -- they're the caller's responsibility, same simplification as
 * EAD in ExpectedLossController: real feature engineering lives in the batch snapshot job, not
 * reimplemented in this form. */
export interface EarlyWarningFeatures {
  creditScore: number;
  originalDti: number;
  originalUpb: number;
  originalCltv: number;
  originalLtv: number;
  originalInterestRate: number;
  originalLoanTerm: number;
  numberOfBorrowers: number;
  numberOfUnits: number;
  miPercent: number;
  loanAge: number;
  eltv: number;
  currentInterestRate: number;
  upbPaydownRatio: number;
  rateLockSeverity: number;
  eltvChange3m: number;
  upbPaydownChange3m: number;
  rateLockSeverityChange3m: number;
  eltvChange6m: number;
  upbPaydownChange6m: number;
  rateLockSeverityChange6m: number;
  occupancyStatus: string;
  propertyType: string;
  loanPurpose: string;
  channel: string;
  firstTimeHomebuyerFlag: string;
  propertyState: string;
  hmmRegime: string;
  priorAssistance: boolean;
  priorModification: boolean;
  priorDisaster: boolean;
  upbStalled: boolean;
}

/** rawRisk is full-resolution and what to sort by when ranking multiple loans; calibratedRisk is
 * the number meaningful against the true population rate and what to display. See
 * EarlyWarningResponse's Javadoc for why the two are kept separate. */
export interface EarlyWarningResponse {
  rawRisk: number;
  calibratedRisk: number;
}

/** One entry in the real-snapshot catalog (GET /early-warning-loans) -- actuallyWentDelinquent is
 * the real, later-observed outcome, shown purely for comparison against the prediction. */
export interface EarlyWarningCatalogEntry {
  label: string;
  actuallyWentDelinquent: boolean;
  features: EarlyWarningFeatures;
}

/** Shape of the {field: message} validation-error body every endpoint returns on 400. */
export interface ValidationErrorBody {
  error: string;
  fields: Record<string, string>;
}

export interface LoginResponse {
  token: string;
  username: string;
  role: Role;
  expiresAt: string;
  sandbox?: boolean;
}

export interface MfaRequiredResponse {
  mfaRequired: true;
}

export interface SetupRequiredResponse {
  setupRequired: true;
  setupToken: string;
  setupTokenExpiresAt: string;
}

export type LoginOutcome = LoginResponse | MfaRequiredResponse | SetupRequiredResponse;

/** /account/2fa/confirm returns a real session (bootstrap flow, via a setup token) or just a
 * confirmation message (voluntary opt-in flow, caller already has a normal session). */
export type TotpConfirmOutcome = LoginResponse | MessageResponse;

export interface TotpStatusResponse {
  enabled: boolean;
  required: boolean;
}

export interface TotpSetupResponse {
  qrCodeDataUri: string;
  secret: string;
}

export interface AuditLogEntry {
  id: number;
  endpoint: string;
  requestJson: string | null;
  responseJson: string | null;
  success: boolean;
  errorMessage: string | null;
  occurredAt: string;
  latencyMs: number;
}

export interface LoginAttemptEntry {
  id: number;
  username: string;
  success: boolean;
  occurredAt: string;
}

export interface MyLoanView {
  loanId: string;
  calibratedProbability: number | null;
  computedAt: string | null;
}

/** password is required for ANALYST/ADMIN and must be omitted for CLIENT -- CLIENT accounts are
 * activated via invite instead (see ActivatePage). */
export interface CreateUserRequest {
  username: string;
  password?: string;
  role: Role;
  loanIds?: string[];
}

export interface CreateUserResponse {
  username: string;
  role: Role;
  loanIds: string[];
  /** Present only when role was CLIENT -- a one-time link the admin shares with the client so
   * they can set their own password (no email delivery in this app; share it manually). */
  activationLink?: string;
}

export interface MessageResponse {
  message: string;
}

export interface UserStatusResponse {
  username: string;
  enabled: boolean;
}

/** One row of GET /admin/users -- the user directory. */
export interface UserSummary {
  username: string;
  role: Role;
  enabled: boolean;
  activated: boolean;
  totpEnabled: boolean;
  locked: boolean;
  createdAt: string;
  loanIds: string[];
}

export type LoanCaseStatus = 'NEW' | 'REVIEWED' | 'ESCALATED' | 'CLEARED';

export interface LoanNoteView {
  author: string;
  text: string;
  createdAt: string;
}

export interface LoanCaseView {
  loanId: string;
  status: LoanCaseStatus;
  assignedTo: string | null;
  flagged: boolean;
  updatedAt: string;
  notes: LoanNoteView[];
}

/** One row of GET /loan-cases -- every loan case across the system. */
export interface LoanCaseSummary {
  loanId: string;
  status: LoanCaseStatus;
  assignedTo: string | null;
  flagged: boolean;
  updatedAt: string;
}

/** One row of GET /loan-notes/recent -- the cross-loan activity feed. */
export interface RecentNoteView {
  loanId: string;
  author: string;
  text: string;
  createdAt: string;
}

/** POST /assistant/chat -- loanId is optional; when given, the backend grounds the answer in
 * that loan's known features/score/case/notes. */
export interface AssistantChatRequest {
  loanId?: string;
  question: string;
}

export interface AssistantChatResponse {
  answer: string;
}

/** One row of GET /notifications. */
export interface NotificationView {
  id: number;
  message: string;
  link: string | null;
  read: boolean;
  createdAt: string;
}

/** One row of GET /loans/{loanId}/attachments. */
export interface AttachmentView {
  id: number;
  filename: string;
  contentType: string;
  sizeBytes: number;
  uploadedBy: string;
  uploadedAt: string;
}

export interface PageResult<T> {
  items: T[];
  total: number;
  page: number;
  size: number;
  hasMore: boolean;
}

export interface CaseSearchParams {
  status?: LoanCaseStatus;
  flagged?: boolean;
  assignedTo?: string;
  state?: string;
  page?: number;
  size?: number;
}

export interface BulkItemResult {
  loanId: string;
  ok: boolean;
  error: string | null;
}

export interface SignupRequest {
  organizationName: string;
  slug: string;
  adminUsername: string;
  email: string;
  password: string;
}

export type NotificationType = 'CASE_ASSIGNED' | 'NOTE_ADDED' | 'AUTOMATION';
export type DeliveryMode = 'INSTANT' | 'DIGEST' | 'OFF';
export type NotificationPreferences = Record<NotificationType, DeliveryMode>;

export interface DashboardConfig {
  widgets: string[];
  available: string[];
}

export type RuleTrigger = 'LOAN_SCORED' | 'LOAN_CASE_UPDATED';
export type ConditionOp = 'GT' | 'GTE' | 'LT' | 'LTE' | 'EQ' | 'NE';
export type ActionType = 'FLAG_CASE' | 'ASSIGN_CASE' | 'SEND_NOTIFICATION';

export interface RuleAction {
  type: ActionType;
  param?: string | null;
}

export interface AutomationRule {
  id: number;
  name: string;
  trigger: RuleTrigger;
  field: string;
  op: ConditionOp;
  value: string;
  actions: string;
  position: number;
  enabled: boolean;
}

export interface CreateRuleRequest {
  name: string;
  trigger: RuleTrigger;
  field: string;
  op: ConditionOp;
  value: string;
  actions: RuleAction[];
  position?: number;
}

export interface ApiKeyView {
  id: number;
  name: string;
  prefix: string;
  createdBy: string;
  createdAt: string;
  lastUsedAt: string | null;
  revokedAt: string | null;
}

export type WebhookEvent = 'LOAN_SCORED' | 'CASE_FLAGGED' | 'CASE_ASSIGNED';

export interface WebhookView {
  id: number;
  url: string;
  events: WebhookEvent[];
  enabled: boolean;
  secret?: string | null;
}

export interface WebhookDelivery {
  id: number;
  eventId: string;
  eventType: string;
  status: string;
  attempts: number;
  lastError: string | null;
  createdAt: string;
}

export interface UsageView {
  plan: string;
  seatLimit: number;
  seatsUsed: number;
  period: string;
  usage: Record<string, number>;
  rateLimit: { capacity: number; perSecond: number };
}

export interface NoteTopic {
  terms: string[];
  examples: string[];
  size: number;
}

export interface BorrowerSegment {
  size: number;
  definingTraits: { feature: string; direction: string; zScore: number }[];
  averages: Record<string, number>;
}
