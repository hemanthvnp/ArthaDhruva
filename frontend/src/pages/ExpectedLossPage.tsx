import { useState } from 'react';
import { Link } from 'react-router-dom';
import { expectedLoss } from '../api/client';
import LoanFeaturesForm from '../components/LoanFeaturesForm';
import { DEFAULT_LOAN } from '../components/loanDefaults';
import LoanPicker from '../components/LoanPicker';
import ErrorBanner from '../components/ErrorBanner';
import RiskMeter from '../components/RiskMeter';
import { duration, money, pct } from '../format';
import type { ExpectedLossResponse, LoanFeatures } from '../api/types';

const STAGE_BADGE = ['', 'badge-low', 'badge-medium', 'badge-high'];
const STAGE_NOTE = ['', 'the allowance is twelve months of expected loss', 'the allowance is the lifetime expected loss', 'credit-impaired: the allowance is the loss expected on the balance outstanding'];

function Result({ loanId, r }: { loanId?: string; r: ExpectedLossResponse }) {
  return (
    <>
      {r.warnings.length > 0 && (
        <div className="warn-banner" role="status">
          {r.warnings.map((w) => <div key={w}>{w}</div>)}
        </div>
      )}
      <div className="kpi-row" style={{ marginTop: '1.25rem' }}>
        <div className="kpi">
          <div className="label">Default within 12 months</div>
          <div className="value">{pct(r.pd)}</div>
          <div style={{ margin: '0.5rem 0 0.2rem' }}><RiskMeter probability={r.pd} small /></div>
          <div className="hint">over its life {pct(r.pdLifetime)}</div>
        </div>
        <div className="kpi">
          <div className="label">Loss given default</div>
          <div className="value">{pct(r.lgd, 1)}</div>
          <div className="hint">of the balance at default</div>
        </div>
        <div className="kpi">
          <div className="label">Exposure today</div>
          <div className="value">{money(r.ead)}</div>
          <div className="hint">expected on the book for {duration(r.expectedLifeMonths)}</div>
        </div>
        <div className="kpi accent">
          <div className="label">Loss allowance (IFRS 9)</div>
          <div className="value">{money(r.eclIfrs9)}</div>
          <div className="hint">stage {r.stage}</div>
        </div>
      </div>
      <dl className="facts">
        <div><dt>12-month expected loss</dt><dd>{money(r.expectedLoss)}</dd></div>
        <div><dt>Lifetime expected loss (CECL)</dt><dd>{money(r.expectedLossLifetime)}</dd></div>
        <div>
          <dt>IFRS 9 stage</dt>
          <dd><span className={`badge ${STAGE_BADGE[r.stage] ?? ''}`}>Stage {r.stage}</span> <span className="sub">{STAGE_NOTE[r.stage]}</span></dd>
        </div>
        <div><dt>Model</dt><dd>{r.modelVersion}</dd></div>
      </dl>
      <p className="chart-caption">
        The 12-month figure adds up, for each of the next twelve months, the probability of defaulting in that month times
        the loss given default times that month's balance, discounted at the note rate. That is close to PD × LGD × EAD but
        not equal to it, because the balance amortizes and later losses are discounted.
        {loanId && <> <Link to={`/lifetime-risk?loanId=${encodeURIComponent(loanId)}`}>See this loan month by month, under stress</Link>.</>}
      </p>
    </>
  );
}

export default function ExpectedLossPage() {
  const [mode, setMode] = useState<'portfolio' | 'manual'>('portfolio');
  const [manual, setManual] = useState<LoanFeatures>(DEFAULT_LOAN);
  const [result, setResult] = useState<{ loanId?: string; r: ExpectedLossResponse } | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [loading, setLoading] = useState(false);

  const compute = async (loan: LoanFeatures, loanId?: string) => {
    setLoading(true);
    setError(null);
    setResult(null);
    try {
      setResult({ loanId, r: await expectedLoss(loan) });
    } catch (e) {
      setError(e);
    } finally {
      setLoading(false);
    }
  };

  const choose = (next: 'portfolio' | 'manual') => {
    setMode(next);
    setResult(null);
    setError(null);
  };

  return (
    <div>
      <div className="page-head-row">
        <div>
          <h2>Expected loss</h2>
          <p className="page-subtitle">
            What one loan is expected to cost: its 12-month and lifetime expected credit loss under the baseline scenario,
            and the IFRS 9 stage that decides which of the two is the allowance.
          </p>
        </div>
      </div>

      <div className="card">
        <div className="toolbar">
          <div className="segmented" role="tablist" aria-label="Which loan">
            <button role="tab" aria-selected={mode === 'portfolio'} className={mode === 'portfolio' ? 'on' : ''} onClick={() => choose('portfolio')}>A loan in the portfolio</button>
            <button role="tab" aria-selected={mode === 'manual'} className={mode === 'manual' ? 'on' : ''} onClick={() => choose('manual')}>A new application</button>
          </div>
        </div>
        {mode === 'portfolio' ? (
          <LoanPicker action="Calculate" busy={loading} onPick={(loan) => compute(loan, loan.loanId)} />
        ) : (
          <>
            <LoanFeaturesForm value={manual} onChange={setManual} showOrigination />
            <div className="actions">
              <button onClick={() => compute(manual)} disabled={loading}>
                {loading ? 'Calculating...' : 'Calculate'}
              </button>
            </div>
          </>
        )}
        <ErrorBanner error={error} />
        {result && <Result loanId={result.loanId} r={result.r} />}
      </div>
    </div>
  );
}
