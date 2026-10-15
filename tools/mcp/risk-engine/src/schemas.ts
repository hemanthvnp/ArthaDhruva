import { z } from 'zod';

/**
 * Tool inputs, mirroring the Java request records (score.LoanFeatures, survival.SurvivalController, cvar.CvarRequest).
 * The drift test (test/contract.test.ts) reads those records and fails when a required field appears there
 * that is missing here, or an endpoint a tool maps to disappears.
 */
const code = z.string().min(1).max(8);

export const loan = z.object({
  loanId: z.string().max(80).optional(),
  creditScore: z.number().int().min(300).max(850),
  originalDti: z.number().positive(),
  originalUpb: z.number().positive(),
  originalCltv: z.number().positive(),
  originalLtv: z.number().positive(),
  originalInterestRate: z.number().positive().describe('percent, e.g. 6.5'),
  originalLoanTerm: z.number().int().positive().describe('months'),
  numberOfBorrowers: z.number().int().positive(),
  numberOfUnits: z.number().int().positive(),
  miPercent: z.number().min(0),
  occupancyStatus: code.describe('P primary, S second home, I investor'),
  propertyType: code.describe('SF, CO, PU, MH, CP'),
  loanPurpose: code.describe('P purchase, C cash-out refinance, N no-cash-out refinance'),
  channel: code.describe('R retail, B broker, C correspondent'),
  firstTimeHomebuyerFlag: code.describe('Y or N'),
  propertyState: code.describe('two-letter state code'),
  originationMonth: z.string().regex(/^\d{4}-(0[1-9]|1[0-2])$/).optional().describe('YYYY-MM'),
}).describe('A loan as at origination (the console\'s loan form). get_loan returns one in this shape.');

export type Loan = z.infer<typeof loan>;

export const projectionInputs = {
  monthsOnBook: z.number().int().min(0).max(480).optional().describe('the loan\'s age when originationMonth is not given'),
  currentBalance: z.number().positive().optional(),
  daysPastDue: z.number().int().min(0).max(3650).optional(),
};

export const scenarioName = z.string().min(1).max(30).describe('a built-in scenario name; see list_scenarios');

export const riskProfile = z.object({
  loanId: z.string().max(80).optional(),
  pd: z.number().min(0).max(1),
  lgd: z.number().min(0).max(1),
  ead: z.number().positive(),
});

export const simulation = {
  confidenceLevel: z.number().min(0.5).max(0.9999).optional().describe('default 0.99 for cvar, 0.999 for loss_distribution'),
  numScenarios: z.number().int().min(1000).max(200_000).optional(),
  assetCorrelation: z.number().min(0).max(0.6).optional().describe('default 0.15, the Basel IRB value for residential mortgages'),
  seed: z.number().int().optional().describe('the same seed gives the same result'),
};

/** Every list a tool returns says how many rows it left out. */
export const omitted = z.number().int().min(0);
