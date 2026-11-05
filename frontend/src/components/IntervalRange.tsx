import type { Interval, Intervals } from '../api/types';
import { intervalText, methodNote } from './interval';

interface Props {
  label: string;
  interval: Interval;
  intervals: Intervals;
  format: (n: number) => string;
}

/**
 * A figure's range, shown beside its point estimate and never instead of it: the lower and upper bound as text, a
 * track marking where the estimate sits inside them, and a method note that can be opened. The text is the
 * alternative for the track.
 */
export default function IntervalRange({ label, interval, intervals, format }: Props) {
  const span = Math.max(interval.upper - interval.lower, 1e-12);
  const at = ((interval.point - interval.lower) / span) * 100;
  return (
    <div className="interval" data-testid="interval">
      <div className="interval-text">
        <span className="interval-range">{format(interval.lower)} &ndash; {format(interval.upper)}</span>
        <span className="interval-label"> range</span>
      </div>
      <div className="interval-track" role="img" aria-label={intervalText(label, interval, format)}>
        <span className="interval-mark" style={{ left: `${Math.min(100, Math.max(0, at))}%` }} />
      </div>
      <details className="interval-method">
        <summary>How this range is made</summary>
        <p>{methodNote(intervals)}</p>
      </details>
    </div>
  );
}

/** Shown when the delinquency model did not run although delinquency data was supplied or implied. */
export function FallbackNote({ reason }: { reason: string | undefined }) {
  if (!reason) return null;
  return (
    <p className="warn-banner" role="status" data-testid="fallback-note">
      The loan&apos;s delinquency status was not used: {reason}. The projection is the existing model&apos;s, which does not condition on
      whether a loan is already late.
    </p>
  );
}
