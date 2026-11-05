import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import type { Intervals, TermStructureResponse } from '../api/types';
import IntervalRange, { FallbackNote } from './IntervalRange';
import { comparisonRows } from './delinquency';
import { intervalText, methodNote } from './interval';

const intervals: Intervals = {
  method: 'regime uncertainty plus historical calibration error',
  draws: 200,
  resampled: 'regime chain only (the hazard model\'s own parameters are not resampled)',
  bandSource: 'survival_backtest.json, 9 origination-year vintages',
  bandSigma: 0.21,
  bandVintages: 9,
  bandHorizon: 36,
  capApplied: false,
  reason: null,
  pd12m: null,
  pdLifetime: { lower: 0.031, point: 0.042, upper: 0.064 },
  ecl12m: null,
  eclLifetime: null,
};
const pct = (n: number) => `${(n * 100).toFixed(1)}%`;

describe('IntervalRange', () => {
  it('shows the point estimate with both bounds, never the range alone', () => {
    const interval = intervals.pdLifetime!;
    const html = renderToStaticMarkup(createElement(IntervalRange, { label: 'Default over its life', interval, intervals, format: pct }));
    expect(html).toContain('3.1% &ndash; 6.4%'.replace('&ndash;', '–'));
    // the text alternative for the track carries the estimate and both bounds
    expect(html).toContain(intervalText('Default over its life', interval, pct));
    expect(intervalText('Default over its life', interval, pct)).toBe('Default over its life: 4.2%, range 3.1% to 6.4%');
    expect(html).toContain('role="img"');
  });

  it('puts the method note beside every range: the label, the draws and the vintages', () => {
    const html = renderToStaticMarkup(createElement(IntervalRange, { label: 'x', interval: intervals.pdLifetime!, intervals, format: pct }));
    expect(html).toContain('<details');
    const note = methodNote(intervals);
    expect(note).toContain('regime uncertainty plus historical calibration error');
    expect(note).toContain('200 regime-chain draws');
    expect(note).toContain('9 origination-year vintages');
    expect(note).toMatch(/not a statistical confidence interval/);
    expect(html).toContain('regime uncertainty plus historical calibration error');
  });

  it('says so when the draw cap applied, and explains a missing interval instead of inventing one', () => {
    expect(methodNote({ ...intervals, capApplied: true })).toContain('the most allowed per request');
    expect(methodNote({ ...intervals, draws: null, reason: 'the interval time budget of 2000 ms was exceeded' })).toBe(
      'the interval time budget of 2000 ms was exceeded',
    );
  });
});

describe('FallbackNote', () => {
  it('states that the status was not used, and why', () => {
    const html = renderToStaticMarkup(createElement(FallbackNote, { reason: 'history incomplete, delinquency model not used' }));
    expect(html).toContain('delinquency status was not used');
    expect(html).toContain('history incomplete, delinquency model not used');
    expect(html).toContain('role="status"');
  });

  it('renders nothing when there is no reason', () => {
    expect(renderToStaticMarkup(createElement(FallbackNote, { reason: undefined }))).toBe('');
  });
});

describe('the comparison of starting statuses', () => {
  const response = (n: number) => ({ modelUsed: `delinquency@${n}` }) as unknown as TermStructureResponse;

  it('lists current, 30 and 60 days past due in that order, with a row for an answer that has not arrived', () => {
    const rows = comparisonRows([response(0), undefined, response(2)]);
    expect(rows.map((r) => r.label)).toEqual(['Current', '30 days past due', '60 days past due']);
    expect(rows.map((r) => r.state)).toEqual([0, 1, 2]);
    expect(rows[1].response).toBeUndefined();
    expect(rows[2].response?.modelUsed).toBe('delinquency@2');
  });
});
