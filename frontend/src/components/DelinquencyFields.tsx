import { checkSummary } from './delinquency';
import type { DelinquencyCheck, DelinquencyDraft } from './delinquency';

interface Props {
  draft: DelinquencyDraft;
  check: DelinquencyCheck;
  onChange: (next: DelinquencyDraft) => void;
}

/**
 * The loan's recent payment history, optional. With days past due it selects the delinquency model, which projects
 * how a late loan cures, rolls or defaults instead of treating it like a current one. Validation matches the API's.
 */
export default function DelinquencyFields({ draft, check, onChange }: Props) {
  const set = (patch: Partial<DelinquencyDraft>) => onChange({ ...draft, ...patch });
  const err = (f: keyof DelinquencyDraft) => check.errors[f];
  return (
    <div className="form-section" style={{ marginTop: '1rem' }}>
      <div className="section-title">Payment history (optional)</div>
      <div className="field-grid">
        <div className="field">
          <label htmlFor="monthsObserved">Months of history (1 to 12)</label>
          <input id="monthsObserved" type="number" min={1} max={12} step={1} value={draft.monthsObserved} aria-invalid={!!err('monthsObserved')}
            aria-describedby={err('monthsObserved') ? 'monthsObserved-error' : undefined} onChange={(e) => set({ monthsObserved: e.target.value })} />
          {err('monthsObserved') && <div id="monthsObserved-error" className="field-error">{err('monthsObserved')}</div>}
        </div>
        <div className="field">
          <label htmlFor="episodes">Spells 30+ days late</label>
          <input id="episodes" type="number" min={0} max={12} step={1} value={draft.episodes} aria-invalid={!!err('episodes')}
            aria-describedby={err('episodes') ? 'episodes-error' : undefined} onChange={(e) => set({ episodes: e.target.value })} />
          {err('episodes') && <div id="episodes-error" className="field-error">{err('episodes')}</div>}
        </div>
        <div className="field">
          <label htmlFor="monthsSinceCure">Months since last cure</label>
          <input id="monthsSinceCure" type="number" min={0} max={12} step={1} value={draft.monthsSinceCure} placeholder="none in the window"
            aria-invalid={!!err('monthsSinceCure')} aria-describedby={err('monthsSinceCure') ? 'monthsSinceCure-error' : undefined}
            onChange={(e) => set({ monthsSinceCure: e.target.value })} />
          {err('monthsSinceCure') && <div id="monthsSinceCure-error" className="field-error">{err('monthsSinceCure')}</div>}
        </div>
        <div className="field">
          <label htmlFor="maxStatus">Worst status in the window</label>
          <select id="maxStatus" value={draft.maxStatus} aria-invalid={!!err('maxStatus')} aria-describedby={err('maxStatus') ? 'maxStatus-error' : undefined}
            onChange={(e) => set({ maxStatus: e.target.value as DelinquencyDraft['maxStatus'] })}>
            <option value="">not given</option>
            <option value="CURRENT">Current</option>
            <option value="D30">30 days past due</option>
            <option value="D60">60 days past due</option>
          </select>
          {err('maxStatus') && <div id="maxStatus-error" className="field-error">{err('maxStatus')}</div>}
        </div>
      </div>
      <p className="chart-caption" data-testid="delinquency-summary">{checkSummary(check)}</p>
    </div>
  );
}
