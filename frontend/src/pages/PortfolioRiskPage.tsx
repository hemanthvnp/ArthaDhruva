import { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { keepPreviousData, useQuery, useQueryClient } from '@tanstack/react-query';
import { Area, AreaChart, CartesianGrid, Legend, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { downloadPortfolioLoanResultsCsv, getPortfolioRisk, portfolioLoanResults, portfolioRiskHistory, startPortfolioRun } from '../api/client';
import ErrorBanner from '../components/ErrorBanner';
import RiskMeter from '../components/RiskMeter';
import { AXIS, GRID, LEGEND, SCENARIO_STYLE, TOOLTIP, scenarioLabel, yearOf, yearTicks } from '../components/chartTheme';
import { axisMoney, bps, compactMoney, count, money, monthLabel, pct } from '../format';
import { isActive, usePortfolioRun } from '../hooks/usePortfolioRun';
import type { CvarResult, LoanResultSort, PortfolioRun, PortfolioSnapshot, ScenarioRisk, StageBreakdown } from '../api/types';

const ORDER = ['BASELINE', 'ADVERSE', 'SEVERELY_ADVERSE', 'RATES_UP_200', 'RATES_DOWN_200'];
const DRIVER: Record<string, string> = {
  loan_age: 'loan age',
  rate_incentive: 'rate incentive',
  unemployment: 'unemployment',
  unemployment_change_12m: 'change in unemployment',
  hpi_change_12m: 'house-price change',
  mtm_ltv: 'mark-to-market LTV',
};

function Progress({ run }: { run: PortfolioRun }) {
  const share = run.loansTotal > 0 ? run.loansDone / run.loansTotal : 0;
  return (
    <div className="card" role="status" aria-live="polite">
      <div className="card-head">
        <h3>{run.status === 'QUEUED' ? 'Waiting for a free worker' : 'Projecting the portfolio'}</h3>
        <span className="sub">{count(run.loansDone)} of {count(run.loansTotal)} loans</span>
      </div>
      <div className="progress" aria-hidden="true"><span style={{ width: `${Math.max(2, share * 100)}%` }} /></div>
      <p className="chart-caption">
        Every loan is projected to maturity under {run.scenarios.length} scenario{run.scenarios.length === 1 ? '' : 's'}.
        This runs on the server; you can leave the page and come back.
      </p>
    </div>
  );
}

function Runoff({ scenarios }: { scenarios: ScenarioRisk[] }) {
  const months = scenarios[0].series.months;
  const ticks = useMemo(() => yearTicks(months), [months]);
  const cumulative = useMemo(() => {
    const running = scenarios.map(() => 0);
    return months.map((month, i) => {
      const row: Record<string, number | string> = { month };
      scenarios.forEach((s, k) => {
        running[k] += s.series.expectedLoss[i];
        row[s.scenario] = Math.round(running[k]);
      });
      return row;
    });
  }, [scenarios, months]);
  const baseline = scenarios.find((s) => s.scenario === 'BASELINE') ?? scenarios[0];
  const balance = useMemo(() => {
    const rows: { month: string; 'On the book': number; Defaulted: number; Prepaid: number }[] = [];
    let defaulted = 0, prepaid = 0;
    for (let i = 0; i < months.length; i++) {
      defaulted += baseline.series.defaults[i];
      prepaid += baseline.series.prepayments[i];
      rows.push({ month: months[i], 'On the book': Math.round(baseline.series.balanceAtRisk[i]), Defaulted: Math.round(defaulted), Prepaid: Math.round(prepaid) });
    }
    return rows;
  }, [baseline, months]);

  return (
    <div className="widget-grid" style={{ marginBottom: '1.25rem' }}>
      <div className="card">
        <div className="card-head"><h3>Loss emerging</h3></div>
        <p className="page-subtitle">Cumulative expected credit loss over the next ten years, discounted, by scenario.</p>
        <ResponsiveContainer width="100%" height={280}>
          <LineChart data={cumulative} margin={{ top: 6, right: 8, bottom: 0, left: 4 }}>
            <CartesianGrid {...GRID} />
            <XAxis dataKey="month" ticks={ticks} tickFormatter={yearOf} {...AXIS} />
            <YAxis tickFormatter={(v) => axisMoney(Number(v))} {...AXIS} />
            <Tooltip {...TOOLTIP} labelFormatter={(m) => monthLabel(String(m))} formatter={(v, name) => [money(Number(v)), scenarioLabel(String(name))]} />
            <Legend {...LEGEND} formatter={(name) => scenarioLabel(String(name))} />
            {scenarios.map((s) => (
              <Line key={s.scenario} type="monotone" dataKey={s.scenario} stroke={SCENARIO_STYLE[s.scenario]?.color ?? 'var(--text)'}
                strokeDasharray={SCENARIO_STYLE[s.scenario]?.dash} strokeWidth={2.2} dot={false} isAnimationActive={false} />
            ))}
          </LineChart>
        </ResponsiveContainer>
      </div>
      <div className="card">
        <div className="card-head"><h3>How the book runs off</h3></div>
        <p className="page-subtitle">Baseline: balance still on the book each month, and where the rest has gone.</p>
        <ResponsiveContainer width="100%" height={280}>
          <AreaChart data={balance} margin={{ top: 6, right: 8, bottom: 0, left: 4 }}>
            <CartesianGrid {...GRID} />
            <XAxis dataKey="month" ticks={ticks} tickFormatter={yearOf} {...AXIS} />
            <YAxis tickFormatter={(v) => axisMoney(Number(v))} {...AXIS} />
            <Tooltip {...TOOLTIP} labelFormatter={(m) => monthLabel(String(m))} formatter={(v, name) => [money(Number(v)), name]} />
            <Legend {...LEGEND} iconType="square" />
            <Area type="monotone" dataKey="Defaulted" stackId="1" stroke="var(--sig-high)" fill="var(--sig-high)" fillOpacity={0.85} isAnimationActive={false} />
            <Area type="monotone" dataKey="On the book" stackId="1" stroke="var(--hop-1)" fill="var(--hop-2)" fillOpacity={0.8} isAnimationActive={false} />
            <Area type="monotone" dataKey="Prepaid" stackId="1" stroke="var(--hop-4)" fill="var(--hop-5)" fillOpacity={0.9} isAnimationActive={false} />
          </AreaChart>
        </ResponsiveContainer>
      </div>
    </div>
  );
}

const STAGE_BADGE = ['', 'badge-low', 'badge-medium', 'badge-high'];
const SORTS: [LoanResultSort, string][] = [
  ['eclLifetime', 'Lifetime loss'],
  ['eclIfrs9', 'Allowance'],
  ['pd12m', '12-month PD'],
  ['exposure', 'Exposure'],
  ['loanId', 'Loan ID'],
];
const PAGE_SIZE = 12;

/** The loans behind the totals: stage and allowance loan by loan, as an auditor would ask to see them. */
function LoanListing({ asOf, stages }: { asOf: string; stages: StageBreakdown[] }) {
  const [stage, setStage] = useState<number | undefined>(undefined);
  const [sort, setSort] = useState<LoanResultSort>('eclLifetime');
  const [page, setPage] = useState(0);
  const [exportError, setExportError] = useState<unknown>(null);
  const listing = useQuery({
    queryKey: ['portfolio-risk-loans', asOf, stage, sort, page],
    queryFn: () => portfolioLoanResults({ stage, sort, limit: PAGE_SIZE, offset: page * PAGE_SIZE }),
    placeholderData: keepPreviousData,
  });
  const filter = (next: number | undefined) => {
    setStage(next);
    setPage(0);
  };
  const total = listing.data?.total ?? 0;
  const pages = Math.max(1, Math.ceil(total / PAGE_SIZE));

  return (
    <div className="card">
      <div className="card-head">
        <h3>Loan by loan</h3>
        <button className="secondary" onClick={() => { setExportError(null); downloadPortfolioLoanResultsCsv().catch(setExportError); }}>Export CSV</button>
      </div>
      <div className="toolbar">
        <div className="segmented" role="tablist" aria-label="Stage">
          <button role="tab" aria-selected={stage === undefined} className={stage === undefined ? 'on' : ''} onClick={() => filter(undefined)}>All</button>
          {stages.map((s) => (
            <button key={s.stage} role="tab" aria-selected={stage === s.stage} className={stage === s.stage ? 'on' : ''} onClick={() => filter(s.stage)}>
              Stage {s.stage}<span className="count">{count(s.loans)}</span>
            </button>
          ))}
        </div>
        <label className="sub" style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
          Largest first by
          <select aria-label="Order" value={sort} onChange={(e) => { setSort(e.target.value as LoanResultSort); setPage(0); }}>
            {SORTS.map(([key, label]) => <option key={key} value={key}>{label}</option>)}
          </select>
        </label>
      </div>
      <ErrorBanner error={listing.error ?? exportError} />
      {listing.isPending && <div className="skeleton" style={{ height: 140 }} />}
      {listing.data && listing.data.loans.length === 0 && <p className="empty">No loans in this stage.</p>}
      {listing.data && listing.data.loans.length > 0 && (
        <div className="table-scroll">
          <table>
            <thead>
              <tr>
                <th>Loan</th>
                <th>Stage</th>
                <th className="num">Exposure</th>
                <th className="num">12-month PD</th>
                <th className="num">Lifetime PD</th>
                <th className="num">LGD</th>
                <th className="num">Allowance</th>
                <th className="num">Lifetime loss</th>
              </tr>
            </thead>
            <tbody>
              {listing.data.loans.map((l) => (
                <tr key={l.loanId}>
                  <td><Link to={`/loans/${encodeURIComponent(l.loanId)}`}>{l.loanId}</Link> <span className="sub">{l.state}</span></td>
                  <td><span className={`badge ${STAGE_BADGE[l.stage] ?? ''}`} title={l.stageReason}>Stage {l.stage}</span></td>
                  <td className="num">{money(l.exposure)}</td>
                  <td className="num">{pct(l.pd12m)}</td>
                  <td className="num">{pct(l.pdLifetime)}</td>
                  <td className="num">{pct(l.lgd, 1)}</td>
                  <td className="num">{money(l.eclIfrs9)}</td>
                  <td className="num">{money(l.eclLifetime)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {total > PAGE_SIZE && (
        <div className="actions">
          <button className="secondary" onClick={() => setPage(page - 1)} disabled={page === 0}>Previous</button>
          <span className="sub">Page {page + 1} of {pages} &middot; {count(total)} loans</span>
          <button className="secondary" onClick={() => setPage(page + 1)} disabled={page + 1 >= pages}>Next</button>
        </div>
      )}
      <p className="chart-caption">
        Baseline scenario. A stage 1 loan carries twelve months of expected loss as its allowance, a stage 2 loan its lifetime
        loss, a stage 3 loan the loss on the balance outstanding; hover a stage for the reason. The export has every loan.
      </p>
    </div>
  );
}

/** The other half of the picture: the allowance covers an average year, this is a bad one. */
function BadYear({ loss, ecl12m }: { loss: CvarResult | null; ecl12m: number }) {
  return (
    <div className="card">
      <div className="card-head">
        <h3>A bad year</h3>
        <Link to="/cvar">Explore the loss distribution &rarr;</Link>
      </div>
      {loss === null ? (
        <p className="page-subtitle" style={{ margin: 0 }}>
          The loss simulation was busy when this run finished. Open the loss distribution to simulate it now.
        </p>
      ) : (
        <>
          <p className="page-subtitle">
            Twelve months of expected loss come to {money(ecl12m)}. Because defaults cluster when the economy turns, one year
            in a thousand costs far more.
          </p>
          <ul className="list">
            <li className="list-row">
              <span className="grow">Expected loss<div className="sub">an average year</div></span>
              <span className="bar" aria-hidden="true"><span style={{ width: `${(loss.expectedLoss / loss.conditionalValueAtRisk) * 100}%` }} /></span>
              <span className="num">{money(loss.expectedLoss)}</span>
            </li>
            <li className="list-row">
              <span className="grow">Value at risk, {pct(loss.confidenceLevel, 1)}<div className="sub">exceeded one year in {count(Math.round(1 / (1 - loss.confidenceLevel)))}</div></span>
              <span className="bar" aria-hidden="true"><span style={{ width: `${(loss.valueAtRisk / loss.conditionalValueAtRisk) * 100}%` }} /></span>
              <span className="num">{money(loss.valueAtRisk)}</span>
            </li>
            <li className="list-row">
              <span className="grow">Expected shortfall<div className="sub">the average of the years beyond that</div></span>
              <span className="bar" aria-hidden="true"><span style={{ width: '100%' }} /></span>
              <span className="num">{money(loss.conditionalValueAtRisk)}</span>
            </li>
            <li className="list-row">
              <span className="grow"><b>Unexpected loss</b><div className="sub">value at risk above the expected loss: what capital is for</div></span>
              <span className="num">{money(loss.unexpectedLoss)}</span>
            </li>
          </ul>
          <p className="chart-caption">
            One-factor Gaussian copula at an asset correlation of {loss.assetCorrelation}, {count(loss.numScenarios)} scenarios.
            Value at risk is known to within {money(loss.valueAtRiskConfidenceInterval[0])} and {money(loss.valueAtRiskConfidenceInterval[1])}.
          </p>
        </>
      )}
    </div>
  );
}

function Trend() {
  const history = useQuery({ queryKey: ['portfolio-risk-history'], queryFn: () => portfolioRiskHistory('BASELINE') });
  const points = useMemo(() => [...(history.data ?? [])].reverse().map((s) => ({
    asOf: s.asOf,
    'IFRS 9 allowance': Math.round(s.eclIfrs9),
    'Lifetime ECL': Math.round(s.eclLifetime),
  })), [history.data]);
  return (
    <div className="card">
      <div className="card-head"><h3>Allowance over time</h3></div>
      {points.length < 2 ? (
        <p className="page-subtitle" style={{ margin: 0 }}>
          {points.length === 0 ? 'No snapshot yet.' : `One snapshot so far (${points[0].asOf}).`} Each run stores one per day, and a
          scheduled job adds one on the first of every month; the trend appears once there are two.
        </p>
      ) : (
        <ResponsiveContainer width="100%" height={240}>
          <LineChart data={points} margin={{ top: 6, right: 8, bottom: 0, left: 4 }}>
            <CartesianGrid {...GRID} />
            <XAxis dataKey="asOf" {...AXIS} />
            <YAxis tickFormatter={(v) => axisMoney(Number(v))} {...AXIS} />
            <Tooltip {...TOOLTIP} formatter={(v, name) => [money(Number(v)), name]} />
            <Legend {...LEGEND} />
            <Line type="monotone" dataKey="IFRS 9 allowance" stroke="var(--text)" strokeWidth={2.2} dot={{ r: 3, fill: 'var(--surface)' }} isAnimationActive={false} />
            <Line type="monotone" dataKey="Lifetime ECL" stroke="var(--hop-3)" strokeWidth={2} strokeDasharray="6 4" dot={{ r: 3, fill: 'var(--surface)' }} isAnimationActive={false} />
          </LineChart>
        </ResponsiveContainer>
      )}
    </div>
  );
}

function Results({ snapshots, portfolioLoans }: { snapshots: PortfolioSnapshot[]; portfolioLoans: number }) {
  const ordered = useMemo(() => [...snapshots].sort((a, b) => ORDER.indexOf(a.scenario) - ORDER.indexOf(b.scenario)), [snapshots]);
  const details = ordered.map((s) => s.detail).filter((d): d is ScenarioRisk => d !== null);
  const head = ordered.find((s) => s.scenario === 'BASELINE') ?? ordered[0];
  const base = head.detail!;
  const stageExposure = base.stages.map((s) => s.exposure);
  const extrapolated = Object.entries(details[details.length - 1].extrapolatedLoans);

  return (
    <>
      {portfolioLoans !== head.loans && (
        <p className="warn-banner" role="status" style={{ marginTop: 0, marginBottom: '1rem' }}>
          The portfolio now has {count(portfolioLoans)} loans; these results cover the {count(head.loans)} it
          had on {head.asOf}. Run the projection again to bring them up to date.
        </p>
      )}
      <p className="page-subtitle">
        As of {head.asOf} &middot; {count(head.loans)} loans
        {head.loansExcluded > 0 && `, ${count(head.loansExcluded)} could not be projected`}
        {head.projectedLoans < head.loans - head.loansExcluded && ` (a sample of ${count(head.projectedLoans)} was projected and scaled up)`}
        {' '}&middot; {head.modelVersion}
      </p>

      <div className="kpi-row">
        <div className="kpi">
          <div className="label">Exposure</div>
          <div className="value">{compactMoney(base.exposure)}</div>
          <div className="hint">outstanding balance</div>
        </div>
        <div className="kpi">
          <div className="label">12-month PD</div>
          <div className="value">{pct(base.pd12m)}</div>
          <div style={{ margin: '0.5rem 0 0.2rem' }}><RiskMeter probability={base.pd12m} small /></div>
          <div className="hint">exposure-weighted, lifetime {pct(base.pdLifetime)}</div>
        </div>
        <div className="kpi accent">
          <div className="label">Loss allowance (IFRS 9)</div>
          <div className="value">{compactMoney(base.eclIfrs9)}</div>
          <div className="hint">{bps(base.coverage)} of exposure</div>
        </div>
        <div className="kpi">
          <div className="label">Lifetime ECL (CECL)</div>
          <div className="value">{compactMoney(base.eclCecl)}</div>
          <div className="hint">
            {base.eclRelativeStandardError !== null ? `sampled: ± ${pct(1.96 * base.eclRelativeStandardError, 0)}` : 'every loan projected'}
          </div>
        </div>
      </div>

      <div className="card">
        <div className="card-head"><h3>Staging</h3></div>
        <div className="mix" role="img" aria-label={`Stage 1 ${base.stages[0].loans} loans, stage 2 ${base.stages[1].loans}, stage 3 ${base.stages[2].loans}`}>
          {stageExposure[0] > 0 && <span className="low" style={{ flexGrow: stageExposure[0] }} />}
          {stageExposure[1] > 0 && <span className="mid" style={{ flexGrow: stageExposure[1] }} />}
          {stageExposure[2] > 0 && <span className="high" style={{ flexGrow: stageExposure[2] }} />}
        </div>
        <div className="mix-legend">
          <span><i style={{ background: 'var(--sig-low)' }} /><b>{count(base.stages[0].loans)}</b>Stage 1 &middot; 12-month ECL</span>
          <span><i style={{ background: 'var(--sig-mid)' }} /><b>{count(base.stages[1].loans)}</b>Stage 2 &middot; lifetime ECL</span>
          <span><i style={{ background: 'var(--sig-high)' }} /><b>{count(base.stages[2].loans)}</b>Stage 3 &middot; credit-impaired, 90+ days past due</span>
        </div>
      </div>

      <div className="card">
        <div className="card-head"><h3>Under stress</h3></div>
        <table>
          <thead>
            <tr>
              <th>Scenario</th>
              <th className="num">12-month PD</th>
              <th className="num">Lifetime PD</th>
              <th className="num">Stage 2 loans</th>
              <th className="num">Allowance (IFRS 9)</th>
              <th className="num">Lifetime ECL</th>
              <th className="num">vs baseline</th>
            </tr>
          </thead>
          <tbody>
            {details.map((s) => (
              <tr key={s.scenario}>
                <td title={s.description}><span className="swatch" style={{ background: SCENARIO_STYLE[s.scenario]?.color }} />{scenarioLabel(s.scenario)}</td>
                <td className="num">{pct(s.pd12m)}</td>
                <td className="num">{pct(s.pdLifetime)}</td>
                <td className="num">{count(s.stages[1].loans)}</td>
                <td className="num">{money(s.eclIfrs9)}</td>
                <td className="num">{money(s.eclLifetime)}</td>
                <td className="num">{s === base ? '' : `×${(s.eclIfrs9 / Math.max(base.eclIfrs9, 1e-9)).toFixed(1)}`}</td>
              </tr>
            ))}
          </tbody>
        </table>
        <p className="chart-caption">
          Stress moves the allowance twice: defaults become likelier, and loans whose risk has risen enough since origination move to
          stage 2, where the allowance is the lifetime loss instead of twelve months of it.
        </p>
      </div>

      <Runoff scenarios={details} />

      <div className="widget-grid" style={{ marginBottom: '1.25rem' }}>
        <div className="card">
          <div className="card-head"><h3>Where the loss sits</h3></div>
          <ul className="list">
            {base.states.slice(0, 8).map((s) => (
              <li key={s.state} className="list-row">
                <span className="grow">
                  <b>{s.state}</b>
                  <div className="sub">{count(s.loans)} loans &middot; {compactMoney(s.exposure)}</div>
                </span>
                <span className="bar" aria-hidden="true"><span style={{ width: `${(s.eclLifetime / base.states[0].eclLifetime) * 100}%` }} /></span>
                <span className="num">{money(s.eclLifetime)}</span>
              </li>
            ))}
          </ul>
          <p className="chart-caption">Lifetime ECL by state, baseline.</p>
        </div>
        <BadYear loss={base.lossDistribution ?? null} ecl12m={base.ecl12m} />
      </div>

      <LoanListing asOf={head.asOf} stages={base.stages} />

      <Trend />

      <div className="card">
        <div className="card-head"><h3>How far to trust it</h3></div>
        {base.pd12m > 0 && base.exposure > 0 && (
          <p className="page-subtitle">
            The baseline allowance is small because loss given default is small, about {pct(base.ecl12m / (base.pd12m * base.exposure), 1)} of
            the balance. Default here means the first time a loan is 90 days past due, and in the years the models were fitted
            on, with house prices rising, most such loans cured or were repaid in full from a sale. When prices fall the
            shortfall on a forced sale takes over, which is why the stressed allowances above are many times the baseline.
            Details are on the <Link to="/models">model governance</Link> page.
          </p>
        )}
        {extrapolated.length > 0 && (
          <>
            <p className="page-subtitle">
              Loans whose {scenarioLabel(details[details.length - 1].scenario).toLowerCase()} path leaves the range the model was trained on. There the
              hazards hold their last fitted level; the extra loss from falling house prices comes from re-marking the collateral.
            </p>
            <div className="chips">
              {extrapolated.map(([key, loans]) => {
                const [driver, direction] = key.split(':');
                return <span key={key} className="chip" style={{ cursor: 'default' }}>{count(loans)} {loans === 1 ? 'loan' : 'loans'}: {DRIVER[driver] ?? driver} {direction} range</span>;
              })}
            </div>
          </>
        )}
      </div>
    </>
  );
}

export default function PortfolioRiskPage() {
  const queryClient = useQueryClient();
  const view = useQuery({ queryKey: ['portfolio-risk'], queryFn: getPortfolioRisk });
  const [started, setStarted] = useState<PortfolioRun | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [starting, setStarting] = useState(false);

  // Watch the run started here, or one already in progress when the page was opened.
  const run = usePortfolioRun(started ?? view.data?.run ?? null, () => {
    void queryClient.invalidateQueries({ queryKey: ['portfolio-risk'] });
    void queryClient.invalidateQueries({ queryKey: ['portfolio-risk-history'] });
    void queryClient.invalidateQueries({ queryKey: ['portfolio-risk-loans'] });
  });

  const start = async () => {
    setStarting(true);
    setError(null);
    try {
      setStarted(await startPortfolioRun());
    } catch (e) {
      setError(e);
    } finally {
      setStarting(false);
    }
  };

  const snapshots = view.data?.snapshots ?? [];
  const running = isActive(run);

  return (
    <div>
      <div className="page-head-row">
        <div>
          <h2>Portfolio risk</h2>
          <p className="page-subtitle">
            Every loan projected to maturity and added up: the loss allowance, how it is staged, how it responds to
            stress, and when the losses are expected to arrive.
          </p>
        </div>
        <button onClick={start} disabled={starting || running}>
          {running ? 'Running...' : snapshots.length > 0 ? 'Run again' : 'Run projection'}
        </button>
      </div>

      <ErrorBanner error={error ?? view.error} />
      {run && running && <Progress run={run} />}
      {run?.status === 'FAILED' && <p className="error-banner">The last run did not finish: {run.error ?? 'unknown error'}</p>}

      {view.isPending ? (
        <div className="card"><div className="skeleton" style={{ height: 160 }} /></div>
      ) : snapshots.length === 0 ? (
        !running && (
          <div className="card">
            <p className="empty">
              No projection yet. Run one to see the portfolio's expected credit loss, staging and stress results. It takes a few
              seconds for a few hundred loans and continues on the server if you navigate away.
            </p>
          </div>
        )
      ) : (
        <Results snapshots={snapshots} portfolioLoans={view.data?.portfolioLoans ?? snapshots[0].loans} />
      )}
    </div>
  );
}
