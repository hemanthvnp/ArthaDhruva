import { useState } from 'react';
import { getCachedScore, score } from '../api/client';
import LoanFeaturesForm, { DEFAULT_LOAN } from '../components/LoanFeaturesForm';
import ErrorBanner from '../components/ErrorBanner';
import type { ScoreResponse, CachedScore } from '../api/types';

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
      <h2>Default Risk Score</h2>
      <p className="page-subtitle">
        PD (probability of default) from the 16-field LightGBM model, with the isotonic
        calibration correction applied. If a Loan ID is supplied, the result is cached in Redis
        and can be looked up below without recomputing.
      </p>

      <div className="card">
        <LoanFeaturesForm value={loan} onChange={setLoan} showLoanId />
        <div className="actions">
          <button onClick={submit} disabled={loading}>
            {loading ? 'Scoring...' : 'Score loan'}
          </button>
        </div>
        <ErrorBanner error={error} />
        {result && (
          <div className="result-grid">
            <div className="stat">
              <div className="label">Raw probability</div>
              <div className="value">{(result.rawProbability * 100).toFixed(2)}%</div>
            </div>
            <div className="stat">
              <div className="label">Calibrated probability</div>
              <div className="value">{(result.calibratedProbability * 100).toFixed(3)}%</div>
            </div>
          </div>
        )}
        {result && (
          <p className="warn-banner">
            The raw LightGBM output overpredicts default probability by ~17.5x on average
            (scale_pos_weight distorts probabilities while still improving ranking) -- use the
            calibrated probability for any dollar-valued decision.
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
        {cacheMiss && <p className="page-subtitle">No cached score found for this Loan ID yet.</p>}
        {cached && (
          <div className="result-grid">
            <div className="stat">
              <div className="label">Calibrated probability</div>
              <div className="value">{(cached.score.calibratedProbability * 100).toFixed(3)}%</div>
            </div>
            <div className="stat">
              <div className="label">Computed at</div>
              <div className="value" style={{ fontSize: '0.95rem' }}>
                {new Date(cached.computedAt).toLocaleString()}
              </div>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
