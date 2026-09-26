import { useState } from 'react';
import { getCachedScore, score } from '../api/client';
import LoanFeaturesForm, { DEFAULT_LOAN } from '../components/LoanFeaturesForm';
import ErrorBanner from '../components/ErrorBanner';
import RiskBadge from '../components/RiskBadge';
import RiskMeter from '../components/RiskMeter';
import type { ScoreResponse, CachedScore } from '../api/types';

/** Diverging bar per factor, scaled to the largest effect so the biggest driver fills its half. */
function Factors({ items }: { items: NonNullable<ScoreResponse['explanation']> }) {
  const max = Math.max(...items.map((a) => Math.abs(a.contribution)), 1e-9);
  return (
    <div role="table" aria-label="Factor contributions">
      {items.map((a) => {
        const up = a.contribution > 0;
        const width = `${(Math.abs(a.contribution) / max) * 50}%`;
        return (
          <div className="factor-row" role="row" key={a.feature}>
            <span role="cell">{a.feature}</span>
            <span className="factor-track" role="cell" aria-hidden="true">
              <span className={`factor-bar ${up ? 'up' : 'down'}`} style={{ width }} />
            </span>
            <span role="cell" className={`factor-val ${up ? 'up' : 'down'}`}>
              {up ? '+' : '−'}{Math.abs(a.contribution * 100).toFixed(3)} pts
            </span>
          </div>
        );
      })}
    </div>
  );
}

export default function ScorePage() {
  const [loan, setLoan] = useState(DEFAULT_LOAN);
  const [result, setResult] = useState<ScoreResponse | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [loading, setLoading] = useState(false);

  const [lookupId, setLookupId] = useState('');
  const [cached, setCached] = useState<CachedScore | null>(null);
  const [cacheError, setCacheError] = useState<unknown>(null);
  const [cacheMiss, setCacheMiss] = useState(false);

  const submit = async () => {
    setLoading(true);
    setError(null);
    setResult(null);
    try {
      setResult(await score(loan));
    } catch (e) {
      setError(e);
    } finally {
      setLoading(false);
    }
  };

  const lookup = async () => {
    setCacheError(null);
    setCached(null);
    setCacheMiss(false);
    try {
      const found = await getCachedScore(lookupId);
      if (found) setCached(found);
      else setCacheMiss(true);
    } catch (e) {
      setCacheError(e);
    }
  };

  return (
    <div>
      <div className="page-head-row">
        <div>
          <h2>Default risk score</h2>
          <p className="page-subtitle">
            Probability of default from the LightGBM model, corrected by isotonic calibration. Give the loan an ID and the
            result is cached, so you can look it up below without recomputing.
          </p>
        </div>
      </div>

      <div className="card">
        <LoanFeaturesForm value={loan} onChange={setLoan} showLoanId />
        <div className="actions">
          <button onClick={submit} disabled={loading}>
            {loading ? 'Scoring...' : 'Score loan'}
          </button>
        </div>
        <ErrorBanner error={error} />

        {result && (
          <div className="hero-score" role="status">
            <div>
              <div className="meta">Calibrated probability of default</div>
              <div className="big">{(result.calibratedProbability * 100).toFixed(2)}%</div>
            </div>
            <RiskBadge probability={result.calibratedProbability} />
            <div className="secondary-stat">
              <div className="label">Raw model output</div>
              <div className="value">{(result.rawProbability * 100).toFixed(2)}%</div>
            </div>
            <div style={{ flexBasis: '100%' }}><RiskMeter probability={result.calibratedProbability} scale /></div>
          </div>
        )}

        {result?.explanation && result.explanation.length > 0 && (
          <div style={{ marginTop: '1.5rem' }}>
            <h3>What moved this score</h3>
            <p className="page-subtitle">
              Each factor's effect versus a typical loan. Red raises risk, green lowers it. This is a single-factor
              estimate and does not capture interactions between factors.
            </p>
            <Factors items={result.explanation} />
          </div>
        )}

        {result && (
          <p className="warn-banner">
            The raw model output overpredicts default probability by about 17.5x on average, because class weighting
            distorts probabilities while still improving ranking. Use the calibrated probability for any dollar-valued
            decision.
          </p>
        )}
      </div>

      <div className="card">
        <h3>Look up a cached score</h3>
        <div className="row-inline">
          <div className="field">
            <label htmlFor="lookupId">Loan ID</label>
            <input id="lookupId" value={lookupId} onChange={(e) => setLookupId(e.target.value)} />
          </div>
          <button className="secondary" onClick={lookup} disabled={!lookupId}>
            Look up
          </button>
        </div>
        <ErrorBanner error={cacheError} />
        {cacheMiss && <p className="empty">No cached score found for this Loan ID yet.</p>}
        {cached && (
          <div className="hero-score">
            <div>
              <div className="meta">Calibrated probability of default</div>
              <div className="big">{(cached.score.calibratedProbability * 100).toFixed(2)}%</div>
            </div>
            <RiskBadge probability={cached.score.calibratedProbability} />
            <div className="secondary-stat">
              <div className="label">Computed</div>
              <div className="value" style={{ fontSize: '0.95rem' }}>{new Date(cached.computedAt).toLocaleString()}</div>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
