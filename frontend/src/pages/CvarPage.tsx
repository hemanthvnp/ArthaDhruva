import { useState } from 'react';
import { simulateCvar } from '../api/client';
import ErrorBanner from '../components/ErrorBanner';
import RangeBar from '../components/RangeBar';
import type { CvarResult, LoanRiskProfile } from '../api/types';

const money = (n: number) => `$${n.toLocaleString(undefined, { maximumFractionDigits: 0 })}`;

const DEFAULT_LOANS: LoanRiskProfile[] = [
  { pd: 0.02, lgd: 0.4, ead: 250000 },
  { pd: 0.05, lgd: 0.5, ead: 180000 },
  { pd: 0.01, lgd: 0.3, ead: 400000 },
];

export default function CvarPage() {
  const [loans, setLoans] = useState<LoanRiskProfile[]>(DEFAULT_LOANS);
  const [confidenceLevel, setConfidenceLevel] = useState(0.95);
  const [numScenarios, setNumScenarios] = useState(50000);
  const [result, setResult] = useState<CvarResult | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [loading, setLoading] = useState(false);

  const updateLoan = (i: number, key: keyof LoanRiskProfile, v: number) =>
    setLoans(loans.map((l, idx) => (idx === i ? { ...l, [key]: v } : l)));

  const addLoan = () => setLoans([...loans, { pd: 0.02, lgd: 0.4, ead: 200000 }]);
  const removeLoan = (i: number) => setLoans(loans.filter((_, idx) => idx !== i));

  const submit = async () => {
    setLoading(true);
    setError(null);
    setResult(null);
    try {
      setResult(await simulateCvar({ loans, confidenceLevel, numScenarios }));
    } catch (e) {
      setError(e);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div>
      <h2>Bootstrap CVaR Simulation</h2>
      <p className="page-subtitle">
        Monte Carlo simulates the portfolio's loss distribution from independent per-loan default
        draws, then bootstraps a confidence interval around VaR/CVaR -- a genuine interval
        reflecting simulation uncertainty, not a single point score. Independent-default
        simulation does not model correlated/systemic risk.
      </p>

      <div className="card">
        <h3>Portfolio</h3>
        <table>
          <thead>
            <tr>
              <th>PD</th>
              <th>LGD</th>
              <th>EAD ($)</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {loans.map((loan, i) => (
              <tr key={i}>
                <td>
                  <input
                    type="number"
                    step={0.001}
                    min={0}
                    max={1}
                    value={loan.pd}
                    onChange={(e) => updateLoan(i, 'pd', Number(e.target.value))}
                  />
                </td>
                <td>
                  <input
                    type="number"
                    step={0.01}
                    min={0}
                    max={1}
                    value={loan.lgd}
                    onChange={(e) => updateLoan(i, 'lgd', Number(e.target.value))}
                  />
                </td>
                <td>
                  <input
                    type="number"
                    step={1000}
                    min={0}
                    value={loan.ead}
                    onChange={(e) => updateLoan(i, 'ead', Number(e.target.value))}
                  />
                </td>
                <td>
                  <button className="danger-outline" onClick={() => removeLoan(i)} disabled={loans.length <= 1}>
                    Remove
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        <div className="actions">
          <button className="secondary" onClick={addLoan}>
            + Add loan
          </button>
        </div>

        <div className="row-inline" style={{ marginTop: '1.1rem' }}>
          <div className="field">
            <label htmlFor="confidenceLevel">Confidence level</label>
            <input
              id="confidenceLevel"
              type="number"
              step={0.01}
              min={0.5}
              max={0.999}
              value={confidenceLevel}
              onChange={(e) => setConfidenceLevel(Number(e.target.value))}
            />
          </div>
          <div className="field">
            <label htmlFor="numScenarios">Scenarios</label>
            <input
              id="numScenarios"
              type="number"
              step={1000}
              min={1000}
              max={200000}
              value={numScenarios}
              onChange={(e) => setNumScenarios(Number(e.target.value))}
            />
          </div>
          <button onClick={submit} disabled={loading}>
            {loading ? 'Simulating...' : 'Run simulation'}
          </button>
        </div>
        <ErrorBanner error={error} />
      </div>

      {result && (
        <div className="card">
          <h3>Result</h3>
          <RangeBar
            label={`Value at Risk (${(result.confidenceLevel * 100).toFixed(0)}%)`}
            point={result.valueAtRisk}
            lower={result.valueAtRiskConfidenceInterval[0]}
            upper={result.valueAtRiskConfidenceInterval[1]}
            color="#2f6fed"
            format={money}
          />
          <RangeBar
            label="Conditional VaR (expected shortfall)"
            point={result.conditionalValueAtRisk}
            lower={result.conditionalValueAtRiskConfidenceInterval[0]}
            upper={result.conditionalValueAtRiskConfidenceInterval[1]}
            color="#c0392b"
            format={money}
          />
          <div className="result-grid">
            <div className="stat">
              <div className="label">Mean loss</div>
              <div className="value">{money(result.meanLoss)}</div>
            </div>
            <div className="stat">
              <div className="label">Loans</div>
              <div className="value">{result.numLoans}</div>
            </div>
            <div className="stat">
              <div className="label">Scenarios</div>
              <div className="value">{result.numScenarios.toLocaleString()}</div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
