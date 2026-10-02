import { useState } from 'react';
import { getCachedScore, score } from '../api/client';
import LoanFeaturesForm from '../components/LoanFeaturesForm';
import { DEFAULT_LOAN } from '../components/loanDefaults';
import ErrorBanner from '../components/ErrorBanner';
import RiskBadge from '../components/RiskBadge';
import RiskMeter from '../components/RiskMeter';
import { dateTime, pct } from '../format';
import { featureLabel } from '../labels';
import type { ScoreResponse, CachedScore } from '../api/types';

/** Diverging bar per factor, scaled to the largest effect so the biggest driver fills its half. */
function Factors({ items: all }: { items: NonNullable<ScoreResponse['explanation']> }) {
  // A factor that moved the score by less than the last digit shown would print as +0.000: left out.
  const items = all.filter((a) => Math.abs(a.contribution) >= 5e-6);
  const max = Math.max(...items.map((a) => Math.abs(a.contribution)), 1e-9);
  return (
    <div role="table" aria-label="Factor contributions">
      {items.map((a) => {
        const up = a.contribution > 0;
        const width = `${(Math.abs(a.contribution) / max) * 50}%`;
        return (
          <div className="factor-row" role="row" key={a.feature}>
            <span role="cell">{featureLabel(a.feature)}</span>
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
      const found = await getCachedScore(lookupId.trim());
      if (found) setCached(found);
      else setCacheMiss(true);
    } catch (e) {
      setCacheError(e);
    }
  };

  const horizon = result?.horizonMonths || 24;

  return (
    <div>
      <div className="page-head-row">
        <div>
          <h2>Default risk score</h2>
          <p className="page-subtitle">
            The probability that a loan defaults within 24 months of origination, the factors behind it, and the reasons
            you would give the borrower. Give the loan an ID and the score is recorded: it can be looked up below and
            appears in the portfolio.
          </p>
        </div>
      </div>

      <div className="card">
        <LoanFeaturesForm value={loan} onChange={setLoan} showLoanId showOrigination />
        <div className="actions">
          <button onClick={submit} disabled={loading}>
            {loading ? 'Scoring...' : 'Score loan'}
          </button>
        </div>
        <ErrorBanner error={error} />

        {result && (
          <div className="hero-score" role="status">
            <div>
              <div className="meta">Probability of default within {horizon} months</div>
              <div className="big">{pct(result.calibratedProbability, 2)}</div>
            </div>
            <RiskBadge probability={result.calibratedProbability} />
            <div className="secondary-stat">
              <div className="label">Uncalibrated model output</div>
              <div className="value">{pct(result.rawProbability, 2)}</div>
            </div>
            <div style={{ flexBasis: '100%' }}><RiskMeter probability={result.calibratedProbability} scale /></div>
          </div>
        )}

        {result?.warnings && result.warnings.length > 0 && (
          <div className="warn-banner" role="status">
            {result.warnings.map((w) => <div key={w}>{w}</div>)}
          </div>
        )}

        {result?.reasonCodes && result.reasonCodes.length > 0 && (
          <div style={{ marginTop: '1.5rem' }}>
            <h3>Principal reasons</h3>
            <p className="page-subtitle">
              The factors that raise this loan's risk the most, worded as the reasons an adverse-action notice would state.
            </p>
            <ul className="list">
              {result.reasonCodes.map((r) => (
                <li key={r.code + r.feature} className="list-row">
                  <span className="grow" style={{ whiteSpace: 'normal' }}>{r.description}</span>
                  <span className="sub">{r.code}</span>
                  <span className="num">+{(r.contribution * 100).toFixed(2)} pts</span>
                </li>
              ))}
            </ul>
          </div>
        )}

        {result?.explanation && result.explanation.length > 0 && (
          <div style={{ marginTop: '1.5rem' }}>
            <h3>What moved this score</h3>
            <p className="page-subtitle">
              Shapley attribution in percentage points of default probability. Red raises risk, green lowers it.
              {result.baselineProbability != null && (
                <> A typical loan of the training population scores {pct(result.baselineProbability, 2)}; the factors
                below add up exactly to the difference between that and this loan's {pct(result.calibratedProbability, 2)}.</>
              )}
            </p>
            <Factors items={result.explanation} />
          </div>
        )}

        {result?.modelVersion && (
          <p className="chart-caption">
            Model {result.modelVersion}. The uncalibrated output ranks loans; the calibrated probability is the one to use
            for pricing, provisioning and any decision with money attached.
          </p>
        )}
      </div>

      <div className="card">
        <div className="card-head"><h3>Look up a recorded score</h3></div>
        <div className="row-inline">
          <div className="field">
            <label htmlFor="lookupId">Loan ID</label>
            <input id="lookupId" value={lookupId} onChange={(e) => setLookupId(e.target.value)} onKeyDown={(e) => e.key === 'Enter' && lookupId.trim() && lookup()} />
          </div>
          <button className="secondary" onClick={lookup} disabled={!lookupId.trim()}>
            Look up
          </button>
        </div>
        <ErrorBanner error={cacheError} />
        {cacheMiss && <p className="empty">This loan has not been scored yet.</p>}
        {cached && (
          <div className="hero-score">
            <div>
              <div className="meta">Probability of default within {cached.score.horizonMonths || 24} months</div>
              <div className="big">{pct(cached.score.calibratedProbability, 2)}</div>
            </div>
            <RiskBadge probability={cached.score.calibratedProbability} />
            <div className="secondary-stat">
              <div className="label">Computed</div>
              <div className="value" style={{ fontSize: '0.95rem' }}>{dateTime(cached.computedAt)}</div>
              {cached.score.modelVersion && <div className="sub">model {cached.score.modelVersion}</div>}
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
