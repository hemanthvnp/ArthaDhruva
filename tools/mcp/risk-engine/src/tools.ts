import { defineTool, markMutating, ToolError, type AnyTool } from '@arthadhruva/mcp-kit';
import { z } from 'zod';
import type { RiskApi } from './http.js';
import { loan, omitted, projectionInputs, riskProfile, scenarioName, simulation } from './schemas.js';

/**
 * The risk API as tools. Every tool maps to one /v1 endpoint (CONTRACT below, which the drift test holds to
 * the Java controllers) and returns a summary bounded in size, with any rows left out counted. Figures are
 * passed through as the API computed them: no tool rounds, rescales or recomputes a number.
 */
export interface Endpoint {
  method: 'GET' | 'POST';
  /** Path as the controller maps it, with {placeholders}. */
  path: string;
  /** Simple name of the Java request record, when the body is one. */
  request?: string;
  /** Simple name of the Java record the response is, and the fields of it the tool reads. */
  response?: string;
  reads?: readonly string[];
}

export const CONTRACT: Record<string, Endpoint> = {
  get_loan: { method: 'GET', path: '/loans/{loanId}', response: 'LoanFeatures', reads: ['loanId', 'creditScore', 'propertyState', 'originationMonth'] },
  list_scenarios: { method: 'GET', path: '/risk/scenarios', response: 'Scenario', reads: ['name', 'description', 'stressedMonths', 'unemploymentShockPp'] },
  portfolio_summary: { method: 'GET', path: '/risk/portfolio', response: 'PortfolioView', reads: ['snapshots', 'run', 'portfolioLoans'] },
  portfolio_loans: { method: 'GET', path: '/risk/portfolio/loans', response: 'LoanPage', reads: ['asOf', 'total', 'loans'] },
  regime_forecast: { method: 'GET', path: '/regime-forecast', response: 'RegimeForecast', reads: ['asOfMonth', 'forecastMonth', 'monthsAhead', 'regimeProbabilities', 'currentRegime', 'expectedDurationMonths', 'dataAgeMonths'] },
  list_models: { method: 'GET', path: '/models', response: 'Entry', reads: ['id', 'name', 'purpose', 'tier', 'validated', 'version', 'sha256', 'checksumVerified', 'gaps'] },
  model_detail: { method: 'GET', path: '/models/{id}' },
  score_loan: { method: 'POST', path: '/score', request: 'LoanFeatures', response: 'ScoreResponse', reads: ['calibratedProbability', 'reasonCodes', 'warnings', 'modelVersion', 'horizonMonths'] },
  explain_loan: { method: 'POST', path: '/score', request: 'LoanFeatures', response: 'ScoreResponse', reads: ['calibratedProbability', 'baselineProbability', 'explanation', 'reasonCodes', 'modelVersion'] },
  term_structure: { method: 'POST', path: '/risk/term-structure', request: 'TermStructureRequest', response: 'TermStructureResponse', reads: ['termStructure', 'warnings'] },
  compare_term_structure: { method: 'POST', path: '/risk/term-structure/compare', request: 'CompareRequest', response: 'CompareResponse', reads: ['modelVersion', 'monthsOnBook', 'remainingMonths', 'scenarios', 'warnings', 'weighted'] },
  cvar: { method: 'POST', path: '/cvar', request: 'CvarRequest', response: 'CvarResult', reads: ['valueAtRisk', 'conditionalValueAtRisk', 'expectedLoss', 'unexpectedLoss', 'totalExposure', 'numLoans', 'numScenarios'] },
  expected_loss: { method: 'POST', path: '/expected-loss', request: 'LoanFeatures', response: 'ExpectedLossResponse', reads: ['pd', 'lgd', 'ead', 'expectedLoss', 'pdLifetime', 'expectedLossLifetime', 'stage', 'eclIfrs9', 'modelVersion', 'warnings'] },
  loss_distribution: { method: 'POST', path: '/risk/portfolio/loss-distribution', request: 'LossRequest', response: 'LossDistribution', reads: ['asOf', 'loans', 'sampled', 'result'] },
  portfolio_run_start: { method: 'POST', path: '/risk/portfolio/runs', request: 'RunRequest', response: 'Run', reads: ['id', 'status', 'scenarios', 'loansTotal', 'loansDone', 'attempt'] },
  portfolio_run_status: { method: 'GET', path: '/risk/portfolio/runs/{id}', response: 'Run', reads: ['id', 'status', 'scenarios', 'loansTotal', 'loansDone', 'error', 'attempt', 'startedAt', 'finishedAt'] },
};

export interface Limits {
  /** Cap on rows any list tool returns. */
  maxRows: number;
}

const READ = { readOnlyHint: true, destructiveHint: false, idempotentHint: true, openWorldHint: false } as const;
/** A computation over the API: it changes no data, but it is audited, metered and rate-limited. */
const COMPUTE = { readOnlyHint: true, destructiveHint: false, idempotentHint: true, openWorldHint: false } as const;

const pct = (p: number) => `${(p * 100).toFixed(p < 0.001 ? 4 : 2)}%`;
const money = (n: number) => `$${Math.round(n).toLocaleString('en-US')}`;
const call = (tool: string, args: unknown) => `${tool}:${JSON.stringify(args)}`;

const runShape = {
  id: z.string(), status: z.string(), scenarios: z.array(z.string()), loansTotal: z.number(), loansDone: z.number(),
  attempt: z.number(), error: z.string().nullable().optional(), startedAt: z.string().nullable().optional(),
  finishedAt: z.string().nullable().optional(), requestedAt: z.string().nullable().optional(),
};
type ApiRun = { id: string; status: string; scenarios: string[]; loansTotal: number; loansDone: number; attempt: number; error?: string | null; startedAt?: string | null; finishedAt?: string | null; requestedAt?: string | null };
const runData = (r: ApiRun) => ({
  id: r.id, status: r.status, scenarios: r.scenarios, loansTotal: r.loansTotal, loansDone: r.loansDone, attempt: r.attempt,
  error: r.error ?? null, startedAt: r.startedAt ?? null, finishedAt: r.finishedAt ?? null, requestedAt: r.requestedAt ?? null,
});
const runText = (r: ApiRun) => `Run ${r.id}: ${r.status}, ${r.loansDone} of ${r.loansTotal} loans, scenarios ${r.scenarios.join(', ')}, attempt ${r.attempt}${r.error ? `; error: ${r.error}` : ''}.`;

const summary = z.object({ pd12m: z.number(), pd24m: z.number(), pdLifetime: z.number(), prepayLifetime: z.number(), expectedLifeMonths: z.number(), maturityProbability: z.number() });
const ecl = z.object({ stage: z.number(), stageReason: z.string().nullable(), exposure: z.number(), lgd: z.number(), ecl12m: z.number(), eclLifetime: z.number(), eclIfrs9: z.number(), eclCecl: z.number() }).passthrough();
const extrapolation = z.object({ driver: z.string(), direction: z.string(), trainedLimit: z.number(), extreme: z.number(), months: z.number(), firstMonth: z.string().nullable() });

export function tools(rawApi: RiskApi, limits: Limits): AnyTool[] {
  const api = markMutating(rawApi, ['startRun']);   // every other call is a read or a pure computation
  const cap = (n: number | undefined) => Math.min(n ?? limits.maxRows, limits.maxRows);

  return [
    defineTool({
      name: 'get_loan', title: 'Look up a loan',
      description: 'A loan in the organization\'s portfolio (or the demo catalog) by id, in the shape the scoring tools take.',
      input: { loanId: z.string().min(1).max(80) },
      output: { loan: loan.passthrough() },
      annotations: READ, uses: ['get'],
      handler: async ({ loanId }) => {
        const l = await api.get<z.infer<typeof loan>>(`/loans/${encodeURIComponent(loanId)}`);
        return { text: `Loan ${l.loanId ?? loanId}: credit score ${l.creditScore}, LTV ${l.originalLtv}, DTI ${l.originalDti}, ${l.propertyState}, originated ${l.originationMonth ?? 'unknown'}.`, data: { loan: l } };
      },
    }),
    defineTool({
      name: 'list_scenarios', title: 'Built-in scenarios',
      description: 'The macro scenarios term_structure, compare_term_structure and portfolio runs accept, with their shocks.',
      input: {},
      output: { scenarios: z.array(z.object({ name: z.string(), description: z.string().nullable() }).passthrough()) },
      annotations: READ, uses: ['get'],
      handler: async () => {
        const scenarios = await api.get<Array<{ name: string; description: string | null }>>('/risk/scenarios');
        return { text: scenarios.map((s) => `${s.name}: ${s.description ?? ''}`).join('\n'), data: { scenarios } };
      },
    }),
    defineTool({
      name: 'portfolio_summary', title: 'Portfolio allowance',
      description: 'The latest portfolio run\'s headline figures per scenario (exposure, PD, 12-month and lifetime ECL, IFRS 9 '
        + 'allowance, stage counts) and the latest run\'s state.',
      input: {},
      output: {
        portfolioLoans: z.number(),
        run: z.object(runShape).nullable(),
        snapshots: z.array(z.object({
          scenario: z.string(), asOf: z.string(), loans: z.number(), exposure: z.number(), pd12m: z.number(), pdLifetime: z.number(),
          ecl12m: z.number(), eclLifetime: z.number(), eclIfrs9: z.number(), stage1: z.number(), stage2: z.number(), stage3: z.number(), modelVersion: z.string().nullable(),
        })),
      },
      annotations: READ, uses: ['get'],
      handler: async () => {
        const v = await api.get<{ portfolioLoans: number; run: ApiRun | null; snapshots: Array<Record<string, unknown>> }>('/risk/portfolio');
        const snapshots = v.snapshots.map((s) => ({
          scenario: String(s.scenario), asOf: String(s.asOf), loans: Number(s.loans), exposure: Number(s.exposure), pd12m: Number(s.pd12m),
          pdLifetime: Number(s.pdLifetime), ecl12m: Number(s.ecl12m), eclLifetime: Number(s.eclLifetime), eclIfrs9: Number(s.eclIfrs9),
          stage1: Number(s.stage1), stage2: Number(s.stage2), stage3: Number(s.stage3), modelVersion: (s.modelVersion as string | null) ?? null,
        }));
        const text = snapshots.length
          ? snapshots.map((s) => `${s.scenario} (as of ${s.asOf}, ${s.loans} loans): exposure ${money(s.exposure)}, IFRS 9 allowance ${money(s.eclIfrs9)}, lifetime ECL ${money(s.eclLifetime)}, 12-month PD ${pct(s.pd12m)}, stages ${s.stage1}/${s.stage2}/${s.stage3}.`).join('\n')
          : 'No portfolio run has finished yet.';
        return { text: `${text}${v.run ? `\nLatest ${runText(v.run)}` : ''}`, data: { portfolioLoans: v.portfolioLoans, run: v.run ? runData(v.run) : null, snapshots } };
      },
    }),
    defineTool({
      name: 'portfolio_loans', title: 'Loans in the latest run',
      description: `Loan-by-loan results of the latest run, ordered by lifetime ECL by default; filter by stage or region. At most ${limits.maxRows} rows; the result says how many more there are.`,
      input: {
        stage: z.number().int().min(1).max(3).optional(),
        region: z.enum(['CERTAIN_HIGH', 'CERTAIN_LOW', 'BOUNDARY']).optional(),
        sort: z.enum(['eclLifetime', 'eclIfrs9', 'pd12m', 'exposure', 'loanId']).default('eclLifetime'),
        limit: z.number().int().min(1).max(500).optional(),
        offset: z.number().int().min(0).default(0),
      },
      output: {
        asOf: z.string().nullable(), total: z.number(), omitted,
        loans: z.array(z.object({ loanId: z.string(), state: z.string().nullable(), exposure: z.number(), pd12m: z.number(), pdLifetime: z.number(), eclLifetime: z.number(), eclIfrs9: z.number(), stage: z.number() })),
      },
      annotations: READ, uses: ['get'],
      handler: async ({ stage, region, sort, limit, offset }) => {
        const n = cap(limit);
        const page = await api.get<{ asOf: string | null; total: number; loans: Array<Record<string, unknown>> }>('/risk/portfolio/loans', { stage, region, sort, limit: n, offset });
        const loans = page.loans.slice(0, n).map((l) => ({
          loanId: String(l.loanId), state: (l.state as string | null) ?? null, exposure: Number(l.exposure), pd12m: Number(l.pd12m),
          pdLifetime: Number(l.pdLifetime), eclLifetime: Number(l.eclLifetime), eclIfrs9: Number(l.eclIfrs9), stage: Number(l.stage),
        }));
        const left = Math.max(0, page.total - offset - loans.length);
        const text = [`${page.total} loans match (as of ${page.asOf ?? 'no run yet'}); showing ${loans.length} from ${offset}${left ? `, ${left} more rows` : ''}.`,
          ...loans.map((l) => `${l.loanId} ${l.state ?? ''} stage ${l.stage}: lifetime ECL ${money(l.eclLifetime)}, 12-month PD ${pct(l.pd12m)}, exposure ${money(l.exposure)}`)].join('\n');
        return { text, data: { asOf: page.asOf, total: page.total, omitted: left, loans } };
      },
    }),
    defineTool({
      name: 'regime_forecast', title: 'Macro regime forecast',
      description: 'Probability of the calm and stressed macro regimes some months ahead, from the regime chain the survival model uses.',
      input: { monthsAhead: z.number().int().min(0).max(60).default(6) },
      output: {
        asOfMonth: z.string(), forecastMonth: z.string(), monthsAhead: z.number(), currentRegime: z.string(),
        regimeProbabilities: z.record(z.string(), z.number()), expectedDurationMonths: z.record(z.string(), z.number()), dataAgeMonths: z.number(),
      },
      annotations: READ, uses: ['get'],
      handler: async ({ monthsAhead }) => {
        const f = await api.get<{ asOfMonth: string; forecastMonth: string; monthsAhead: number; currentRegime: string; regimeProbabilities: Record<string, number>; expectedDurationMonths: Record<string, number>; dataAgeMonths: number }>('/regime-forecast', { monthsAhead });
        const data = { asOfMonth: f.asOfMonth, forecastMonth: f.forecastMonth, monthsAhead: f.monthsAhead, currentRegime: f.currentRegime, regimeProbabilities: f.regimeProbabilities, expectedDurationMonths: f.expectedDurationMonths, dataAgeMonths: f.dataAgeMonths };
        return { text: `Regime now (data as of ${f.asOfMonth}): ${f.currentRegime}. In ${f.forecastMonth}: ${Object.entries(f.regimeProbabilities).map(([k, v]) => `${k} ${pct(v)}`).join(', ')}.`, data };
      },
    }),
    defineTool({
      name: 'list_models', title: 'Model inventory',
      description: 'Every model behind the numbers, with tier, version, artifact checksum, whether it is validated and what is missing if not.',
      input: {},
      output: { models: z.array(z.object({ id: z.string(), name: z.string(), tier: z.number(), validated: z.boolean(), version: z.string().nullable(), sha256: z.string().nullable(), checksumVerified: z.boolean().nullable(), gaps: z.array(z.string()) })) },
      annotations: READ, uses: ['get'],
      handler: async () => {
        const entries = await api.get<Array<Record<string, unknown>>>('/models');
        const models = entries.map((m) => ({ id: String(m.id), name: String(m.name), tier: Number(m.tier), validated: Boolean(m.validated), version: (m.version as string | null) ?? null, sha256: (m.sha256 as string | null) ?? null, checksumVerified: (m.checksumVerified as boolean | null) ?? null, gaps: (m.gaps as string[] | null) ?? [] }));
        return { text: models.map((m) => `${m.id} (${m.name}, tier ${m.tier}): ${m.validated ? 'validated' : 'not validated'}, version ${m.version ?? 'n/a'}${m.gaps.length ? `; gaps: ${m.gaps.join('; ')}` : ''}`).join('\n'), data: { models } };
      },
    }),
    defineTool({
      name: 'model_detail', title: 'Model card',
      description: 'The full model card of a validated model (pd_24m, survival, trajectory_lstm): training window, validation, limitations, checksum.',
      input: { id: z.string().min(1).max(40) },
      output: { id: z.string(), card: z.record(z.string(), z.unknown()) },
      annotations: READ, uses: ['get'],
      handler: async ({ id }) => {
        const card = await api.get<Record<string, unknown>>(`/models/${encodeURIComponent(id)}`);
        const limitations = Array.isArray(card.limitations) ? (card.limitations as unknown[]).slice(0, 8).map(String) : [];
        return { text: [`Model card ${id}, version ${String(card.version ?? 'n/a')}, artifact SHA-256 ${String(card.artifact_sha256 ?? 'n/a')}.`, ...limitations.map((l) => `- ${l}`)].join('\n'), data: { id, card } };
      },
    }),
    defineTool({
      name: 'score_loan', title: 'Score a loan',
      description: 'Probability of default within 24 months of origination for a loan, with the model version, adverse-action reason codes and any warnings (POST /v1/score).',
      input: { loan },
      output: { calibratedProbability: z.number(), horizonMonths: z.number(), modelVersion: z.string(), reasonCodes: z.array(z.record(z.string(), z.unknown())), warnings: z.array(z.string()) },
      annotations: COMPUTE, uses: ['post'],
      handler: async (args) => {
        const { data: s } = await api.post<{ calibratedProbability: number; horizonMonths: number; modelVersion: string; reasonCodes: Array<Record<string, unknown>>; warnings: string[] }>('/score', args.loan, call('score_loan', args));
        const data = { calibratedProbability: s.calibratedProbability, horizonMonths: s.horizonMonths, modelVersion: s.modelVersion, reasonCodes: s.reasonCodes ?? [], warnings: s.warnings ?? [] };
        return { text: `${s.horizonMonths}-month PD ${pct(s.calibratedProbability)} (${s.modelVersion}).${data.reasonCodes.length ? ` Reasons: ${data.reasonCodes.map((r) => String(r.description ?? r.feature ?? r.code)).join('; ')}.` : ''}${data.warnings.length ? ` Warnings: ${data.warnings.join('; ')}` : ''}`, data };
      },
    }),
    defineTool({
      name: 'explain_loan', title: 'Explain a score',
      description: 'The Shapley attribution of a loan\'s PD: each feature\'s contribution against the reference loan, adding up exactly to the score. '
        + 'The API returns it with the score (POST /v1/score), so this costs one scoring call.',
      input: { loan, top: z.number().int().min(1).max(30).default(10) },
      output: { calibratedProbability: z.number(), baselineProbability: z.number().nullable(), modelVersion: z.string(), contributions: z.array(z.object({ feature: z.string(), contribution: z.number() })), omitted },
      annotations: COMPUTE, uses: ['post'],
      handler: async (args) => {
        const { data: s } = await api.post<{ calibratedProbability: number; baselineProbability: number | null; modelVersion: string; explanation: Array<{ feature: string; contribution: number }> }>('/score', args.loan, call('explain_loan', args));
        const sorted = [...(s.explanation ?? [])].sort((a, b) => Math.abs(b.contribution) - Math.abs(a.contribution));
        const contributions = sorted.slice(0, args.top);
        const left = sorted.length - contributions.length;
        const text = [`PD ${pct(s.calibratedProbability)} against a reference of ${s.baselineProbability === null ? 'n/a' : pct(s.baselineProbability)} (${s.modelVersion}). Largest contributions:`,
          ...contributions.map((c) => `  ${c.feature}: ${c.contribution >= 0 ? '+' : ''}${(c.contribution * 100).toFixed(3)} pp`), ...(left ? [`  (${left} more rows, each smaller)`] : [])].join('\n');
        return { text, data: { calibratedProbability: s.calibratedProbability, baselineProbability: s.baselineProbability ?? null, modelVersion: s.modelVersion, contributions, omitted: left } };
      },
    }),
    defineTool({
      name: 'term_structure', title: 'Lifetime projection',
      description: 'One loan month by month to maturity under a scenario: lifetime PD and prepayment, IFRS 9 stage and allowance, CECL, '
        + 'and where the scenario leaves the model\'s training range. Returns the summary and yearly points, not every month.',
      input: { loan, scenario: scenarioName.optional(), ...projectionInputs },
      output: { scenario: z.string(), modelVersion: z.string(), monthsOnBook: z.number(), remainingMonths: z.number(), summary, ecl, extrapolations: z.array(extrapolation), yearly: z.array(z.record(z.string(), z.unknown())), warnings: z.array(z.string()) },
      annotations: COMPUTE, uses: ['post'],
      handler: async (args) => {
        const { loan: l, ...rest } = args;
        const { data: r } = await api.post<{ termStructure: { scenario: string; modelVersion: string; monthsOnBook: number; remainingMonths: number; summary: z.infer<typeof summary>; ecl: z.infer<typeof ecl>; months: Array<Record<string, unknown>>; extrapolations: Array<z.infer<typeof extrapolation>> }; warnings: string[] }>('/risk/term-structure', { loan: l, ...rest }, call('term_structure', args));
        const t = r.termStructure;
        const yearly = t.months.filter((_, i) => (i + 1) % 12 === 0).slice(0, 40);
        const data = { scenario: t.scenario, modelVersion: t.modelVersion, monthsOnBook: t.monthsOnBook, remainingMonths: t.remainingMonths, summary: t.summary, ecl: t.ecl, extrapolations: t.extrapolations ?? [], yearly, warnings: r.warnings ?? [] };
        const text = `${t.scenario}: lifetime PD ${pct(t.summary.pdLifetime)}, 12-month PD ${pct(t.summary.pd12m)}, lifetime prepayment ${pct(t.summary.prepayLifetime)}, expected life ${t.summary.expectedLifeMonths.toFixed(1)} months; stage ${t.ecl.stage}, IFRS 9 allowance ${money(t.ecl.eclIfrs9)}, CECL ${money(t.ecl.eclCecl)} (${t.modelVersion}).`
          + (data.extrapolations.length ? ` Outside training range: ${data.extrapolations.map((e) => `${e.driver} ${e.direction} for ${e.months} months`).join('; ')}.` : '');
        return { text, data };
      },
    }),
    defineTool({
      name: 'compare_term_structure', title: 'Compare scenarios',
      description: 'One loan under several scenarios side by side; with weights (probabilities summing to 1) also the probability-weighted allowance.',
      input: { loan, scenarios: z.array(scenarioName).min(1).max(5).optional(), weights: z.record(z.string(), z.number().min(0)).optional(), ...projectionInputs },
      output: { modelVersion: z.string(), scenarios: z.array(z.object({ scenario: z.string(), summary, ecl })), weighted: z.record(z.string(), z.unknown()).nullable(), warnings: z.array(z.string()) },
      annotations: COMPUTE, uses: ['post'],
      handler: async (args) => {
        const { data: r } = await api.post<{ modelVersion: string; scenarios: Array<{ scenario: string; summary: z.infer<typeof summary>; ecl: z.infer<typeof ecl> }>; weighted?: Record<string, unknown>; warnings: string[] }>('/risk/term-structure/compare', args, call('compare_term_structure', args));
        const data = { modelVersion: r.modelVersion, scenarios: r.scenarios.map((s) => ({ scenario: s.scenario, summary: s.summary, ecl: s.ecl })), weighted: r.weighted ?? null, warnings: r.warnings ?? [] };
        const text = [...data.scenarios.map((s) => `${s.scenario}: lifetime PD ${pct(s.summary.pdLifetime)}, stage ${s.ecl.stage}, IFRS 9 ${money(s.ecl.eclIfrs9)}`),
          ...(data.weighted ? [`Probability-weighted IFRS 9 allowance ${money(Number(data.weighted.eclIfrs9))}.`] : [])].join('\n');
        return { text, data };
      },
    }),
    defineTool({
      name: 'cvar', title: 'Loss distribution of given loans',
      description: 'Value at risk and expected shortfall of a set of loans given their PD, LGD and exposure (one-factor Gaussian copula, seeded).',
      input: { loans: z.array(riskProfile).min(1).max(10_000), ...simulation },
      output: { valueAtRisk: z.number(), conditionalValueAtRisk: z.number(), expectedLoss: z.number(), unexpectedLoss: z.number(), totalExposure: z.number(), numLoans: z.number(), numScenarios: z.number() },
      annotations: COMPUTE, uses: ['post'],
      handler: async (args) => {
        const { data: r } = await api.post<Record<string, number>>('/cvar', { ...args, contributions: false }, call('cvar', args));
        const data = { valueAtRisk: r.valueAtRisk!, conditionalValueAtRisk: r.conditionalValueAtRisk!, expectedLoss: r.expectedLoss!, unexpectedLoss: r.unexpectedLoss!, totalExposure: r.totalExposure!, numLoans: r.numLoans!, numScenarios: r.numScenarios! };
        return { text: `VaR ${money(data.valueAtRisk)}, expected shortfall ${money(data.conditionalValueAtRisk)}, expected loss ${money(data.expectedLoss)} on ${money(data.totalExposure)} across ${data.numLoans} loans (${data.numScenarios} scenarios).`, data };
      },
    }),
    defineTool({
      name: 'expected_loss', title: 'Expected loss of a loan',
      description: 'PD x LGD x EAD for one loan, 12-month and lifetime, with its IFRS 9 stage.',
      input: { loan },
      output: { pd: z.number(), lgd: z.number(), ead: z.number(), expectedLoss: z.number(), pdLifetime: z.number(), expectedLossLifetime: z.number(), stage: z.number(), eclIfrs9: z.number(), modelVersion: z.string(), warnings: z.array(z.string()) },
      annotations: COMPUTE, uses: ['post'],
      handler: async (args) => {
        const { data: r } = await api.post<{ pd: number; lgd: number; ead: number; expectedLoss: number; pdLifetime: number; expectedLossLifetime: number; stage: number; eclIfrs9: number; modelVersion: string; warnings: string[] }>('/expected-loss', args.loan, call('expected_loss', args));
        const data = { ...r, warnings: r.warnings ?? [] };
        return { text: `12-month expected loss ${money(r.expectedLoss)} (PD ${pct(r.pd)} x LGD ${pct(r.lgd)} x EAD ${money(r.ead)}); lifetime ${money(r.expectedLossLifetime)}; stage ${r.stage}, IFRS 9 ${money(r.eclIfrs9)}.`, data };
      },
    }),
    defineTool({
      name: 'loss_distribution', title: 'Portfolio loss distribution',
      description: 'The one-year loss distribution of the organization\'s portfolio as of its latest run: VaR, expected shortfall, expected loss.',
      input: { ...simulation, importanceSampling: z.boolean().optional() },
      output: { asOf: z.string(), loans: z.number(), sampled: z.boolean(), valueAtRisk: z.number(), conditionalValueAtRisk: z.number(), expectedLoss: z.number(), totalExposure: z.number() },
      annotations: COMPUTE, uses: ['post'],
      handler: async (args) => {
        const { data: r } = await api.post<{ asOf: string; loans: number; sampled: boolean; result: Record<string, number> }>('/risk/portfolio/loss-distribution', args, call('loss_distribution', args));
        const data = { asOf: r.asOf, loans: r.loans, sampled: r.sampled, valueAtRisk: r.result.valueAtRisk!, conditionalValueAtRisk: r.result.conditionalValueAtRisk!, expectedLoss: r.result.expectedLoss!, totalExposure: r.result.totalExposure! };
        return { text: `As of ${r.asOf} (${r.loans} loans${r.sampled ? ', a sample' : ''}): VaR ${money(data.valueAtRisk)}, expected shortfall ${money(data.conditionalValueAtRisk)}, expected loss ${money(data.expectedLoss)}.`, data };
      },
    }),
    defineTool({
      name: 'portfolio_run_start', title: 'Start a portfolio run',
      description: 'Starts projecting every loan in the portfolio (the baseline is always included). Returns the run at once; poll '
        + 'portfolio_run_status. One run per organization at a time: if one is active, the result is a Conflict carrying its id. '
        + 'Consumes plan allowance and compute; do not call it unless the user asked for a new run.',
      input: { scenarios: z.array(scenarioName).min(1).max(5).optional(), defer: z.boolean().optional().describe('wait for an idle engine instead of competing with interactive runs') },
      output: runShape,
      annotations: { readOnlyHint: false, destructiveHint: false, idempotentHint: false, openWorldHint: false },
      uses: ['startRun'],
      handler: async (args) => {
        try {
          const { data: r } = await api.startRun<ApiRun>(args, call('portfolio_run_start', args));
          return { text: `Started. ${runText(r)} Poll portfolio_run_status with this id.`, data: runData(r) };
        } catch (e) {
          if (e instanceof ToolError && e.code === 'Conflict' && typeof e.details?.id === 'string') {
            throw new ToolError('Conflict', `A run is already active for this organization: ${String(e.details.id)} (${String(e.details.status)}). Poll it instead.`, { details: { runId: e.details.id, status: e.details.status } });
          }
          throw e;
        }
      },
    }),
    defineTool({
      name: 'portfolio_run_status', title: 'Portfolio run status',
      description: 'The state of a run: QUEUED, RUNNING, DONE or FAILED (a run whose worker died is retried under a new attempt, then failed with the reason).',
      input: { runId: z.string().uuid() },
      output: runShape,
      annotations: READ, uses: ['get'],
      handler: async ({ runId }) => {
        const r = await api.get<ApiRun>(`/risk/portfolio/runs/${runId}`);
        return { text: runText(r), data: runData(r) };
      },
    }),
  ];
}
