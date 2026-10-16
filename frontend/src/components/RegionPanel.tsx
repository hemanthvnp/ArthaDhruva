import { useState } from 'react';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { termStructure } from '../api/client';
import type { Decision, DecisionMetric, DecisionRequest, LifetimeRiskInput } from '../api/types';
import { pct } from '../format';
import { scenarioLabel } from './chartTheme';
import ErrorBanner from './ErrorBanner';
import {
  METRIC_LABEL,
  REGION_BADGE,
  REGION_GLYPH,
  REGION_LABEL,
  extrapolationCaution,
  granuleSentence,
  parseDecisionForm,
  rangeSentence,
  regionHeadline,
  trackPosition,
} from './regions';

/** Where the variants land against the threshold: a range, the median, the unperturbed figure and the line. */
function Track({ d }: { d: Decision }) {
  const lo = Math.min(d.min, d.threshold);
  const hi = Math.max(d.max, d.threshold);
  const pad = Math.max((hi - lo) * 0.15, 1e-6);
  const from = lo - pad;
  const to = hi + pad;
  const at = (v: number) => `${trackPosition(v, from, to)}%`;
  const summary = `Variants run from ${pct(d.min)} to ${pct(d.max)}; the threshold is ${pct(d.threshold)}.`;
  return (
    <div role="img" aria-label={summary} style={{ margin: '1rem 0 0.4rem' }}>
      <div style={{ position: 'relative', height: 12, background: 'var(--surface-2)', borderRadius: 6 }}>
        <div style={{ position: 'absolute', left: at(d.min), width: `calc(${at(d.max)} - ${at(d.min)})`, minWidth: 3, height: '100%', background: 'var(--text)', opacity: 0.28, borderRadius: 6 }} />
        <div title={`Median ${pct(d.median)}`} style={{ position: 'absolute', left: `calc(${at(d.median)} - 1px)`, top: 1, width: 2, height: 10, background: 'var(--text)' }} />
        <div title={`Unperturbed ${pct(d.baseMetric)}`} style={{ position: 'absolute', left: `calc(${at(d.baseMetric)} - 4px)`, top: 2, width: 8, height: 8, borderRadius: '50%', background: 'var(--surface)', border: '2px solid var(--text)', boxSizing: 'border-box' }} />
        <div title={`Threshold ${pct(d.threshold)}`} style={{ position: 'absolute', left: `calc(${at(d.threshold)} - 1px)`, top: -6, width: 2, height: 24, background: 'var(--sig-high)' }} />
      </div>
      <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: '0.72rem', color: 'var(--text-muted)', marginTop: '0.35rem' }}>
        <span>lowest variant {pct(d.min)}</span>
        <span>threshold {pct(d.threshold)}</span>
        <span>highest variant {pct(d.max)}</span>
      </div>
    </div>
  );
}

function Result({ d }: { d: Decision }) {
  const caution = extrapolationCaution(d);
  return (
    <div style={{ marginTop: '1rem' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: '0.6rem', flexWrap: 'wrap' }}>
        <span className={`badge badge-region ${REGION_BADGE[d.region]}`}><span aria-hidden="true">{REGION_GLYPH[d.region]}</span>&nbsp;{REGION_LABEL[d.region]}</span>
        <span style={{ color: 'var(--text-muted)', fontSize: '0.85rem' }}>{d.high} of {d.members} high</span>
      </div>
      <p className="page-subtitle" style={{ marginTop: '0.6rem' }}>{regionHeadline(d)}</p>
      <Track d={d} />
      <p className="page-subtitle">{rangeSentence(d)}</p>
      {caution && <div className="warn-banner" role="status" style={{ marginTop: '0.75rem' }}>{caution}</div>}
      <p className="chart-caption">{granuleSentence(d)} Method: {d.method}.</p>
    </div>
  );
}

/**
 * Asks whether this loan's stressed figure is high across variants of the selected scenario, and shows
 * the answer as one of three regions. A region says whether the answer is robust to the details of the
 * stress; it is not a probability.
 */
export default function RegionPanel({ input, scenario }: { input: LifetimeRiskInput; scenario: string }) {
  const [metric, setMetric] = useState<DecisionMetric>('PD_LIFETIME');
  const [thresholdPct, setThresholdPct] = useState('');
  const [tolerancePct, setTolerancePct] = useState('');
  const [fieldError, setFieldError] = useState<{ field: 'threshold' | 'tolerance'; message: string } | null>(null);
  const [applied, setApplied] = useState<DecisionRequest | null>(null);

  const key = JSON.stringify({ input, scenario, applied });
  const query = useQuery({
    queryKey: ['decision', key],
    // only the decision is wanted: one month of the series keeps the response small
    queryFn: () => termStructure({ ...input, months: 1, decision: applied! }, scenario),
    enabled: applied !== null,
    retry: false,
    placeholderData: keepPreviousData,
  });

  const check = () => {
    const parsed = parseDecisionForm({ metric, thresholdPct, tolerancePct });
    if (!parsed.ok) {
      setFieldError({ field: parsed.field, message: parsed.message });
      return;
    }
    setFieldError(null);
    setApplied(parsed.request);
  };

  const decision = applied ? query.data?.decision : undefined;

  return (
    <div className="card">
      <div className="card-head"><h3>Is the answer robust to the stress?</h3></div>
      <p className="page-subtitle">
        Pick a figure and a threshold. The stress is varied around {scenarioLabel(scenario)} and each variant is
        projected, so you can see whether the loan is high under every variant, low under every variant, or on the
        boundary where the answer turns on the details. Boundary loans are the ones to review.
      </p>
      <div className="field-grid">
        <div className="field">
          <label htmlFor="region-metric">Figure</label>
          <select id="region-metric" value={metric} onChange={(e) => setMetric(e.target.value as DecisionMetric)}>
            {(Object.keys(METRIC_LABEL) as DecisionMetric[]).map((m) => <option key={m} value={m}>{METRIC_LABEL[m]}</option>)}
          </select>
        </div>
        <div className="field">
          <label htmlFor="region-threshold">High at or above (%)</label>
          <input id="region-threshold" type="number" min={0} max={100} step={0.1} value={thresholdPct} onChange={(e) => setThresholdPct(e.target.value)} placeholder="e.g. 5" aria-invalid={fieldError?.field === 'threshold'} />
          {fieldError?.field === 'threshold' && <div className="error-banner" role="alert">{fieldError.message}</div>}
        </div>
        <div className="field">
          <label htmlFor="region-tolerance">Allow exceptions (%)</label>
          <input id="region-tolerance" type="number" min={0} max={49.9} step={1} value={tolerancePct} onChange={(e) => setTolerancePct(e.target.value)} placeholder="0 = every variant" aria-invalid={fieldError?.field === 'tolerance'} />
          {fieldError?.field === 'tolerance' && <div className="error-banner" role="alert">{fieldError.message}</div>}
        </div>
      </div>
      <div className="actions">
        <button onClick={check} disabled={query.isFetching}>{query.isFetching ? 'Testing variants...' : 'Test robustness'}</button>
      </div>
      <ErrorBanner error={query.error} />
      {query.isFetching && !decision && <div className="skeleton" style={{ height: 120, marginTop: '1rem' }} />}
      {decision && <Result d={decision} />}
    </div>
  );
}
