// Mirrors the Java DTOs in backend/risk-engine exactly -- field names are a direct mapping,
// since Jackson already serializes the record component names as camelCase (verified against
// live responses during backend development, e.g. calibratedProbability, valueAtRiskConfidenceInterval).

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

export interface ScoreResponse {
  rawProbability: number;
  calibratedProbability: number;
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

export interface SegmentNeighbor {
  state: string;
  hops: number;
}

/** Shape of the {field: message} validation-error body every endpoint returns on 400. */
export interface ValidationErrorBody {
  error: string;
  fields: Record<string, string>;
}

export interface LoginResponse {
  token: string;
  username: string;
  role: 'ANALYST' | 'ADMIN';
  expiresAt: string;
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
