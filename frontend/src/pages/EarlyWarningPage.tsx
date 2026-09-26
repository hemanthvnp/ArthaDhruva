import { useEffect, useState } from 'react';
import { earlyWarningScore, listEarlyWarningCatalog } from '../api/client';
import EarlyWarningFeaturesForm, { DEFAULT_EARLY_WARNING_LOAN } from '../components/EarlyWarningFeaturesForm';
import ErrorBanner from '../components/ErrorBanner';
import RiskMeter from '../components/RiskMeter';
import type { EarlyWarningCatalogEntry, EarlyWarningResponse } from '../api/types';

export default function EarlyWarningPage() {
  const [loan, setLoan] = useState(DEFAULT_EARLY_WARNING_LOAN);
  const [result, setResult] = useState<EarlyWarningResponse | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [loading, setLoading] = useState(false);

  const [catalog, setCatalog] = useState<EarlyWarningCatalogEntry[]>([]);
  const [pickedLabel, setPickedLabel] = useState('');
  const [pickLoading, setPickLoading] = useState(false);
  const [pickError, setPickError] = useState<unknown>(null);
  const [pickResult, setPickResult] = useState<{ entry: EarlyWarningCatalogEntry; result: EarlyWarningResponse } | null>(null);

  useEffect(() => {
    listEarlyWarningCatalog().then(setCatalog).catch(() => {});
  }, []);

  const submit = async () => {
    setLoading(true);
    setError(null);
    setResult(null);
    try {
      setResult(await earlyWarningScore(loan));
    } catch (e) {
      setError(e);
    } finally {
      setLoading(false);
    }
  };

  const scorePicked = async () => {
    const entry = catalog.find((e) => e.label === pickedLabel);
    if (!entry) return;
    setPickLoading(true);
    setPickError(null);
    setPickResult(null);
    try {
      const result = await earlyWarningScore(entry.features);
      setPickResult({ entry, result });
    } catch (e) {
      setPickError(e);
    } finally {
      setPickLoading(false);
    }
  };

  return (
    <div>
      <h2>Early-Warning Delinquency</h2>
      <p className="page-subtitle">
        For a currently-performing loan (0 DPD, never delinquent before), the probability it goes
        30+ days late within the next 3 months -- a first-time early-warning signal, not a
        recurring-delinquency predictor.
      </p>

      <div className="card">
        <h3>Score a real loan snapshot</h3>
        <p className="page-subtitle">
          Real currently-current-loan snapshots sampled from the natural-rate calibration
          holdout -- each one's real, later-observed outcome is shown alongside the prediction.
        </p>
        <div className="row-inline">
          <div className="field" style={{ minWidth: 320 }}>
            <label htmlFor="ewLoanPicker">Loan snapshot</label>
            <input
              id="ewLoanPicker"
              list="ew-catalog-labels"
              value={pickedLabel}
              onChange={(e) => setPickedLabel(e.target.value)}
              placeholder="Start typing a real Loan ID..."
            />
            <datalist id="ew-catalog-labels">
              {catalog.map((e) => (
                <option key={e.label} value={e.label} />
              ))}
            </datalist>
          </div>
          <button
            onClick={scorePicked}
            disabled={pickLoading || !catalog.some((e) => e.label === pickedLabel)}
          >
            {pickLoading ? 'Scoring...' : 'Fetch & score'}
          </button>
        </div>
        <ErrorBanner error={pickError} />
        {pickResult && (
          <>
            <div className="result-grid">
              <div className="stat">
                <div className="label">Raw risk</div>
                <div className="value">{(pickResult.result.rawRisk * 100).toFixed(2)}%</div>
              </div>
              <div className="stat">
                <div className="label">Calibrated risk</div>
                <div className="value">{(pickResult.result.calibratedRisk * 100).toFixed(2)}%</div>
                <div style={{ marginTop: '0.6rem' }}><RiskMeter probability={pickResult.result.calibratedRisk} small /></div>
              </div>
              <div className="stat">
                <div className="label">Actually went delinquent?</div>
                <div className="value" style={{ color: pickResult.entry.actuallyWentDelinquent ? 'var(--danger)' : 'var(--ok)' }}>
                  {pickResult.entry.actuallyWentDelinquent ? 'Yes' : 'No'}
                </div>
              </div>
            </div>
            <p className="page-subtitle" style={{ marginTop: '0.5rem' }}>
              "Actually went delinquent" is the real, later-observed outcome for this exact
              loan/month -- not something the model saw. It's shown here purely to compare the
              prediction against reality.
            </p>
          </>
        )}
        <p className="page-subtitle" style={{ marginTop: '0.75rem' }}>
          {catalog.length.toLocaleString()} real snapshots available to pick from.
        </p>
      </div>

      <div className="card">
        <h3>Or score a hypothetical snapshot (manual)</h3>
        <p className="page-subtitle">
          Trend and regime features below (eLTV, UPB paydown, rate-lock severity, and their
          3-/6-month changes, macro regime) describe a loan's actual recent history; this form
          doesn't compute them for you, the same way expected-loss's EAD isn't a modeled
          prediction.
        </p>
        <EarlyWarningFeaturesForm value={loan} onChange={setLoan} />
        <div className="actions">
          <button onClick={submit} disabled={loading}>
            {loading ? 'Scoring...' : 'Score early-warning risk'}
          </button>
        </div>
        <ErrorBanner error={error} />
        {result && (
          <div className="result-grid">
            <div className="stat">
              <div className="label">Raw risk</div>
              <div className="value">{(result.rawRisk * 100).toFixed(2)}%</div>
            </div>
            <div className="stat">
              <div className="label">Calibrated risk</div>
              <div className="value">{(result.calibratedRisk * 100).toFixed(2)}%</div>
              <div style={{ marginTop: '0.6rem' }}><RiskMeter probability={result.calibratedRisk} small /></div>
            </div>
          </div>
        )}
        {result && (
          <p className="warn-banner">
            Use calibrated risk for a dollar/decision-meaningful probability. Raw risk is kept at
            full resolution for ranking multiple loans against each other -- isotonic calibration
            is a monotonic step function that coarsens resolution at the extreme tail, so two
            loans can share a calibrated value while still being genuinely different in raw risk.
          </p>
        )}
      </div>
    </div>
  );
}
