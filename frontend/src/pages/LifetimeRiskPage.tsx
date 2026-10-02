import { useMemo, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { Area, AreaChart, Bar, BarChart, CartesianGrid, Legend, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { compareScenarios, getLoan, termStructure } from '../api/client';
import ErrorBanner from '../components/ErrorBanner';
import LoanFeaturesForm from '../components/LoanFeaturesForm';
import { DEFAULT_LOAN } from '../components/loanDefaults';
import LoanPicker from '../components/LoanPicker';
import RiskMeter from '../components/RiskMeter';
import { AXIS, GRID, LEGEND, SCENARIO_STYLE, TOOLTIP, scenarioLabel, yearOf, yearTicks } from '../components/chartTheme';
import { axisMoney, compactMoney, duration, money, monthLabel, pct } from '../format';
import type { Extrapolation, LifetimeRiskInput, LoanFeatures, TermStructure } from '../api/types';

const DRIVER: Record<string, string> = {
  loan_age: 'Loan age',
  rate_incentive: 'Rate incentive (note rate minus market rate)',
  unemployment: 'Unemployment rate',
  unemployment_change_12m: '12-month change in unemployment',
  hpi_change_12m: '12-month change in house prices',
  mtm_ltv: 'Mark-to-market LTV',
};

const STAGE_BADGE = ['', 'badge-low', 'badge-medium', 'badge-high'];

function rangeNote(e: Extrapolation): string {
  const unit = e.driver === 'hpi_change_12m' ? (v: number) => `${(v * 100).toFixed(1)}%` : (v: number) => v.toFixed(e.driver === 'loan_age' ? 0 : 1);
  return `${e.months} month${e.months === 1 ? '' : 's'} ${e.direction} the range the model was trained on `
    + `(${e.direction === 'below' ? 'minimum' : 'maximum'} ${unit(e.trainedLimit)}; this path reaches ${unit(e.extreme)}), from ${monthLabel(e.firstMonth)}`;
}

function LoanFacts({ loan }: { loan: LoanFeatures }) {
  const facts = [
    `Credit score ${loan.creditScore}`,
    `LTV ${loan.originalLtv}%`,
    `DTI ${loan.originalDti}%`,
    `${loan.originalInterestRate}% over ${loan.originalLoanTerm} months`,
    money(loan.originalUpb),
    loan.propertyState,
    loan.originationMonth ? `originated ${monthLabel(loan.originationMonth)}` : 'new application',
  ];
  return (
    <div className="chips" style={{ marginTop: '0.75rem' }}>
      {facts.map((f) => <span key={f} className="chip" style={{ cursor: 'default' }}>{f}</span>)}
    </div>
  );
}

function Detail({ t }: { t: TermStructure }) {
  const outcome = useMemo(() => t.months.map((m) => ({
    month: m.month,
    Defaulted: +(m.cumulativeDefault * 100).toFixed(3),
    Active: +(m.survival * 100).toFixed(3),
    Prepaid: +(m.cumulativePrepay * 100).toFixed(3),
  })), [t]);
  const losses = useMemo(() => t.months.slice(0, 120).map((m) => ({ month: m.month, loss: +m.discountedExpectedLoss.toFixed(2), lgd: +(m.lgd * 100).toFixed(2) })), [t]);
  const macro = useMemo(() => t.months.slice(0, 120).map((m) => ({
    month: m.month,
    unemployment: +m.unemployment.toFixed(2),
    housePrices: +m.housePriceIndex.toFixed(1),
    ltv: +m.mtmLtv.toFixed(1),
    stressed: +(m.stressedProbability * 100).toFixed(1),
  })), [t]);

  const lifeTicks = useMemo(() => yearTicks(t.months.map((m) => m.month)), [t]);
  const decadeTicks = useMemo(() => yearTicks(t.months.slice(0, 120).map((m) => m.month), 6), [t]);

  const small = (key: 'unemployment' | 'housePrices' | 'ltv' | 'stressed', title: string, unit: string) => (
    <div>
      <div className="section-label">{title}</div>
      <ResponsiveContainer width="100%" height={130}>
        <LineChart data={macro} margin={{ top: 6, right: 8, bottom: 0, left: -12 }}>
          <CartesianGrid {...GRID} />
          <XAxis dataKey="month" ticks={decadeTicks} tickFormatter={yearOf} {...AXIS} />
          <YAxis domain={['auto', 'auto']} unit={unit} width={52} {...AXIS} />
          <Tooltip {...TOOLTIP} labelFormatter={(m) => monthLabel(String(m))} formatter={(v) => [`${v}${unit}`, title]} />
          <Line type="monotone" dataKey={key} stroke="var(--text)" strokeWidth={1.8} dot={false} isAnimationActive={false} />
        </LineChart>
      </ResponsiveContainer>
    </div>
  );

  return (
    <>
      <div className="widget-grid" style={{ marginBottom: '1.25rem' }}>
        <div className="card">
          <div className="card-head"><h3>Where the loan ends up</h3></div>
          <p className="page-subtitle">
            Each month the loan is still on the book, has prepaid, or has defaulted. The three always add to 100%.
          </p>
          <ResponsiveContainer width="100%" height={280}>
            <AreaChart data={outcome} margin={{ top: 6, right: 8, bottom: 0, left: -8 }}>
              <CartesianGrid {...GRID} />
              <XAxis dataKey="month" ticks={lifeTicks} tickFormatter={yearOf} {...AXIS} />
              {/* The three shares are rounded separately, so their sum can be 100.001: the axis stays at 100. */}
              <YAxis domain={[0, 100]} ticks={[0, 25, 50, 75, 100]} allowDataOverflow unit="%" {...AXIS} />
              <Tooltip {...TOOLTIP} labelFormatter={(m) => monthLabel(String(m))} formatter={(v, name) => [`${Number(v).toFixed(2)}%`, name]} />
              <Legend {...LEGEND} iconType="square" />
              <Area type="monotone" dataKey="Defaulted" stackId="1" stroke="var(--sig-high)" fill="var(--sig-high)" fillOpacity={0.85} isAnimationActive={false} />
              <Area type="monotone" dataKey="Active" stackId="1" stroke="var(--hop-1)" fill="var(--hop-2)" fillOpacity={0.8} isAnimationActive={false} />
              <Area type="monotone" dataKey="Prepaid" stackId="1" stroke="var(--hop-4)" fill="var(--hop-5)" fillOpacity={0.9} isAnimationActive={false} />
            </AreaChart>
          </ResponsiveContainer>
        </div>

        <div className="card">
          <div className="card-head"><h3>Loss emerging by month</h3></div>
          <p className="page-subtitle">
            Expected credit loss arising in each of the next ten years of months, discounted at the note rate. LGD
            starts at {pct(t.ecl.lgd)}{t.ecl.lgdPeak > t.ecl.lgd + 1e-9 ? ` and peaks at ${pct(t.ecl.lgdPeak)} as collateral falls` : ''}.
          </p>
          <ResponsiveContainer width="100%" height={280}>
            <BarChart data={losses} margin={{ top: 6, right: 8, bottom: 0, left: 0 }}>
              <CartesianGrid {...GRID} />
              <XAxis dataKey="month" ticks={decadeTicks} tickFormatter={yearOf} {...AXIS} />
              <YAxis tickFormatter={(v) => axisMoney(Number(v))} {...AXIS} />
              <Tooltip {...TOOLTIP} labelFormatter={(m) => monthLabel(String(m))} formatter={(v) => [money(Number(v)), 'Expected loss']} />
              <Bar dataKey="loss" fill="var(--text)" isAnimationActive={false} />
            </BarChart>
          </ResponsiveContainer>
        </div>
      </div>

      <div className="card">
        <div className="card-head"><h3>The macro path behind it</h3></div>
        <p className="page-subtitle">
          What the scenario assumes for this loan's state over the next ten years, and what it does to the loan's
          collateral. Probability of stress comes from the regime chain, started at the regime decoded
          for {monthLabel(t.assumptions.regimeAsOf)}.
        </p>
        <div className="mini-grid">
          {small('unemployment', 'Unemployment', '%')}
          {small('housePrices', 'House prices (today = 100)', '')}
          {small('ltv', 'Mark-to-market LTV', '%')}
          {small('stressed', 'Probability of stressed regime', '%')}
        </div>
      </div>

      <div className="card">
        <div className="card-head"><h3>How far to trust it</h3></div>
        {t.extrapolations.length === 0 ? (
          <p className="page-subtitle">Every driver of this path stays inside the range the model was trained on.</p>
        ) : (
          <>
            <p className="page-subtitle">
              A tree model holds its response at the edge of what it has seen. Where this path goes beyond the training
              data, the hazards below are the model's last fitted level, not an opinion about the new territory.
            </p>
            <ul className="list">
              {t.extrapolations.map((e) => (
                <li key={e.driver + e.direction} className="list-row">
                  <span className="grow" style={{ whiteSpace: 'normal' }}>
                    <b>{DRIVER[e.driver] ?? e.driver}</b>
                    <div className="sub">{rangeNote(e)}</div>
                  </span>
                </li>
              ))}
            </ul>
          </>
        )}
        <dl className="facts">
          <div><dt>Forecast starts after</dt><dd>{monthLabel(t.assumptions.forecastOrigin)}</dd></div>
          <div><dt>Market mortgage rate</dt><dd>{t.assumptions.marketRate.toFixed(2)}%</dd></div>
          <div><dt>Rate spread at origination</dt><dd>{t.assumptions.rateSpread >= 0 ? '+' : ''}{t.assumptions.rateSpread.toFixed(2)} pts</dd></div>
          <div><dt>State unemployment today</dt><dd>{t.assumptions.startUnemployment.toFixed(1)}%</dd></div>
          <div><dt>Drift overlay on default</dt><dd>×{t.assumptions.defaultOverlay.toFixed(3)}</dd></div>
          <div><dt>Forced-sale haircut</dt><dd>{pct(t.assumptions.liquidationHaircut, 0)}</dd></div>
          <div><dt>Discount rate</dt><dd>{t.assumptions.discountRate.toFixed(2)}%</dd></div>
          <div><dt>Model</dt><dd>{t.modelVersion}</dd></div>
        </dl>
      </div>
    </>
  );
}

export default function LifetimeRiskPage() {
  const [params] = useSearchParams();
  const linkedLoanId = params.get('loanId') ?? '';
  const [mode, setMode] = useState<'portfolio' | 'manual'>('portfolio');
  const [manual, setManual] = useState<LoanFeatures>(DEFAULT_LOAN);
  const [balance, setBalance] = useState('');
  const [daysPastDue, setDaysPastDue] = useState('');
  const [chosen, setChosen] = useState<LifetimeRiskInput | null>(null);
  const [scenario, setScenario] = useState('BASELINE');

  // Arriving from a loan's page: that loan is projected straight away, until another one is chosen.
  const linked = useQuery({
    queryKey: ['loan', linkedLoanId],
    queryFn: () => getLoan(linkedLoanId),
    enabled: linkedLoanId !== '' && chosen === null,
  });
  const input = useMemo<LifetimeRiskInput | null>(() => chosen ?? (linked.data ? { loan: linked.data } : null), [chosen, linked.data]);

  const project = (loan: LoanFeatures) => {
    setScenario('BASELINE');
    setChosen({
      loan,
      currentBalance: balance ? Number(balance) : undefined,
      daysPastDue: daysPastDue ? Number(daysPastDue) : undefined,
    });
  };

  const key = input ? JSON.stringify(input) : '';
  const comparison = useQuery({ queryKey: ['scenario-comparison', key], queryFn: () => compareScenarios(input!), enabled: input !== null, retry: false });
  const detail = useQuery({ queryKey: ['term-structure', key, scenario], queryFn: () => termStructure(input!, scenario), enabled: input !== null, retry: false });

  const curves = useMemo(() => {
    const c = comparison.data;
    if (!c) return [];
    return c.months.map((month, i) => {
      const row: Record<string, number | string> = { month };
      c.scenarios.forEach((s) => {
        row[s.scenario] = +(s.cumulativeDefault[i] * 100).toFixed(3);
      });
      return row;
    });
  }, [comparison.data]);

  const baseline = comparison.data?.scenarios.find((s) => s.scenario === 'BASELINE');
  const selected = comparison.data?.scenarios.find((s) => s.scenario === scenario);
  const t = detail.data?.termStructure;
  const warnings = detail.data?.warnings ?? comparison.data?.warnings ?? [];

  return (
    <div>
      <div className="page-head-row">
        <div>
          <h2>Lifetime risk</h2>
          <p className="page-subtitle">
            How one loan's risk unfolds month by month: the chance it defaults, prepays or stays on the book, and the
            expected credit loss that follows, under a baseline and under stress.
          </p>
        </div>
      </div>

      <div className="card">
        <div className="toolbar">
          <div className="segmented" role="tablist" aria-label="Which loan">
            <button role="tab" aria-selected={mode === 'portfolio'} className={mode === 'portfolio' ? 'on' : ''} onClick={() => setMode('portfolio')}>A loan in the portfolio</button>
            <button role="tab" aria-selected={mode === 'manual'} className={mode === 'manual' ? 'on' : ''} onClick={() => setMode('manual')}>A new application</button>
          </div>
        </div>
        {mode === 'portfolio' ? (
          <LoanPicker initialId={linkedLoanId} action="Project" busy={comparison.isFetching} onPick={project} />
        ) : (
          <>
            <LoanFeaturesForm value={manual} onChange={setManual} showOrigination />
            <div className="actions">
              <button onClick={() => project(manual)} disabled={comparison.isFetching}>
                {comparison.isFetching ? 'Projecting...' : 'Project'}
              </button>
            </div>
          </>
        )}
        <div className="form-section" style={{ marginTop: '1rem' }}>
          <div className="section-title">Current position (optional)</div>
          <div className="field-grid">
            <div className="field">
              <label htmlFor="currentBalance">Outstanding balance ($)</label>
              <input id="currentBalance" type="number" min={0} step={1000} value={balance} onChange={(e) => setBalance(e.target.value)} placeholder="scheduled balance" />
            </div>
            <div className="field">
              <label htmlFor="daysPastDue">Days past due</label>
              <input id="daysPastDue" type="number" min={0} step={1} value={daysPastDue} onChange={(e) => setDaysPastDue(e.target.value)} placeholder="0" />
            </div>
          </div>
        </div>
        {input && <LoanFacts loan={input.loan} />}
        <ErrorBanner error={comparison.error ?? detail.error ?? linked.error} />
        {linkedLoanId && linked.data === null && <p className="error-banner">Loan {linkedLoanId} is not in the portfolio.</p>}
      </div>

      {warnings.length > 0 && (
        <div className="warn-banner" role="status">
          {warnings.map((w) => <div key={w}>{w}</div>)}
        </div>
      )}

      {baseline && selected && comparison.data && (
        <>
          <div className="kpi-row" style={{ marginTop: '1.25rem' }}>
            <div className="kpi">
              <div className="label">Default within 12 months</div>
              <div className="value">{pct(selected.summary.pd12m)}</div>
              <div style={{ margin: '0.5rem 0 0.2rem' }}><RiskMeter probability={selected.summary.pd12m} small /></div>
              <div className="hint">{scenarioLabel(scenario)}</div>
            </div>
            <div className="kpi">
              <div className="label">Default over its life</div>
              <div className="value">{pct(selected.summary.pdLifetime)}</div>
              <div className="hint">prepays {pct(selected.summary.prepayLifetime, 0)}, runs to term {pct(selected.summary.maturityProbability, 0)}</div>
            </div>
            <div className="kpi">
              <div className="label">Expected time on book</div>
              <div className="value">{duration(selected.summary.expectedLifeMonths)}</div>
              <div className="hint">of {comparison.data.remainingMonths} months remaining</div>
            </div>
            <div className="kpi accent">
              <div className="label">Expected credit loss</div>
              <div className="value">{compactMoney(selected.ecl.eclIfrs9)}</div>
              <div className="hint">IFRS 9 stage {selected.ecl.stage} &middot; CECL {compactMoney(selected.ecl.eclCecl)}</div>
            </div>
          </div>

          <div className="card">
            <div className="card-head">
              <h3>Scenarios</h3>
              <span className={`badge ${STAGE_BADGE[selected.ecl.stage]}`}>Stage {selected.ecl.stage}: {selected.ecl.stageReason}</span>
            </div>
            <table>
              <thead>
                <tr>
                  <th>Scenario</th>
                  <th className="num">12-month PD</th>
                  <th className="num">Lifetime PD</th>
                  <th className="num">Time on book</th>
                  <th className="num">Stage</th>
                  <th className="num">12-month ECL</th>
                  <th className="num">Lifetime ECL</th>
                </tr>
              </thead>
              <tbody>
                {comparison.data.scenarios.map((s) => (
                  <tr key={s.scenario} className={s.scenario === scenario ? 'selected' : ''} onClick={() => setScenario(s.scenario)} style={{ cursor: 'pointer' }}>
                    <td>
                      <button type="button" className="plain-text" aria-pressed={s.scenario === scenario} title={s.description}>
                        <span className="swatch" style={{ background: SCENARIO_STYLE[s.scenario]?.color }} />
                        {scenarioLabel(s.scenario)}
                      </button>
                    </td>
                    <td className="num">{pct(s.summary.pd12m)}</td>
                    <td className="num">{pct(s.summary.pdLifetime)}</td>
                    <td className="num">{duration(s.summary.expectedLifeMonths)}</td>
                    <td className="num">{s.ecl.stage}</td>
                    <td className="num">{money(s.ecl.ecl12m)}</td>
                    <td className="num">{money(s.ecl.eclLifetime)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            <p className="page-subtitle" style={{ margin: '0.75rem 0 0.5rem' }}>{selected.description}</p>
            <ResponsiveContainer width="100%" height={300}>
              <LineChart data={curves} margin={{ top: 6, right: 8, bottom: 0, left: -8 }}>
                <CartesianGrid {...GRID} />
                <XAxis dataKey="month" ticks={yearTicks(comparison.data.months)} tickFormatter={yearOf} {...AXIS} />
                <YAxis unit="%" {...AXIS} />
                <Tooltip {...TOOLTIP} labelFormatter={(m) => monthLabel(String(m))} formatter={(v, name) => [`${Number(v).toFixed(2)}%`, scenarioLabel(String(name))]} />
                <Legend {...LEGEND} formatter={(name) => scenarioLabel(String(name))} />
                {comparison.data.scenarios.map((s) => (
                  <Line
                    key={s.scenario}
                    type="monotone"
                    dataKey={s.scenario}
                    stroke={SCENARIO_STYLE[s.scenario]?.color ?? 'var(--text)'}
                    strokeDasharray={SCENARIO_STYLE[s.scenario]?.dash}
                    strokeWidth={s.scenario === scenario ? 3 : 1.8}
                    dot={false}
                    isAnimationActive={false}
                  />
                ))}
              </LineChart>
            </ResponsiveContainer>
            <p className="chart-caption">Cumulative probability of default over the next ten years, by scenario. Select a row to see that scenario in detail below.</p>
          </div>

          {t ? <Detail t={t} /> : <div className="card"><div className="skeleton" style={{ height: 180 }} /></div>}
        </>
      )}
    </div>
  );
}
