import type { Interval, Intervals } from '../api/types';

/** The text a screen reader gets for a figure with a range, and the visible caption: the estimate and both bounds together. */
export function intervalText(label: string, interval: Interval, format: (n: number) => string): string {
  return `${label}: ${format(interval.point)}, range ${format(interval.lower)} to ${format(interval.upper)}`;
}

/** Why the range is what it is: the label the API attaches, and how many draws and vintages it rests on. */
export function methodNote(intervals: Intervals): string {
  if (intervals.draws === null || intervals.reason) {
    return intervals.reason ?? 'No range was computed; the point estimate stands.';
  }
  const parts = [`${intervals.method}.`, `${intervals.draws} regime-chain draws${intervals.capApplied ? ' (the most allowed per request)' : ''},`];
  parts.push(
    intervals.bandVintages
      ? `widened by the spread of predicted over realized incidence across ${intervals.bandVintages} origination-year vintages`
        + (intervals.bandHorizon ? ` at ${intervals.bandHorizon} months.` : '.')
      : 'with no calibration band (too few backtest vintages).',
  );
  parts.push('Only the regime chain is resampled, not the hazard model; this is not a statistical confidence interval.');
  return parts.join(' ');
}
