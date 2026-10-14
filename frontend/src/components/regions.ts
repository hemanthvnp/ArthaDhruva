import type { Decision, DecisionMetric, DecisionRegion, DecisionRequest, PortfolioRegionSummary } from '../api/types';
import { compactMoney, count, pct } from '../format';

/**
 * The words and the parsing behind the region view, kept apart from the layout so they can be tested.
 *
 * A region is a robustness classification: whether the answer holds across variants of the stress. It is
 * not a probability and it carries no coverage guarantee, so nothing here uses the vocabulary of
 * confidence, and the method label from the server is always shown beside it.
 */

export const REGION_LABEL: Record<DecisionRegion, string> = {
  CERTAIN_HIGH: 'High under every variant',
  CERTAIN_LOW: 'Low under every variant',
  BOUNDARY: 'Depends on the stress details',
};

/** A glyph and a word, so the region never rests on colour alone. */
export const REGION_GLYPH: Record<DecisionRegion, string> = { CERTAIN_HIGH: '▲', CERTAIN_LOW: '▼', BOUNDARY: '◆' };

export const REGION_BADGE: Record<DecisionRegion, string> = {
  CERTAIN_HIGH: 'badge-high',
  CERTAIN_LOW: 'badge-low',
  BOUNDARY: 'badge-medium',
};

export const METRIC_LABEL: Record<DecisionMetric, string> = {
  PD_12M: '12-month default',
  PD_LIFETIME: 'Lifetime default',
  ECL_LIFETIME_RATE: 'Lifetime loss rate',
};

/** The sentence under the badge: what was tested and how many variants agreed. */
export function regionHeadline(d: Decision): string {
  const metric = METRIC_LABEL[d.metric].toLowerCase();
  const threshold = pct(d.threshold);
  if (d.granuleKind === 'SCENARIO_SET') {
    const set = d.scenarios.join(', ');
    switch (d.region) {
      case 'CERTAIN_HIGH': return `The ${metric} is at or above ${threshold} under every listed scenario (${set}).`;
      case 'CERTAIN_LOW': return `The ${metric} is below ${threshold} under every listed scenario (${set}).`;
      default: return `The ${metric} is at or above ${threshold} under ${d.high} of ${d.members} listed scenarios (${set}).`;
    }
  }
  switch (d.region) {
    case 'CERTAIN_HIGH': return `The ${metric} is at or above ${threshold} in ${d.high} of ${d.members} variants of this stress${d.tolerance > 0 ? `, within the allowed ${pct(d.tolerance, 0)} of exceptions` : ''}.`;
    case 'CERTAIN_LOW': return `The ${metric} is below ${threshold} in ${d.members - d.high} of ${d.members} variants of this stress${d.tolerance > 0 ? `, within the allowed ${pct(d.tolerance, 0)} of exceptions` : ''}.`;
    default: return `The ${metric} is at or above ${threshold} in ${d.high} of ${d.members} variants of this stress, so the answer turns on the details of the stress. Review this loan.`;
  }
}

/** Where the variants land, in words. */
export function rangeSentence(d: Decision): string {
  return `Across the variants the ${METRIC_LABEL[d.metric].toLowerCase()} runs from ${pct(d.min)} to ${pct(d.max)} (median ${pct(d.median)}, unperturbed ${pct(d.baseMetric)}).`;
}

/** How the variants were drawn, so the region is never read without its neighbourhood. */
export function granuleSentence(d: Decision): string {
  if (d.granuleKind === 'SCENARIO_SET') return `${d.members} listed scenarios.`;
  return `${d.members} variants of the scenario: shock sizes within ±${(d.shockBand * 100).toFixed(0)}% and timing within ±${d.timingBandMonths} months. Same inputs and seed ${d.seed} reproduce this exactly.`;
}

/** Shown whenever a driver the scenario moves leaves the range the model was trained on. */
export function extrapolationCaution(d: Decision): string | null {
  if (!d.extrapolated) return null;
  const drivers = Array.from(new Set(d.extrapolations.map((e) => e.driver.replaceAll('_', ' ')))).join(', ');
  return `Part of this stress goes beyond the data the model was trained on (${drivers}), where a tree model holds its response at the edge of what it has seen. A narrow boundary here is not evidence of certainty.`;
}

export type DecisionForm = { metric: DecisionMetric; thresholdPct: string; tolerancePct: string };

export type ParsedForm = { ok: true; request: DecisionRequest } | { ok: false; field: 'threshold' | 'tolerance'; message: string };

/** Turns the form's percentages into a request, or says which field is wrong. */
export function parseDecisionForm(form: DecisionForm): ParsedForm {
  const threshold = Number(form.thresholdPct);
  if (form.thresholdPct.trim() === '' || !Number.isFinite(threshold) || threshold < 0 || threshold > 100) {
    return { ok: false, field: 'threshold', message: 'Enter a threshold between 0 and 100 percent.' };
  }
  const toleranceText = form.tolerancePct.trim();
  const tolerance = toleranceText === '' ? 0 : Number(toleranceText);
  if (!Number.isFinite(tolerance) || tolerance < 0 || tolerance >= 50) {
    return { ok: false, field: 'tolerance', message: 'Enter an exception allowance of at least 0 and below 50 percent.' };
  }
  return { ok: true, request: { metric: form.metric, threshold: threshold / 100, tolerance: tolerance / 100 } };
}

/** Position of a value on a track that runs from `lo` to `hi`, as a percentage clamped to the track. */
export function trackPosition(value: number, lo: number, hi: number): number {
  const span = hi - lo;
  if (span <= 0) return 50;
  return Math.max(0, Math.min(100, ((value - lo) / span) * 100));
}

// ---- portfolio -------------------------------------------------------------------------------

/** "1 loan", "23 loans". */
export function loanCount(n: number): string {
  return `${count(n)} loan${n === 1 ? '' : 's'}`;
}

/** One sentence per region for the split of the book. */
export function portfolioSplitText(s: PortfolioRegionSummary, region: DecisionRegion): string {
  const r = s.regions.find((x) => x.region === region);
  if (!r) return '';
  return `${loanCount(r.loans)}, ${pct(r.exposureShare, 1)} of exposure`;
}

/** The legend for the asterisk the loan list puts beside a region. */
export function extrapolatedLegend(): string {
  return '* marks a loan whose stress goes beyond the data the model was trained on.';
}

/** What the high-risk part of the book could carry, as a range between its two approximations. */
export function lossRangeText(s: PortfolioRegionSummary): string {
  return `The loans that are high carry between ${compactMoney(s.eclLower)} and ${compactMoney(s.eclUpper)} of lifetime expected loss: `
    + `the lower figure counts only loans that are high under every variant, each at its lowest, and the upper adds the boundary loans, `
    + `each at its highest. Under the unperturbed scenario they carry ${compactMoney(s.eclHighBase)}.`;
}

export type RunForm = DecisionForm & { members: string };

/** The region-analysis fields of a portfolio run, or the field that is wrong. */
export function parseRunForm(form: RunForm): ParsedForm | { ok: false; field: 'members'; message: string } {
  const base = parseDecisionForm(form);
  if (!base.ok) return base;
  const text = form.members.trim();
  const members = text === '' ? undefined : Number(text);
  if (members !== undefined && (!Number.isInteger(members) || members < 1 || members > 32)) {
    return { ok: false, field: 'members', message: 'Enter a whole number of variants from 1 to 32.' };
  }
  return { ok: true, request: members === undefined ? base.request : { ...base.request, granule: { members } } };
}

/** Under the portfolio split: where to look for loans whose stress goes beyond the trained data. */
export function regionNotice(): string {
  return 'Loans whose stress goes beyond the data the model was trained on are marked in the loan list; a narrow boundary for them is not evidence of certainty.';
}
