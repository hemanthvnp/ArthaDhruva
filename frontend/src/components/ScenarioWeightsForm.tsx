import { useState } from 'react';
import { scenarioLabel } from './chartTheme';
import { parseWeights, presetFor } from './weights';

/**
 * One percentage per scenario, the illustrative preset, and an apply button that stays disabled, with the
 * reason shown, until the weights sum to 100%.
 */
export default function ScenarioWeightsForm({ scenarios, applied, onApply }: {
  scenarios: string[];
  applied: Record<string, number> | null;
  onApply: (weights: Record<string, number> | null) => void;
}) {
  const [entries, setEntries] = useState<Record<string, string>>(() =>
    applied ? Object.fromEntries(scenarios.map((s) => [s, String(Math.round((applied[s] ?? 0) * 1000) / 10)])) : presetFor(scenarios));
  const parsed = parseWeights(Object.fromEntries(scenarios.map((s) => [s, entries[s] ?? ''])));

  return (
    <div className="form-section">
      <div className="section-title">Probability of each scenario (%)</div>
      <div className="field-grid">
        {scenarios.map((s) => (
          <div className="field" key={s}>
            <label htmlFor={`weight-${s}`}>{scenarioLabel(s)}</label>
            <input id={`weight-${s}`} type="number" min={0} max={100} step={5} value={entries[s] ?? ''}
                   onChange={(e) => setEntries({ ...entries, [s]: e.target.value })} />
          </div>
        ))}
      </div>
      <div className="actions">
        <button type="button" onClick={() => parsed.weights && onApply(parsed.weights)} disabled={parsed.problem !== null}
                title={parsed.problem ?? undefined}>
          Apply weights
        </button>
        <button type="button" className="secondary" onClick={() => setEntries(presetFor(scenarios))}>Illustrative 50 / 30 / 20</button>
        {applied && <button type="button" className="secondary" onClick={() => onApply(null)}>Clear</button>}
      </div>
      {parsed.problem && <p className="hint" role="status">{parsed.problem}</p>}
    </div>
  );
}
