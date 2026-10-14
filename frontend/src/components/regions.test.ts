import { describe, expect, it } from 'vitest';
import type { Decision, PortfolioRegionSummary } from '../api/types';
import {
  METRIC_LABEL,
  REGION_BADGE,
  REGION_GLYPH,
  REGION_LABEL,
  extrapolationCaution,
  extrapolatedLegend,
  granuleSentence,
  loanCount,
  lossRangeText,
  parseDecisionForm,
  parseRunForm,
  portfolioSplitText,
  rangeSentence,
  regionHeadline,
  regionNotice,
  trackPosition,
} from './regions';

function decision(over: Partial<Decision> = {}): Decision {
  return {
    region: 'BOUNDARY', metric: 'PD_LIFETIME', threshold: 0.05, tolerance: 0, high: 12, members: 32,
    min: 0.041, median: 0.049, max: 0.062, width: 0.021, baseMetric: 0.049, exposure: 250000, eclBase: 4100, eclMin: 3500, eclMax: 5200,
    extrapolated: false, extrapolations: [],
    granuleKind: 'PERTURBATION', shockBand: 0.2, timingBandMonths: 3, scenarios: [], seed: 4242,
    method: 'rough-set three-way decision over a seeded perturbation granule; not a probability and not a coverage-guaranteed interval',
    ...over,
  };
}

function summary(over: Partial<PortfolioRegionSummary> = {}): PortfolioRegionSummary {
  return {
    scenario: 'SEVERELY_ADVERSE',
    parameters: { metric: 'PD_LIFETIME', threshold: 0.05, tolerance: 0, granuleKind: 'PERTURBATION', members: 8, shockBand: 0.2, timingBandMonths: 3, scenarios: [], seed: null },
    loans: 400, excluded: 2, exposure: 100_000_000,
    regions: [
      { region: 'CERTAIN_HIGH', loans: 60, exposure: 18_000_000, loanShare: 0.15, exposureShare: 0.18 },
      { region: 'CERTAIN_LOW', loans: 300, exposure: 70_000_000, loanShare: 0.75, exposureShare: 0.7 },
      { region: 'BOUNDARY', loans: 40, exposure: 12_000_000, loanShare: 0.1, exposureShare: 0.12 },
    ],
    eclLower: 900_000, eclUpper: 1_500_000, eclHighBase: 1_100_000,
    boundaryByState: [{ state: 'FL', loans: 9, exposure: 3_000_000 }],
    method: 'rough-set three-way decision over a seeded perturbation granule; not a probability and not a coverage-guaranteed interval',
    ...over,
  };
}

/** Everything this module can say to a user, for the wording review. */
function allCopy(): string[] {
  const out: string[] = [...Object.values(REGION_LABEL), ...Object.values(METRIC_LABEL)];
  for (const region of ['CERTAIN_HIGH', 'CERTAIN_LOW', 'BOUNDARY'] as const) {
    for (const tolerance of [0, 0.05]) {
      out.push(regionHeadline(decision({ region, tolerance })));
      out.push(regionHeadline(decision({ region, tolerance, granuleKind: 'SCENARIO_SET', scenarios: ['ADVERSE', 'SEVERELY_ADVERSE'], members: 2, high: 1 })));
    }
  }
  out.push(rangeSentence(decision()), granuleSentence(decision()), granuleSentence(decision({ granuleKind: 'SCENARIO_SET', members: 2 })));
  out.push(extrapolationCaution(decision({ extrapolated: true, extrapolations: [{ driver: 'hpi_change_12m', direction: 'below', trainedLimit: -0.01, extreme: -0.13, months: 32, firstMonth: '2026-10' }] })) ?? '');
  out.push(parseDecisionForm({ metric: 'PD_12M', thresholdPct: 'x', tolerancePct: '' }).ok ? '' : (parseDecisionForm({ metric: 'PD_12M', thresholdPct: 'x', tolerancePct: '' }) as { message: string }).message);
  out.push(lossRangeText(summary()), regionNotice(), portfolioSplitText(summary(), 'BOUNDARY'));
  const bad = parseRunForm({ metric: 'PD_12M', thresholdPct: '5', tolerancePct: '', members: '99' });
  out.push(bad.ok ? '' : bad.message);
  return out;
}

describe('region wording', () => {
  it('names the region in words, with a glyph and a badge for each', () => {
    for (const region of ['CERTAIN_HIGH', 'CERTAIN_LOW', 'BOUNDARY'] as const) {
      expect(REGION_LABEL[region].length).toBeGreaterThan(5);
      expect(REGION_GLYPH[region]).toMatch(/\S/);
      expect(REGION_BADGE[region]).toMatch(/^badge-/);
    }
    expect(new Set(Object.values(REGION_GLYPH)).size).toBe(3);
    expect(new Set(Object.values(REGION_LABEL)).size).toBe(3);
  });

  it('never talks in confidence, probability of being right, or guarantees', () => {
    for (const text of allCopy()) {
      expect(text).not.toMatch(/confiden|probab|guarant|certainty is|sure that|likely/i);
    }
  });

  it('says how many variants were high for a boundary loan', () => {
    const text = regionHeadline(decision());
    expect(text).toContain('12 of 32');
    expect(text).toContain('5.00%');
    expect(text.toLowerCase()).toContain('review');
  });

  it('reports a decided loan against the whole granule, and the tolerance when one was allowed', () => {
    expect(regionHeadline(decision({ region: 'CERTAIN_HIGH', high: 32 }))).toContain('32 of 32');
    expect(regionHeadline(decision({ region: 'CERTAIN_LOW', high: 0 }))).toContain('32 of 32');
    expect(regionHeadline(decision({ region: 'CERTAIN_HIGH', high: 31, tolerance: 0.05 }))).toContain('within the allowed 5%');
  });

  it('describes a scenario-set decision by the scenarios, not by perturbations', () => {
    const text = regionHeadline(decision({ region: 'CERTAIN_HIGH', granuleKind: 'SCENARIO_SET', scenarios: ['ADVERSE', 'SEVERELY_ADVERSE'], members: 2, high: 2 }));
    expect(text).toContain('every listed scenario');
    expect(text).toContain('ADVERSE, SEVERELY_ADVERSE');
    expect(text).not.toContain('variants of this stress');
  });

  it('states the neighbourhood and the seed so the region is never read without them', () => {
    const text = granuleSentence(decision());
    expect(text).toContain('±20%');
    expect(text).toContain('±3 months');
    expect(text).toContain('4242');
  });

  it('gives the spread of the metric', () => {
    expect(rangeSentence(decision())).toContain('4.10% to 6.20%');
  });
});

describe('extrapolation caution', () => {
  it('is absent when nothing the scenario moves left the trained range', () => {
    expect(extrapolationCaution(decision())).toBeNull();
  });

  it('names the drivers and warns that a narrow boundary is not evidence of certainty', () => {
    const text = extrapolationCaution(decision({
      extrapolated: true,
      extrapolations: [
        { driver: 'hpi_change_12m', direction: 'below', trainedLimit: -0.01, extreme: -0.13, months: 32, firstMonth: '2026-10' },
        { driver: 'hpi_change_12m', direction: 'below', trainedLimit: -0.01, extreme: -0.12, months: 20, firstMonth: '2026-10' },
      ],
    }));
    expect(text).toContain('hpi change 12m');
    expect(text?.match(/hpi change 12m/g)).toHaveLength(1);
    expect(text).toContain('not evidence of certainty');
  });
});

describe('decision form', () => {
  it('turns percentages into fractions', () => {
    expect(parseDecisionForm({ metric: 'PD_LIFETIME', thresholdPct: '5', tolerancePct: '10' })).toEqual({
      ok: true, request: { metric: 'PD_LIFETIME', threshold: 0.05, tolerance: 0.1 },
    });
  });

  it('treats an empty allowance as strict', () => {
    const parsed = parseDecisionForm({ metric: 'PD_12M', thresholdPct: '2.5', tolerancePct: '' });
    expect(parsed.ok && parsed.request.tolerance).toBe(0);
  });

  it('names the field that is wrong', () => {
    expect(parseDecisionForm({ metric: 'PD_12M', thresholdPct: '', tolerancePct: '' })).toMatchObject({ ok: false, field: 'threshold' });
    expect(parseDecisionForm({ metric: 'PD_12M', thresholdPct: '101', tolerancePct: '' })).toMatchObject({ ok: false, field: 'threshold' });
    expect(parseDecisionForm({ metric: 'PD_12M', thresholdPct: '-1', tolerancePct: '' })).toMatchObject({ ok: false, field: 'threshold' });
    expect(parseDecisionForm({ metric: 'PD_12M', thresholdPct: '5', tolerancePct: '50' })).toMatchObject({ ok: false, field: 'tolerance' });
    expect(parseDecisionForm({ metric: 'PD_12M', thresholdPct: '5', tolerancePct: 'abc' })).toMatchObject({ ok: false, field: 'tolerance' });
  });
});

describe('track position', () => {
  it('places values on the track and clamps outside it', () => {
    expect(trackPosition(5, 0, 10)).toBe(50);
    expect(trackPosition(-1, 0, 10)).toBe(0);
    expect(trackPosition(11, 0, 10)).toBe(100);
  });

  it('centres a degenerate track', () => {
    expect(trackPosition(3, 3, 3)).toBe(50);
  });
});

describe('portfolio region text', () => {
  it('writes a count of loans in the singular when there is one', () => {
    expect(loanCount(1)).toBe('1 loan');
    expect(loanCount(0)).toBe('0 loans');
    expect(loanCount(1234)).toBe('1,234 loans');
    expect(portfolioSplitText(summary({ regions: [{ region: 'BOUNDARY', loans: 1, exposure: 1, loanShare: 0.1, exposureShare: 0.1 }] }), 'BOUNDARY')).toContain('1 loan,');
  });

  it('explains the asterisk in the loan list', () => {
    expect(extrapolatedLegend()).toContain('beyond the data the model was trained on');
  });

  it('states a region as a count and a share of exposure', () => {
    expect(portfolioSplitText(summary(), 'BOUNDARY')).toBe('40 loans, 12.0% of exposure');
    expect(portfolioSplitText(summary({ regions: [] }), 'BOUNDARY')).toBe('');
  });

  it('gives the loss range between the two approximations, with the unperturbed figure', () => {
    const text = lossRangeText(summary());
    expect(text).toContain('$900K');
    expect(text).toContain('$1.50M');
    expect(text).toContain('$1.10M');
    expect(text).toContain('boundary loans');
  });

  it('warns where to look for stress that goes beyond the trained data', () => {
    expect(regionNotice()).toContain('not evidence of certainty');
  });
});

describe('portfolio run form', () => {
  it('adds the number of variants when one is given', () => {
    expect(parseRunForm({ metric: 'PD_LIFETIME', thresholdPct: '5', tolerancePct: '', members: '16' })).toEqual({
      ok: true, request: { metric: 'PD_LIFETIME', threshold: 0.05, tolerance: 0, granule: { members: 16 } },
    });
  });

  it('leaves the server default when the number of variants is empty', () => {
    const parsed = parseRunForm({ metric: 'PD_LIFETIME', thresholdPct: '5', tolerancePct: '', members: '' });
    expect(parsed.ok && parsed.request.granule).toBeUndefined();
  });

  it('names the field that is wrong, in the same order the form shows them', () => {
    expect(parseRunForm({ metric: 'PD_12M', thresholdPct: '', tolerancePct: '', members: '99' })).toMatchObject({ ok: false, field: 'threshold' });
    expect(parseRunForm({ metric: 'PD_12M', thresholdPct: '5', tolerancePct: '60', members: '8' })).toMatchObject({ ok: false, field: 'tolerance' });
    for (const members of ['0', '33', '2.5', 'abc']) {
      expect(parseRunForm({ metric: 'PD_12M', thresholdPct: '5', tolerancePct: '', members })).toMatchObject({ ok: false, field: 'members' });
    }
  });
});
