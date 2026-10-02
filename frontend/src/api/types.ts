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
  /** YYYY-MM. Omit for a new application being priced at today's rates. */
  originationMonth?: string | null;
}

export interface Attribution {
  feature: string;
  contribution: number;
}

/** A principal reason the model rates this loan riskier than a typical one. */
export interface ReasonCode {
  code: string;
  feature: string;
  description: string;
  contribution: number;
}

export interface ScoreResponse {
  rawProbability: number;
  calibratedProbability: number;
  explanation?: Attribution[];
  reasonCodes?: ReasonCode[];
  /** Inputs outside the training distribution: the score is an extrapolation. */
  warnings?: string[];
  modelVersion?: string | null;
  horizonMonths?: number;
  /** PD of the reference loan the explanation is measured from; null when read back without one. */
  baselineProbability?: number | null;
}

/** One row of GET /loan-scores -- every loan anyone has scored so far, most recent first. */
export interface LoanScoreSummary {
  loanId: string;
  rawProbability: number;
  calibratedProbability: number;
  computedAt: string;
  modelVersion?: string | null;
}

export interface CachedScore {
  score: ScoreResponse;
  computedAt: string;
}

export interface RegimeProbabilities {
  calm: number;
  stressed: number;
}

export interface RegimeForecast {
  asOfMonth: string;
  forecastMonth: string;
  monthsAhead: number;
  regimeProbabilities: RegimeProbabilities;
  currentRegime: 'calm' | 'stressed';
  /** The distribution in every month up to the forecast month. */
  path: { month: string; regimeProbabilities: RegimeProbabilities }[];
  /** Long-run share of months in each regime. */
  stationary: RegimeProbabilities;
  expectedDurationMonths: { calm: number | null; stressed: number | null };
  /** Months between the last decoded month and today. */
  dataAgeMonths: number;
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
  /** Share of each loan's fate driven by the common factor; 0 makes defaults independent. */
  assetCorrelation?: number;
  seed?: number;
  importanceSampling?: boolean;
  contributions?: boolean;
}

export interface CvarContribution {
  loanId: string | null;
  index: number;
  contribution: number;
  share: number;
  expectedLoss: number;
}

export interface CvarResult {
  valueAtRisk: number;
  conditionalValueAtRisk: number;
  valueAtRiskConfidenceInterval: [number, number];
  conditionalValueAtRiskConfidenceInterval: [number, number];
  expectedLoss: number;
  unexpectedLoss: number;
  /** Closed-form VaR of an infinitely fine-grained portfolio with the same loans. */
  asrfValueAtRisk: number;
  granularityAddOn: number;
  totalExposure: number;
  effectiveLoans: number;
  numLoans: number;
  numScenarios: number;
  confidenceLevel: number;
  assetCorrelation: number;
  importanceSampling: boolean;
  factorShift: number;
  systematicVarianceShare: number;
  seed: number;
  topContributions: CvarContribution[];
  exceedanceCurve: { loss: number; probability: number }[];
  elapsedMillis: number;
}

export interface ExpectedLossResponse {
  /** 12-month PD (survival model, baseline). */
  pd: number;
  lgd: number;
  /** Exposure today: the amortized balance. */
  ead: number;
  /** 12-month expected credit loss, discounted. */
  expectedLoss: number;
  pdLifetime: number;
  expectedLossLifetime: number;
  stage: number;
  eclIfrs9: number;
  expectedLifeMonths: number;
  modelVersion: string;
  warnings: string[];
}

// ---- lifetime risk -------------------------------------------------------------------------

export interface Scenario {
  name: string;
  description: string;
  stressedMonths: number;
  unemploymentShockPp: number;
  unemploymentShockMonths: number;
  unemploymentRecoveryMonths: number;
  hpiShockPct: number;
  hpiShockMonths: number;
  hpiAnnualGrowthPct: number;
  rateShockBp: number;
}

export interface MonthPoint {
  month: string;
  loanAge: number;
  survival: number;
  marginalDefault: number;
  marginalPrepay: number;
  cumulativeDefault: number;
  cumulativePrepay: number;
  defaultHazard: number;
  prepayHazard: number;
  stressedProbability: number;
  exposure: number;
  lgd: number;
  expectedLoss: number;
  discountedExpectedLoss: number;
  unemployment: number;
  housePriceIndex: number;
  mtmLtv: number;
}

export interface TermSummary {
  pd12m: number;
  pd24m: number;
  pdLifetime: number;
  prepayLifetime: number;
  expectedLifeMonths: number;
  maturityProbability: number;
}

export interface Ecl {
  stage: number;
  stageReason: string;
  exposure: number;
  lgd: number;
  lgdPeak: number;
  ecl12m: number;
  eclLifetime: number;
  eclIfrs9: number;
  eclCecl: number;
  referencePd12m: number | null;
}

/** A driver the scenario pushes outside the range the model was trained on. */
export interface Extrapolation {
  driver: string;
  direction: 'below' | 'above';
  trainedLimit: number;
  extreme: number;
  months: number;
  firstMonth: string;
}

export interface TermAssumptions {
  forecastOrigin: string;
  rateAsOf: string;
  regimeAsOf: string;
  originationMonth: string;
  rateSpread: number;
  marketRate: number;
  startUnemployment: number;
  defaultOverlay: number;
  prepayOverlay: number;
  liquidationHaircut: number;
  discountRate: number;
}

export interface TermStructure {
  scenario: string;
  modelVersion: string;
  monthsOnBook: number;
  remainingMonths: number;
  summary: TermSummary;
  ecl: Ecl;
  months: MonthPoint[];
  extrapolations: Extrapolation[];
  assumptions: TermAssumptions;
}

export interface LifetimeRiskInput {
  loan: LoanFeatures;
  monthsOnBook?: number;
  currentBalance?: number;
  daysPastDue?: number;
  originationPd12m?: number;
  months?: number;
}

export interface TermStructureResponse {
  termStructure: TermStructure;
  warnings: string[];
}

export interface ScenarioCurve {
  scenario: string;
  description: string;
  summary: TermSummary;
  ecl: Ecl;
  cumulativeDefault: number[];
  cumulativePrepay: number[];
  survival: number[];
  discountedExpectedLoss: number[];
  extrapolations: Extrapolation[];
}

export interface ScenarioComparison {
  modelVersion: string;
  monthsOnBook: number;
  remainingMonths: number;
  months: string[];
  scenarios: ScenarioCurve[];
  warnings: string[];
}

// ---- portfolio risk ------------------------------------------------------------------------

export type RunStatus = 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'FAILED';

export interface PortfolioRun {
  id: string;
  status: RunStatus;
  scenarios: string[];
  loansTotal: number;
  loansDone: number;
  requestedBy: string;
  requestedAt: string;
  startedAt: string | null;
  finishedAt: string | null;
  error: string | null;
}

export interface StageBreakdown {
  stage: number;
  loans: number;
  exposure: number;
  ecl: number;
}

export interface StateRisk {
  state: string;
  loans: number;
  exposure: number;
  eclLifetime: number;
  eclIfrs9: number;
}

export interface LoanRisk {
  loanId: string | null;
  state: string;
  exposure: number;
  pd12m: number;
  pdLifetime: number;
  eclLifetime: number;
  stage: number;
}

export interface RunoffSeries {
  months: string[];
  balanceAtRisk: number[];
  defaults: number[];
  prepayments: number[];
  expectedLoss: number[];
}

export interface ScenarioRisk {
  scenario: string;
  description: string;
  loans: number;
  exposure: number;
  pd12m: number;
  pdLifetime: number;
  ecl12m: number;
  eclLifetime: number;
  eclIfrs9: number;
  eclCecl: number;
  coverage: number;
  stages: StageBreakdown[];
  series: RunoffSeries;
  states: StateRisk[];
  topLoans: LoanRisk[];
  extrapolatedLoans: Record<string, number>;
  eclRelativeStandardError: number | null;
  /** The one-year loss distribution around the 12-month ECL; on the baseline only, null if it could not be simulated. */
  lossDistribution: CvarResult | null;
}

/** One loan's baseline result in the latest run: a row of the listing behind the totals. */
export interface LoanResult {
  loanId: string;
  state: string;
  exposure: number;
  pd12m: number;
  pdLifetime: number;
  lgd: number;
  ecl12m: number;
  eclLifetime: number;
  eclIfrs9: number;
  stage: number;
  stageReason: string;
  /** How many portfolio loans the row stands for: 1 unless the portfolio was sampled. */
  weight: number;
}

export interface LoanResultPage {
  /** The date of the run the loans belong to; null when there has been none. */
  asOf: string | null;
  total: number;
  loans: LoanResult[];
}

export type LoanResultSort = 'eclLifetime' | 'eclIfrs9' | 'pd12m' | 'exposure' | 'loanId';

export interface LossParameters {
  confidenceLevel?: number;
  numScenarios?: number;
  assetCorrelation?: number;
  seed?: number;
  importanceSampling?: boolean;
}

/** The portfolio's one-year loss distribution, simulated from the loans of its latest run. */
export interface PortfolioLossDistribution {
  asOf: string | null;
  loans: number;
  sampled: boolean;
  result: CvarResult;
}

export interface PortfolioSnapshot {
  asOf: string;
  scenario: string;
  loans: number;
  loansExcluded: number;
  projectedLoans: number;
  exposure: number;
  pd12m: number;
  pdLifetime: number;
  ecl12m: number;
  eclLifetime: number;
  eclIfrs9: number;
  stage1: number;
  stage2: number;
  stage3: number;
  modelVersion: string;
  computedAt: string;
  /** Present on the latest snapshots, absent in the history list. */
  detail: ScenarioRisk | null;
}

export interface PortfolioView {
  snapshots: PortfolioSnapshot[];
  run: PortfolioRun | null;
  /** Loans in the portfolio now, which may no longer be the number the snapshots cover. */
  portfolioLoans: number;
}

// ---- model governance ----------------------------------------------------------------------

export interface ModelEntry {
  id: string;
  name: string;
  purpose: string;
  tier: number;
  validated: boolean;
  version: string;
  artifact: string | null;
  sha256: string | null;
  checksumVerified: boolean | null;
  trainedAt: string | null;
  algorithm: string | null;
  headline: Record<string, number | string>;
  gaps: string[];
}

export interface DriftBin {
  label: string;
  expected: number;
  actual: number;
}

export interface FeatureDrift {
  feature: string;
  psi: number;
  noiseFloor: number;
  status: 'STABLE' | 'MODERATE' | 'SIGNIFICANT' | 'NO_DATA';
  observations: number;
  bins: DriftBin[];
}

export interface DriftReport {
  modelVersion: string;
  loans: number;
  features: FeatureDrift[];
  score: FeatureDrift | null;
  computedAt: string;
}

export interface DecileRow {
  decile: number;
  predicted: number;
  actual: number;
}

export interface SurvivalBacktest {
  monthly: {
    month: string;
    all_vintages: boolean;
    exposure: number;
    predicted_default: number;
    actual_default: number;
    predicted_prepay: number;
    actual_prepay: number;
  }[];
  vintages: {
    orig_year: number;
    n_loans: number;
    ages: number[];
    predicted_default: number[];
    empirical_default: number[];
    predicted_prepay: number[];
    empirical_prepay: number[];
  }[];
  calibration: {
    held_out_window: [string, string];
    held_out: Record<'default' | 'prepay', { deciles: DecileRow[]; top_percentile: DecileRow; actual_over_predicted: number }>;
    out_of_time_window: [string, string];
    out_of_time: Record<'default' | 'prepay', DecileRow[]>;
  };
}

/** A model card as the training script exported it. Snake case: it is the file, served as is. */
export interface ModelCard {
  version: string;
  trained_at: string;
  target?: string;
  population?: string;
  algorithm?: string;
  limitations?: string[];
  artifact_sha256?: string;
  [key: string]: unknown;
}

export interface PdValidation {
  n: number;
  default_rate: number;
  mean_predicted_pd: number;
  auc: number;
  gini: number;
  pr_auc: number;
  ks: number;
  brier: number;
  deciles: { pd_from: number; pd_to: number; n: number; predicted: number; actual: number }[];
}

export interface PdCard extends ModelCard {
  horizon_months: number;
  validation: {
    out_of_time: PdValidation & {
      train_vintages: string;
      test_vintages: string;
      by_vintage: { orig_year: number; n: number; default_rate: number; mean_predicted_pd: number; auc: number; ks: number }[];
    };
    in_time_holdout: PdValidation;
  };
  feature_importance_gain: Record<string, number>;
  /** +1: the score may only rise with the input; -1: only fall. */
  monotone_constraints: Record<string, number>;
}

/** What a drift overlay did when tried out of time: estimated on the first months, judged on the later ones. */
export interface OverlayTrial {
  overlay: number;
  actual_over_predicted_before: number;
  actual_over_predicted_after: number;
  eligible: boolean;
}

/** The overlay in production for one cause, and the evidence it rests on. */
export interface OverlayCause {
  scalar: number;
  applied: boolean;
  actual_over_predicted: number;
  standard_error: number;
  events: number;
  eligible: boolean;
}

export interface SurvivalCard extends ModelCard {
  training: {
    rows: number;
    loans: number;
    months: [string, string];
    months_left_out: string[];
    all_vintages_through: string;
    held_out_loans: number;
    /** Range of each time-varying driver in the training rows (0.5th to 99.5th percentile). */
    envelope: Record<string, [number, number]>;
  };
  validation: {
    out_of_time: {
      rows: number;
      weighted_log_loss: number;
      age_only_baseline_log_loss: number;
      default_auc: number;
      prepay_auc: number;
      test_months: [string, string];
      train_months: [string, string];
    };
    overlay_protocol: {
      estimated_on: [string, string];
      judged_on: [string, string];
      default: OverlayTrial;
      prepay: OverlayTrial;
    };
  };
  overlay: {
    window: [string, string];
    default: OverlayCause;
    prepay: OverlayCause;
    rule: string;
  };
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
  /** When this access token expires. */
  expiresAt: string;
  /** When the session reaches its absolute limit. */
  sessionExpiresAt: string;
  sandbox?: boolean;
}

/** Password change and 2FA changes end every other session and hand the caller a new one. */
export interface SessionRotated {
  message: string;
  session: LoginResponse;
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
export type TotpConfirmOutcome = LoginResponse | SessionRotated;

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
  actor?: string | null;
  httpMethod?: string | null;
  path?: string | null;
  statusCode?: number | null;
  clientIp?: string | null;
  modelVersion?: string | null;
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

/** One entry of a case's immutable history. */
export interface CaseEventView {
  actor: string;
  fromStatus: LoanCaseStatus | null;
  toStatus: LoanCaseStatus;
  fromAssignee: string | null;
  toAssignee: string | null;
  fromFlagged: boolean | null;
  toFlagged: boolean;
  reason: string | null;
  occurredAt: string;
}

export interface LoanCaseView {
  loanId: string;
  status: LoanCaseStatus;
  assignedTo: string | null;
  flagged: boolean;
  updatedAt: string;
  /** Sent back with an update so a change based on a stale view is refused instead of overwriting. */
  version: number;
  escalatedBy: string | null;
  notes: LoanNoteView[];
  history: CaseEventView[];
}

/** One row of GET /loan-cases -- every loan case across the system. */
export interface LoanCaseSummary {
  loanId: string;
  status: LoanCaseStatus;
  assignedTo: string | null;
  flagged: boolean;
  updatedAt: string;
  version: number;
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
  /** SHA-256 of the stored bytes; null for files uploaded before checksums were recorded. */
  sha256: string | null;
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

export interface AccessRequestSubmission {
  companyName: string;
  contactName: string;
  workEmail: string;
  jobTitle: string;
  message: string;
}

/** Activation outcome for roles with mandatory 2FA: no session is issued; the user signs in next. */
export interface ActivationSignInRequired {
  signInRequired: true;
  organization: string;
  username: string;
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
