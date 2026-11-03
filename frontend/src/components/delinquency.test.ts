import { describe, expect, it } from 'vitest';
import type { LifetimeRiskInput, LoanFeatures } from '../api/types';
import { EMPTY_DRAFT, checkDelinquency, historyFor, startState, withDelinquency } from './delinquency';
import type { DelinquencyDraft } from './delinquency';

const draft = (patch: Partial<DelinquencyDraft>): DelinquencyDraft => ({ ...EMPTY_DRAFT, ...patch });
const loan = { loanId: 'x' } as LoanFeatures;

describe('checkDelinquency', () => {
  it('sends nothing when the form is blank', () => {
    const check = checkDelinquency('', EMPTY_DRAFT);
    expect(check.state).toBe('none');
    expect(check.history).toBeUndefined();
    const input: LifetimeRiskInput = { loan };
    // "Optional fields blank: the request contains none of the new fields"
    expect(Object.keys(withDelinquency(input, check))).toEqual(['loan']);
  });

  it('is ready with a status and a complete history, and sends exactly what was typed', () => {
    const check = checkDelinquency('45', draft({ monthsObserved: '12', episodes: '2', monthsSinceCure: '4', maxStatus: 'D30' }));
    expect(check).toEqual({ state: 'ready', errors: {}, history: { monthsObserved: 12, episodes30Plus: 2, monthsSinceCure: 4, maxStatus12m: 'D30' } });
    expect(withDelinquency({ loan }, check).delinquency).toEqual(check.history);
  });

  it('says incomplete, without an error, when the history is partly filled or the status is missing', () => {
    expect(checkDelinquency('45', draft({ monthsObserved: '6', episodes: '1' })).state).toBe('incomplete');
    const noStatus = checkDelinquency('', draft({ monthsObserved: '6', episodes: '1', maxStatus: 'D30' }));
    expect(noStatus.state).toBe('incomplete');
    expect(noStatus.errors).toEqual({});
  });

  it('refuses impossible history, naming the field, as the API does', () => {
    // 5 spells in the last 3 months
    expect(checkDelinquency('40', draft({ monthsObserved: '3', episodes: '5', maxStatus: 'D30' })).errors.episodes).toMatch(/at least 9 months/);
    expect(checkDelinquency('', draft({ monthsObserved: '13' })).errors.monthsObserved).toBeDefined();
    expect(checkDelinquency('', draft({ monthsObserved: '3', monthsSinceCure: '5' })).errors.monthsSinceCure).toMatch(/3 observed/);
    expect(checkDelinquency('45', draft({ monthsObserved: '6', episodes: '0', maxStatus: 'D30' })).errors.episodes).toBeDefined();
    expect(checkDelinquency('65', draft({ monthsObserved: '6', episodes: '1', maxStatus: 'D30' })).errors.maxStatus).toBeDefined();
    expect(checkDelinquency('0', draft({ monthsObserved: '6', episodes: '1', maxStatus: 'D30' })).errors.monthsSinceCure).toBeDefined();
    expect(checkDelinquency('0', draft({ monthsObserved: '6', episodes: '0', monthsSinceCure: '2', maxStatus: 'CURRENT' })).errors.monthsSinceCure).toBeDefined();
    expect(checkDelinquency('95', draft({ monthsObserved: '12', episodes: '1', maxStatus: 'D60' })).errors.daysPastDue).toMatch(/defaulted/);
  });

  it('accepts a consistent current loan that had a spell and cured', () => {
    expect(checkDelinquency('0', draft({ monthsObserved: '12', episodes: '1', monthsSinceCure: '5', maxStatus: 'D30' })).state).toBe('ready');
  });
});

describe('starting states', () => {
  it('maps days past due to the model states', () => {
    expect([0, 29, 30, 59, 60, 89].map(startState)).toEqual([0, 0, 1, 1, 2, 2]);
    expect(startState(90)).toBeNull();
  });

  it('adjusts a history to be consistent with each status, and never makes it milder', () => {
    const base = { monthsObserved: 12, episodes30Plus: 0, maxStatus12m: 'CURRENT' as const };
    expect(historyFor(0, base)).toEqual(base);
    expect(historyFor(1, base)).toEqual({ ...base, episodes30Plus: 1, maxStatus12m: 'D30' });
    expect(historyFor(2, base)).toEqual({ ...base, episodes30Plus: 1, maxStatus12m: 'D60' });
    const worse = { monthsObserved: 12, episodes30Plus: 3, monthsSinceCure: 2, maxStatus12m: 'D60' as const };
    expect(historyFor(1, worse)).toEqual(worse);
  });
});
