import { useState } from 'react';
import { expectedLoss } from '../api/client';
import LoanFeaturesForm, { DEFAULT_LOAN } from '../components/LoanFeaturesForm';
import ErrorBanner from '../components/ErrorBanner';
import type { ExpectedLossResponse } from '../api/types';

export default function ExpectedLossPage() {
  const [loan, setLoan] = useState(DEFAULT_LOAN);
  const [result, setResult] = useState<ExpectedLossResponse | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [loading, setLoading] = useState(false);

  const submit = async () => {
    setLoading(true);
    setError(null);
    setResult(null);
    try {
      setResult(await expectedLoss(loan));
    } catch (e) {
      setError(e);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div>
      <h2>Expected Loss</h2>
      <p className="page-subtitle">
        PD x LGD x EAD -- the full Basel-style Expected Loss framework. PD comes from the same
        calibrated model as the Score page; LGD from a Beta regression fit on realized-loss
        loans; EAD is a stated simplification (original UPB, since no post-origination
        performance history exists for a loan being scored at origination).
      </p>

      <div className="card">
        <LoanFeaturesForm value={loan} onChange={setLoan} />
        <div className="actions">
          <button onClick={submit} disabled={loading}>
            {loading ? 'Calculating...' : 'Calculate expected loss'}
          </button>
        </div>
        <ErrorBanner error={error} />
        {result && (
          <>
            <div className="result-grid">
              <div className="stat">
                <div className="label">PD</div>
                <div className="value">{(result.pd * 100).toFixed(3)}%</div>
              </div>
              <div className="stat">
                <div className="label">LGD</div>
                <div className="value">{(result.lgd * 100).toFixed(2)}%</div>
              </div>
              <div className="stat">
                <div className="label">EAD</div>
                <div className="value">${result.ead.toLocaleString()}</div>
              </div>
              <div className="stat">
                <div className="label">Expected loss</div>
                <div className="value">${result.expectedLoss.toFixed(2)}</div>
              </div>
            </div>
            <p className="warn-banner">
              LGD is only observable in the training data for 2,746 of 243,304 "defaulted" loans
              (1.1%) -- most either cured or haven't completed liquidation in this dataset's
              window. EAD = original UPB is a simplification, not a modeled prediction.
            </p>
          </>
        )}
      </div>
    </div>
  );
}
