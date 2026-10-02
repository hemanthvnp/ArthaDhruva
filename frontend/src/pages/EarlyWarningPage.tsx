import { useEffect, useState } from 'react';
import { earlyWarningScore, listEarlyWarningCatalog } from '../api/client';
import EarlyWarningFeaturesForm from '../components/EarlyWarningFeaturesForm';
import { DEFAULT_EARLY_WARNING_LOAN } from '../components/loanDefaults';
import ErrorBanner from '../components/ErrorBanner';
import RiskMeter from '../components/RiskMeter';
import type { EarlyWarningCatalogEntry, EarlyWarningResponse } from '../api/types';
import { count } from '../format';

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
      <div className="page-head-row">
        <div>
          <h2>Early warning</h2>
          <p className="page-subtitle">
            For a loan that is current and has never been late: the probability that it goes 30 or more days past due
            within the next three months. A first-time signal, not a predictor of repeat delinquency.
          </p>
        </div>
      </div>

      <div className="card">
        <h3>An observed snapshot</h3>
        <p className="page-subtitle">
          Snapshots of performing loans the model was not fitted on, each shown with what the loan went on to do.
        </p>
        <div className="row-inline">
          <div className="field" style={{ minWidth: 320 }}>
            <label htmlFor="ewLoanPicker">Loan snapshot</label>
            <input
              id="ewLoanPicker"
              list="ew-catalog-labels"
              value={pickedLabel}
              onChange={(e) => setPickedLabel(e.target.value)}
              placeholder="Start typing a loan ID"
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
            {pickLoading ? 'Scoring...' : 'Score'}
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
                <div className="label">What happened</div>
                <div className="value" style={{ color: pickResult.entry.actuallyWentDelinquent ? 'var(--danger)' : 'var(--ok)' }}>
                  {pickResult.entry.actuallyWentDelinquent ? 'Went late' : 'Stayed current'}
                </div>
              </div>
            </div>
            <p className="chart-caption">
              The outcome is what this loan did in the three months after the snapshot. The model never saw it; it is
              here to set the prediction against what happened.
            </p>
          </>
        )}
        <p className="chart-caption">{count(catalog.length)} snapshots to pick from.</p>
      </div>

      <div className="card">
        <h3>A snapshot you enter</h3>
        <p className="page-subtitle">
          The trend fields (estimated LTV, balance paid down, rate-lock severity and their three- and six-month changes)
          describe a loan's recent history. They are inputs here: this form does not derive them from payments.
        </p>
        <EarlyWarningFeaturesForm value={loan} onChange={setLoan} />
        <div className="actions">
          <button onClick={submit} disabled={loading}>
            {loading ? 'Scoring...' : 'Score'}
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
          <p className="chart-caption">
            Use the calibrated risk as the probability. The raw output is kept for ranking: calibration is a step
            function, so two loans can share a calibrated value and still differ in the raw one.
          </p>
        )}
      </div>
    </div>
  );
}
