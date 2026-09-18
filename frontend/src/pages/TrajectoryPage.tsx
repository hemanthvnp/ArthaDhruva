import { useEffect, useState } from 'react';
import { listTrajectoryCatalog, trajectoryScore } from '../api/client';
import ErrorBanner from '../components/ErrorBanner';
import type { MonthlyRecord, TrajectoryCatalogEntry, TrajectoryScoreResponse } from '../api/types';

const DEFAULT_MONTHS: MonthlyRecord[] = [
  { currentLoanDelinquencyStatus: '0', currentActualUpb: 249500, modificationFlag: 'N' },
  { currentLoanDelinquencyStatus: '0', currentActualUpb: 249000, modificationFlag: 'N' },
  { currentLoanDelinquencyStatus: '0', currentActualUpb: 248500, modificationFlag: 'N' },
];

export default function TrajectoryPage() {
  const [originalUpb, setOriginalUpb] = useState(250000);
  const [months, setMonths] = useState<MonthlyRecord[]>(DEFAULT_MONTHS);
  const [result, setResult] = useState<TrajectoryScoreResponse | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [loading, setLoading] = useState(false);

  const [catalog, setCatalog] = useState<TrajectoryCatalogEntry[]>([]);
  const [pickedLabel, setPickedLabel] = useState('');
  const [pickLoading, setPickLoading] = useState(false);
  const [pickError, setPickError] = useState<unknown>(null);
  const [pickResult, setPickResult] = useState<{ entry: TrajectoryCatalogEntry; result: TrajectoryScoreResponse } | null>(null);

  useEffect(() => {
    listTrajectoryCatalog().then(setCatalog).catch(() => {});
  }, []);

  const scorePicked = async () => {
    const entry = catalog.find((e) => e.label === pickedLabel);
    if (!entry) return;
    setPickLoading(true);
    setPickError(null);
    setPickResult(null);
    try {
      const result = await trajectoryScore(entry.request);
      setPickResult({ entry, result });
    } catch (e) {
      setPickError(e);
    } finally {
      setPickLoading(false);
    }
  };

  const updateMonth = (i: number, key: keyof MonthlyRecord, v: string | number) =>
    setMonths(months.map((m, idx) => (idx === i ? { ...m, [key]: v } : m)));

  const addMonth = () => {
    if (months.length >= 12) return;
    const last = months[months.length - 1];
    setMonths([...months, { ...last }]);
  };
  const removeMonth = (i: number) => setMonths(months.filter((_, idx) => idx !== i));

  const submit = async () => {
    setLoading(true);
    setError(null);
    setResult(null);
    try {
      setResult(await trajectoryScore({ originalUpb, months }));
    } catch (e) {
      setError(e);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div>
      <h2>Loan Trajectory Score</h2>
      <p className="page-subtitle">
        LSTM prediction from a loan's first up to 12 months of <em>actual</em> performance
        (delinquency status, UPB paydown, modification events), not a static origination-time
        snapshot -- the deep-learning + time-series component of this project. Months are ordered
        from origination.
      </p>

      <div className="card">
        <h3>Score a real loan's actual trajectory</h3>
        <p className="page-subtitle">
          Real loans' actual first-observed months, sampled from the 2020Q1 origination cohort.
        </p>
        <div className="row-inline">
          <div className="field" style={{ minWidth: 300 }}>
            <label htmlFor="trajLoanPicker">Loan</label>
            <input
              id="trajLoanPicker"
              list="traj-catalog-labels"
              value={pickedLabel}
              onChange={(e) => setPickedLabel(e.target.value)}
              placeholder="Start typing a real Loan ID..."
            />
            <datalist id="traj-catalog-labels">
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
          <div className="result-grid">
            <div className="stat">
              <div className="label">Probability</div>
              <div className="value">{(pickResult.result.probability * 100).toFixed(1)}%</div>
            </div>
          </div>
        )}
        <p className="page-subtitle" style={{ marginTop: '0.75rem' }}>
          {catalog.length.toLocaleString()} real trajectories available to pick from.
        </p>
      </div>

      <div className="card">
        <h3>Or score a hypothetical trajectory (manual)</h3>
        <div className="field" style={{ maxWidth: 240, marginBottom: '1rem' }}>
          <label htmlFor="originalUpb">Original UPB ($)</label>
          <input
            id="originalUpb"
            type="number"
            step={1000}
            min={1}
            value={originalUpb}
            onChange={(e) => setOriginalUpb(Number(e.target.value))}
          />
        </div>

        <table>
          <thead>
            <tr>
              <th>Month</th>
              <th>Delinquency status</th>
              <th>Current UPB ($)</th>
              <th>Modified?</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {months.map((m, i) => (
              <tr key={i}>
                <td>{i}</td>
                <td>
                  <input
                    style={{ width: 70 }}
                    type="text"
                    value={m.currentLoanDelinquencyStatus}
                    onChange={(e) => updateMonth(i, 'currentLoanDelinquencyStatus', e.target.value)}
                  />
                </td>
                <td>
                  <input
                    type="number"
                    step={100}
                    min={0}
                    value={m.currentActualUpb}
                    onChange={(e) => updateMonth(i, 'currentActualUpb', Number(e.target.value))}
                  />
                </td>
                <td>
                  <select
                    value={m.modificationFlag}
                    onChange={(e) => updateMonth(i, 'modificationFlag', e.target.value)}
                  >
                    <option value="N">N</option>
                    <option value="Y">Y</option>
                  </select>
                </td>
                <td>
                  <button className="danger-outline" onClick={() => removeMonth(i)} disabled={months.length <= 1}>
                    Remove
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        <div className="actions">
          <button className="secondary" onClick={addMonth} disabled={months.length >= 12}>
            + Add month
          </button>
          <button onClick={submit} disabled={loading}>
            {loading ? 'Scoring...' : 'Score trajectory'}
          </button>
        </div>
        <ErrorBanner error={error} />
        {result && (
          <>
            <div className="result-grid">
              <div className="stat">
                <div className="label">Probability</div>
                <div className="value">{(result.probability * 100).toFixed(1)}%</div>
              </div>
            </div>
            <p className="warn-banner">
              Uncalibrated: the model trains with pos_weight-weighted loss and, unlike the PD
              model, never fits a calibration step afterward. Useful for ranking trajectories
              relative to each other (a worsening trajectory scores meaningfully higher), not as a
              dollar-valued probability.
            </p>
          </>
        )}
      </div>
    </div>
  );
}
