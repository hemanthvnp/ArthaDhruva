import { useEffect, useState } from 'react';
import { expectedLoss, listLoanCatalog } from '../api/client';
import LoanFeaturesForm, { DEFAULT_LOAN } from '../components/LoanFeaturesForm';
import ErrorBanner from '../components/ErrorBanner';
import type { ExpectedLossResponse, LoanFeatures } from '../api/types';

export default function ExpectedLossPage() {
  const [loan, setLoan] = useState(DEFAULT_LOAN);
  const [result, setResult] = useState<ExpectedLossResponse | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [loading, setLoading] = useState(false);

  const [catalog, setCatalog] = useState<LoanFeatures[]>([]);
  const [pickedLoanId, setPickedLoanId] = useState('');
  const [pickLoading, setPickLoading] = useState(false);
  const [pickResult, setPickResult] = useState<{ loanId: string; result: ExpectedLossResponse } | null>(null);
  const [pickError, setPickError] = useState<unknown>(null);

  useEffect(() => {
    listLoanCatalog().then(setCatalog).catch(() => {});
  }, []);

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

  const scorePicked = async () => {
    const picked = catalog.find((l) => l.loanId === pickedLoanId);
    if (!picked) return;
    setPickLoading(true);
    setPickError(null);
    setPickResult(null);
    try {
      const result = await expectedLoss(picked);
      setPickResult({ loanId: picked.loanId!, result });
    } catch (e) {
      setPickError(e);
    } finally {
      setPickLoading(false);
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
        <h3>Compute for a real loan by ID</h3>
        <div className="row-inline">
          <div className="field" style={{ minWidth: 260 }}>
            <label htmlFor="elLoanIdPicker">Loan ID</label>
            <input
              id="elLoanIdPicker"
              list="el-loan-catalog-ids"
              value={pickedLoanId}
              onChange={(e) => setPickedLoanId(e.target.value)}
              placeholder="Start typing a real Loan ID..."
            />
            <datalist id="el-loan-catalog-ids">
              {catalog.map((l) => (
                <option key={l.loanId} value={l.loanId}>
                  {`credit ${l.creditScore}, ${l.propertyState}`}
                </option>
              ))}
            </datalist>
          </div>
          <button
            onClick={scorePicked}
            disabled={pickLoading || !catalog.some((l) => l.loanId === pickedLoanId)}
          >
            {pickLoading ? 'Calculating...' : 'Fetch & calculate'}
          </button>
        </div>
        <ErrorBanner error={pickError} />
        {pickResult && (
          <div className="result-grid">
            <div className="stat">
              <div className="label">Loan</div>
              <div className="value" style={{ fontSize: '1.1rem' }}>{pickResult.loanId}</div>
            </div>
            <div className="stat">
              <div className="label">PD</div>
              <div className="value">{(pickResult.result.pd * 100).toFixed(3)}%</div>
            </div>
            <div className="stat">
              <div className="label">LGD</div>
              <div className="value">{(pickResult.result.lgd * 100).toFixed(2)}%</div>
            </div>
            <div className="stat">
              <div className="label">Expected loss</div>
              <div className="value">${pickResult.result.expectedLoss.toFixed(2)}</div>
            </div>
          </div>
        )}
      </div>

      <div className="card">
        <h3>Or calculate for a hypothetical loan (manual)</h3>
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
