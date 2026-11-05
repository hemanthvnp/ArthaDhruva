import { describe, expect, it } from 'vitest';
import type { RealizedReportBody } from '../api/types';
import { realizedVerdict, unresolvedLine } from './realized';

const base: RealizedReportBody = {
  asOf: '2026-11-04', computedOn: 'risk-compute-1', horizonMonths: 24, loansScored: 212, outcomesRecorded: 215, matchedToAScore: 212,
  resolved: 200, defaults: 20, unresolvedDefaultedOrPrepaidBeforeScored: 5, unresolvedNotObservedThroughHorizon: 7,
  meanPredicted: 0.068, realizedRate: 0.1, predictedOverRealized: 0.68, auc: 1, deciles: [], note: null, method: 'm', caveat: 'c',
};

describe('realizedVerdict', () => {
  it('says which side of what happened the predictions are, with the numbers it rests on', () => {
    const lines = realizedVerdict(base);
    expect(lines[0]).toContain('200 resolved loans');
    expect(lines[0]).toContain('6.80%');
    expect(lines[0]).toContain('10.00% defaulted');
    expect(lines[0]).toContain('0.68');
    expect(lines[0]).toContain('below what happened');
    expect(lines[1]).toContain('AUC 1.000');
  });

  it('calls a ratio within ten percent close, and one above above', () => {
    expect(realizedVerdict({ ...base, predictedOverRealized: 1.05 })[0]).toContain('close to what happened');
    expect(realizedVerdict({ ...base, predictedOverRealized: 1.39 })[0]).toContain('above what happened');
  });

  it('does not invent a ratio when nothing defaulted, or a verdict when nothing resolved', () => {
    const none = realizedVerdict({ ...base, defaults: 0, realizedRate: 0, predictedOverRealized: null, auc: null });
    expect(none[0]).toContain('none defaulted');
    expect(none[0]).not.toContain('predicted over realized');
    expect(realizedVerdict({ ...base, resolved: 0 })).toEqual(['No stored score has an outcome that resolves it yet.']);
  });

  it('carries the report\'s own note about what it cannot say', () => {
    expect(realizedVerdict({ ...base, note: 'Fewer than 100 resolved loans: no deciles.' })).toContain('Fewer than 100 resolved loans: no deciles.');
  });
});

describe('unresolvedLine', () => {
  it('counts what was left out, and why', () => {
    const line = unresolvedLine(base)!;
    expect(line).toContain('5 defaulted or prepaid before they were scored');
    expect(line).toContain('7 not yet observed through the 24-month horizon');
    expect(line).toContain('3 recorded for loans with no stored score');
    expect(unresolvedLine({ ...base, outcomesRecorded: 218 })).toContain('6 recorded for loans with no stored score');
  });

  it('is absent when nothing was left out', () => {
    expect(unresolvedLine({ ...base, unresolvedDefaultedOrPrepaidBeforeScored: 0, unresolvedNotObservedThroughHorizon: 0, outcomesRecorded: 212 })).toBeNull();
  });
});
