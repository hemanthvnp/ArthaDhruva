import { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { CartesianGrid, Line, LineChart, ReferenceLine, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { portfolioLossDistribution, simulateCvar } from '../api/client';
import ErrorBanner from '../components/ErrorBanner';
import RangeBar from '../components/RangeBar';
import { AXIS, GRID, TOOLTIP } from '../components/chartTheme';
import { axisMoney, compactMoney, count, money, pct } from '../format';
import type { CvarResult, LoanRiskProfile, LossParameters } from '../api/types';

const DEFAULT_LOANS: LoanRiskProfile[] = [
  { pd: 0.02, lgd: 0.4, ead: 250000 },
  { pd: 0.05, lgd: 0.5, ead: 180000 },
  { pd: 0.01, lgd: 0.3, ead: 400000 },
];
const CONFIDENCE: [number, string][] = [[0.95, '95%: one year in 20'], [0.99, '99%: one year in 100'], [0.995, '99.5%: one year in 200'], [0.999, '99.9%: one year in 1,000']];
const SCENARIOS = [10_000, 20_000, 50_000, 100_000];

type Mode = 'portfolio' | 'custom';
interface Outcome {
  result: CvarResult;
  /** Set when the simulation ran on the portfolio's own loans. */
  portfolio?: { asOf: string | null; loans: number; sampled: boolean };
}

/** "One year in N" for a tail probability. */
function oneIn(probability: number): string {
  return probability > 0 ? `one year in ${count(Math.round(1 / probability))}` : 'never observed';
}

function Exceedance({ result }: { result: CvarResult }) {
  // Drawn down to a hundredth of the tail probability asked for. Importance sampling does reach losses far
  // rarer than that, but on a handful of heavily weighted scenarios each: too few to be worth drawing.
  const floor = Math.pow(10, Math.floor(Math.log10((1 - result.confidenceLevel) / 100) + 1e-9));
  const points = useMemo(() => result.exceedanceCurve.filter((p) => p.probability >= floor).map((p) => ({ loss: p.loss, probability: p.probability })),
    [result, floor]);
  if (points.length < 2) return null;
  const ticks: number[] = [];
  for (let t = 1; t >= floor * 0.999; t /= 10) ticks.push(t);
  const level = pct(result.confidenceLevel, result.confidenceLevel >= 0.995 ? 1 : 0);
  return (
    <ResponsiveContainer width="100%" height={300}>
      <LineChart data={points} margin={{ top: 8, right: 16, bottom: 0, left: 4 }}>
        <CartesianGrid {...GRID} />
        <XAxis dataKey="loss" type="number" domain={[0, 'dataMax']} tickFormatter={(v) => axisMoney(Number(v))} {...AXIS} />
        <YAxis scale="log" domain={[floor, 1]} ticks={ticks} allowDataOverflow tickFormatter={(v) => `${Number((Number(v) * 100).toPrecision(1))}%`} width={64} {...AXIS} />
        <Tooltip {...TOOLTIP} labelFormatter={(v) => `A loss above ${money(Number(v))}`}
          formatter={(v) => [`${pct(Number(v))} of years (${oneIn(Number(v))})`, 'Happens in']} />
        <ReferenceLine x={result.expectedLoss} stroke="var(--text-muted)" strokeDasharray="6 4"
          label={{ value: 'expected loss', position: 'insideTopLeft', fill: 'var(--text-muted)', fontSize: 12 }} />
        <ReferenceLine x={result.valueAtRisk} stroke="var(--sig-high)" strokeDasharray="6 4"
          label={{ value: `value at risk, ${level}`, position: 'insideTopLeft', dy: 18, fill: 'var(--sig-high)', fontSize: 12 }} />
        <Line type="monotone" dataKey="probability" stroke="var(--text)" strokeWidth={2.2} dot={false} isAnimationActive={false} />
      </LineChart>
    </ResponsiveContainer>
  );
}

function Result({ outcome, onReuseSeed }: { outcome: Outcome; onReuseSeed: (seed: number) => void }) {
  const r = outcome.result;
  const level = pct(r.confidenceLevel, r.confidenceLevel >= 0.995 ? 1 : 0);
  return (
    <>
      <div className="kpi-row" style={{ marginTop: '1.25rem' }}>
        <div className="kpi">
          <div className="label">Expected loss</div>
          <div className="value">{compactMoney(r.expectedLoss)}</div>
          <div className="hint">an average year; {pct(r.expectedLoss / r.totalExposure)} of {compactMoney(r.totalExposure)}</div>
        </div>
        <div className="kpi">
          <div className="label">Value at risk, {level}</div>
          <div className="value">{compactMoney(r.valueAtRisk)}</div>
          <div className="hint">exceeded {oneIn(1 - r.confidenceLevel)}</div>
        </div>
        <div className="kpi">
          <div className="label">Expected shortfall</div>
          <div className="value">{compactMoney(r.conditionalValueAtRisk)}</div>
          <div className="hint">the average of the years beyond VaR</div>
        </div>
        <div className="kpi accent">
          <div className="label">Unexpected loss</div>
          <div className="value">{compactMoney(r.unexpectedLoss)}</div>
          <div className="hint">VaR above expected loss: what capital is for</div>
        </div>
      </div>

      <div className="widget-grid" style={{ marginBottom: '1.25rem' }}>
        <div className="card">
          <div className="card-head"><h3>How often a loss this large happens</h3></div>
          <p className="page-subtitle">
            The share of years in which the loss exceeds each level, on a log scale: every gridline down is ten times rarer.
          </p>
          <Exceedance result={r} />
        </div>

        <div className="card">
          <div className="card-head"><h3>Where the tail comes from</h3></div>
          <p className="page-subtitle">
            Each loan's share of the expected shortfall: its average loss in the worst years. Over the whole portfolio the
            shares add up to it exactly.
          </p>
          {r.topContributions.length === 0 ? <p className="empty">No loan contributes to the tail.</p> : (
            <div className="table-scroll">
              <table>
                <thead>
                  <tr>
                    <th>Loan</th>
                    <th className="num">In the tail</th>
                    <th className="num">Share</th>
                    <th className="num">In an average year</th>
                  </tr>
                </thead>
                <tbody>
                  {r.topContributions.map((c) => (
                    <tr key={c.index}>
                      <td>
                        {c.loanId
                          ? outcome.portfolio ? <Link to={`/loans/${encodeURIComponent(c.loanId)}`}>{c.loanId}</Link> : c.loanId
                          : `Row ${c.index + 1}`}
                      </td>
                      <td className="num">{money(c.contribution)}</td>
                      <td className="num">{pct(c.share, 1)}</td>
                      <td className="num">{money(c.expectedLoss)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </div>

      <div className="card">
        <div className="card-head"><h3>How far to trust the simulation</h3></div>
        <div className="widget-grid">
          <RangeBar label={`Value at risk, ${level}`} point={r.valueAtRisk} lower={r.valueAtRiskConfidenceInterval[0]}
            upper={r.valueAtRiskConfidenceInterval[1]} color="var(--text)" format={money} />
          <RangeBar label="Expected shortfall" point={r.conditionalValueAtRisk} lower={r.conditionalValueAtRiskConfidenceInterval[0]}
            upper={r.conditionalValueAtRiskConfidenceInterval[1]} color="var(--sig-high)" format={money} />
        </div>
        <p className="chart-caption" style={{ marginTop: 0 }}>
          95% intervals for Monte Carlo error, from twenty independent sections of the {count(r.numScenarios)} scenarios.
          {r.importanceSampling
            ? ` Importance sampling shifted the common factor by ${r.factorShift.toFixed(2)} standard deviations toward bad years, so far more scenarios land in the tail than ${oneIn(1 - r.confidenceLevel)} would give.`
            : ' Importance sampling was off, so only a few scenarios reach the tail; turn it on for a tighter estimate.'}
        </p>
        <dl className="facts">
          <div>
            <dt>Without name concentration</dt>
            <dd>{money(r.asrfValueAtRisk)} <span className="sub">VaR of an infinitely fine-grained book (Basel's formula)</span></dd>
          </div>
          <div>
            <dt>Added by individual loans</dt>
            <dd>{money(r.granularityAddOn)}</dd>
          </div>
          <div>
            <dt>Effective number of loans</dt>
            <dd>{count(r.effectiveLoans, 1)} <span className="sub">of {count(r.numLoans)}</span></dd>
          </div>
          <div>
            <dt>Variance from the common factor</dt>
            <dd>{pct(r.systematicVarianceShare, 1)} <span className="sub">at correlation {r.assetCorrelation}</span></dd>
          </div>
          <div>
            <dt>Seed</dt>
            <dd>
              <span className="mono">{r.seed}</span>{' '}
              <button type="button" className="plain-text sub" onClick={() => onReuseSeed(r.seed)} title="A run with the same seed and inputs gives exactly these figures">use again</button>
            </dd>
          </div>
          <div>
            <dt>Computed in</dt>
            <dd>{count(r.elapsedMillis)} ms</dd>
          </div>
        </dl>
      </div>
    </>
  );
}

export default function CvarPage() {
  const [mode, setMode] = useState<Mode>('portfolio');
  const [loans, setLoans] = useState<LoanRiskProfile[]>(DEFAULT_LOANS);
  const [confidenceLevel, setConfidenceLevel] = useState(0.999);
  const [assetCorrelation, setAssetCorrelation] = useState('0.15');
  const [numScenarios, setNumScenarios] = useState(20_000);
  const [seed, setSeed] = useState('');
  const [importanceSampling, setImportanceSampling] = useState(true);
  const [outcome, setOutcome] = useState<Outcome | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [loading, setLoading] = useState(false);

  const updateLoan = (i: number, key: 'pd' | 'lgd' | 'ead', v: number) =>
    setLoans(loans.map((l, idx) => (idx === i ? { ...l, [key]: v } : l)));
  const addLoan = () => setLoans([...loans, { pd: 0.02, lgd: 0.4, ead: 200000 }]);
  const removeLoan = (i: number) => setLoans(loans.filter((_, idx) => idx !== i));

  const correlation = Number(assetCorrelation);
  const seedValue = seed.trim() === '' ? undefined : Number(seed.trim());
  const invalid = !(correlation >= 0 && correlation <= 0.6) || (seedValue !== undefined && !Number.isSafeInteger(seedValue));

  const choose = (next: Mode) => {
    setMode(next);
    setOutcome(null);
    setError(null);
  };

  const submit = async () => {
    setLoading(true);
    setError(null);
    const parameters: LossParameters = { confidenceLevel, numScenarios, assetCorrelation: correlation, seed: seedValue, importanceSampling };
    try {
      if (mode === 'portfolio') {
        const { result, ...portfolio } = await portfolioLossDistribution(parameters);
        setOutcome({ result, portfolio });
      } else {
        setOutcome({ result: await simulateCvar({ ...parameters, loans }) });
      }
    } catch (e) {
      setOutcome(null);
      setError(e);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div>
      <div className="page-head-row">
        <div>
          <h2>Loss distribution</h2>
          <p className="page-subtitle">
            Expected loss is what an average year costs. This is how much worse a bad year can be: defaults are tied
            together by one common factor, the state of the economy, so they cluster, and the simulation finds the loss
            that only one year in a hundred or a thousand exceeds, and the loans that drive it.
          </p>
        </div>
      </div>

      <div className="card">
        <div className="toolbar">
          <div className="segmented" role="tablist" aria-label="What to simulate">
            <button role="tab" aria-selected={mode === 'portfolio'} className={mode === 'portfolio' ? 'on' : ''} onClick={() => choose('portfolio')}>The portfolio</button>
            <button role="tab" aria-selected={mode === 'custom'} className={mode === 'custom' ? 'on' : ''} onClick={() => choose('custom')}>A custom portfolio</button>
          </div>
        </div>

        {mode === 'portfolio' ? (
          <p className="page-subtitle">
            Simulates the loans of the latest <Link to="/portfolio-risk">portfolio projection</Link>. Each loan defaults with its
            12-month probability and then loses its 12-month expected credit loss per unit of that probability, so the
            simulated average is the 12-month ECL exactly.
          </p>
        ) : (
          <>
            <table>
              <thead>
                <tr>
                  <th>Probability of default</th>
                  <th>Loss given default</th>
                  <th>Exposure ($)</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {loans.map((loan, i) => (
                  <tr key={i}>
                    <td><input aria-label={`PD of loan ${i + 1}`} type="number" step={0.001} min={0} max={1} value={loan.pd} onChange={(e) => updateLoan(i, 'pd', Number(e.target.value))} /></td>
                    <td><input aria-label={`LGD of loan ${i + 1}`} type="number" step={0.01} min={0} max={1} value={loan.lgd} onChange={(e) => updateLoan(i, 'lgd', Number(e.target.value))} /></td>
                    <td><input aria-label={`Exposure of loan ${i + 1}`} type="number" step={1000} min={1} value={loan.ead} onChange={(e) => updateLoan(i, 'ead', Number(e.target.value))} /></td>
                    <td className="num"><button className="danger-outline" onClick={() => removeLoan(i)} disabled={loans.length <= 1}>Remove</button></td>
                  </tr>
                ))}
              </tbody>
            </table>
            <div className="actions">
              <button className="secondary" onClick={addLoan}>Add a loan</button>
            </div>
          </>
        )}

        <div className="form-section" style={{ marginTop: '1rem' }}>
          <div className="section-title">Simulation</div>
          <div className="field-grid">
            <div className="field">
              <label htmlFor="confidenceLevel">Confidence level</label>
              <select id="confidenceLevel" value={confidenceLevel} onChange={(e) => setConfidenceLevel(Number(e.target.value))}>
                {CONFIDENCE.map(([value, label]) => <option key={value} value={value}>{label}</option>)}
              </select>
            </div>
            <div className="field">
              <label htmlFor="assetCorrelation">Asset correlation (0 to 0.6)</label>
              <input id="assetCorrelation" type="number" step={0.01} min={0} max={0.6} value={assetCorrelation} onChange={(e) => setAssetCorrelation(e.target.value)} />
            </div>
            <div className="field">
              <label htmlFor="numScenarios">Scenarios</label>
              <select id="numScenarios" value={numScenarios} onChange={(e) => setNumScenarios(Number(e.target.value))}>
                {SCENARIOS.map((n) => <option key={n} value={n}>{count(n)}</option>)}
              </select>
            </div>
            <div className="field">
              <label htmlFor="seed">Seed (empty for a fresh run)</label>
              <input id="seed" inputMode="numeric" value={seed} onChange={(e) => setSeed(e.target.value)} placeholder="a whole number" />
            </div>
            <div className="field" style={{ justifyContent: 'flex-end' }}>
              <label style={{ fontWeight: 400, color: 'var(--text)' }}>
                <input type="checkbox" checked={importanceSampling} onChange={(e) => setImportanceSampling(e.target.checked)} /> Importance sampling
              </label>
            </div>
          </div>
          <p className="chart-caption">
            0.15 is the correlation Basel prescribes for residential mortgages; 0 makes every default independent, and the
            tail collapses toward the expected loss.
          </p>
        </div>

        <div className="actions">
          <button onClick={submit} disabled={loading || invalid}>{loading ? 'Simulating...' : 'Simulate'}</button>
          {outcome?.portfolio && (
            <span className="sub">
              {count(outcome.portfolio.loans)} loans as of {outcome.portfolio.asOf}
              {outcome.portfolio.sampled && ', a sample scaled to the portfolio (slightly overstates name concentration)'}
            </span>
          )}
        </div>
        <ErrorBanner error={error} />
      </div>

      {outcome && <Result outcome={outcome} onReuseSeed={(s) => setSeed(String(s))} />}
    </div>
  );
}
