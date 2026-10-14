import { useState } from 'react';
import type { DecisionMetric, DecisionRegion, DecisionRequest, PortfolioRegionSummary } from '../api/types';
import { compactMoney, pct } from '../format';
import { scenarioLabel } from './chartTheme';
import {
  METRIC_LABEL,
  REGION_BADGE,
  REGION_GLYPH,
  REGION_LABEL,
  loanCount,
  regionNotice,
  lossRangeText,
  parseRunForm,
  portfolioSplitText,
} from './regions';

const ORDER: DecisionRegion[] = ['CERTAIN_HIGH', 'BOUNDARY', 'CERTAIN_LOW'];

/**
 * The split of the book into the three regions, the loss range it implies, and the way into the boundary
 * loans. A region is a robustness classification, not a probability.
 */
export function PortfolioRegions({ summary, onShowRegion }: { summary: PortfolioRegionSummary; onShowRegion: (region: DecisionRegion) => void }) {
  const p = summary.parameters;
  return (
    <div className="card">
      <div className="card-head"><h3>Which loans are on the boundary</h3></div>
      <p className="page-subtitle">
        Every loan was judged under {scenarioLabel(summary.scenario)}: is its {METRIC_LABEL[p.metric].toLowerCase()} at or above{' '}
        {pct(p.threshold)} under every variant of the stress, under none, or under some?
      </p>
      <div className="mix" role="img" aria-label={ORDER.map((r) => `${REGION_LABEL[r]}: ${portfolioSplitText(summary, r)}`).join('; ')}>
        {ORDER.map((r) => {
          const share = summary.regions.find((x) => x.region === r)?.exposure ?? 0;
          return share > 0 ? <span key={r} className={r === 'CERTAIN_HIGH' ? 'high' : r === 'BOUNDARY' ? 'mid' : 'low'} style={{ flexGrow: share }} /> : null;
        })}
      </div>
      <ul className="list" style={{ marginTop: '0.75rem' }}>
        {ORDER.map((r) => (
          <li key={r} className="list-row">
            <span className={`badge badge-region ${REGION_BADGE[r]}`}><span aria-hidden="true">{REGION_GLYPH[r]}</span>&nbsp;{REGION_LABEL[r]}</span>
            <span className="grow sub">{portfolioSplitText(summary, r)}</span>
            {(summary.regions.find((x) => x.region === r)?.loans ?? 0) > 0 && (
              <button className="secondary" onClick={() => onShowRegion(r)}>List loans</button>
            )}
          </li>
        ))}
      </ul>
      <p className="page-subtitle" style={{ marginTop: '0.75rem' }}>{lossRangeText(summary)}</p>
      {summary.boundaryByState.length > 0 && (
        <p className="page-subtitle">
          Boundary exposure is largest in{' '}
          {summary.boundaryByState.slice(0, 4).map((s) => `${s.state} (${compactMoney(s.exposure)}, ${loanCount(s.loans)})`).join(', ')}.
        </p>
      )}
      <p className="chart-caption">
        {p.granuleKind === 'SCENARIO_SET' ? `${p.members} listed scenarios.` : `${p.members} variants per loan: shock sizes within ±${(p.shockBand * 100).toFixed(0)}% and timing within ±${p.timingBandMonths} months.`}
        {' '}{regionNotice()} Method: {summary.method}.
      </p>
    </div>
  );
}

/** The optional fields that make a portfolio run also judge every loan. */
export function RunWithRegions({ busy, onRun }: { busy: boolean; onRun: (decision: DecisionRequest) => void }) {
  const [metric, setMetric] = useState<DecisionMetric>('PD_LIFETIME');
  const [thresholdPct, setThresholdPct] = useState('');
  const [tolerancePct, setTolerancePct] = useState('');
  const [members, setMembers] = useState('');
  const [problem, setProblem] = useState<{ field: string; message: string } | null>(null);

  const run = () => {
    const parsed = parseRunForm({ metric, thresholdPct, tolerancePct, members });
    if (!parsed.ok) {
      setProblem({ field: parsed.field, message: parsed.message });
      return;
    }
    setProblem(null);
    onRun(parsed.request);
  };
  const error = (field: string) => problem?.field === field && <div className="error-banner" role="alert">{problem.message}</div>;

  return (
    <div className="card">
      <div className="card-head"><h3>Run with region analysis</h3></div>
      <p className="page-subtitle">
        Also judge every loan: is its stressed figure high under every variant of the harshest scenario, under none, or on the
        boundary? It projects each loan several more times, so it takes longer than a plain run.
      </p>
      <div className="field-grid">
        <div className="field">
          <label htmlFor="pr-metric">Figure</label>
          <select id="pr-metric" value={metric} onChange={(e) => setMetric(e.target.value as DecisionMetric)}>
            {(Object.keys(METRIC_LABEL) as DecisionMetric[]).map((m) => <option key={m} value={m}>{METRIC_LABEL[m]}</option>)}
          </select>
        </div>
        <div className="field">
          <label htmlFor="pr-threshold">High at or above (%)</label>
          <input id="pr-threshold" type="number" min={0} max={100} step={0.1} value={thresholdPct} onChange={(e) => setThresholdPct(e.target.value)} placeholder="e.g. 5" aria-invalid={problem?.field === 'threshold'} />
          {error('threshold')}
        </div>
        <div className="field">
          <label htmlFor="pr-tolerance">Allow exceptions (%)</label>
          <input id="pr-tolerance" type="number" min={0} max={49.9} step={1} value={tolerancePct} onChange={(e) => setTolerancePct(e.target.value)} placeholder="0 = every variant" aria-invalid={problem?.field === 'tolerance'} />
          {error('tolerance')}
        </div>
        <div className="field">
          <label htmlFor="pr-members">Variants per loan</label>
          <input id="pr-members" type="number" min={1} max={32} step={1} value={members} onChange={(e) => setMembers(e.target.value)} placeholder="8" aria-invalid={problem?.field === 'members'} />
          {error('members')}
        </div>
      </div>
      <div className="actions">
        <button onClick={run} disabled={busy}>{busy ? 'Running...' : 'Run with regions'}</button>
      </div>
    </div>
  );
}
