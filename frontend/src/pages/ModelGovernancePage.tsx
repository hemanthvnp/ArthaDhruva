import { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Bar, BarChart, CartesianGrid, Legend, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { listModels, modelCard, pdDrift, survivalBacktest } from '../api/client';
import ErrorBanner from '../components/ErrorBanner';
import { AXIS, GRID, LEGEND, TOOLTIP, yearOf, yearTicks } from '../components/chartTheme';
import { count, dateTime, monthLabel, pct } from '../format';
import { featureLabel } from '../labels';
import type { FeatureDrift, ModelCard, ModelEntry, OverlayCause, OverlayTrial, PdCard, SurvivalBacktest, SurvivalCard } from '../api/types';

const HEADLINE: Record<string, string> = {
  outOfTimeAuc: 'Out-of-time AUC',
  outOfTimeKs: 'Out-of-time KS',
  outOfTimeVintages: 'Tested on vintages',
  loans: 'Loans',
  outOfTimeDefaultAuc: 'Out-of-time AUC, default',
  outOfTimePrepayAuc: 'Out-of-time AUC, prepayment',
  outOfTimeLogLoss: 'Out-of-time log loss',
  ageOnlyBaselineLogLoss: 'Log loss of an age-only table',
  loanMonths: 'Loan-months fitted',
  defaultAssetCorrelation: 'Default asset correlation',
};

const DRIFT_BADGE: Record<FeatureDrift['status'], string> = { STABLE: 'badge-low', MODERATE: 'badge-medium', SIGNIFICANT: 'badge-high', NO_DATA: '' };
const DRIFT_LABEL: Record<FeatureDrift['status'], string> = { STABLE: 'Stable', MODERATE: 'Moderate shift', SIGNIFICANT: 'Significant shift', NO_DATA: 'No data' };

const CAUSE = {
  default: { label: 'Default', noun: 'default', predicted: 'predicted_default', actual: 'actual_default', empirical: 'empirical_default' },
  prepay: { label: 'Prepayment', noun: 'prepayment', predicted: 'predicted_prepay', actual: 'actual_prepay', empirical: 'empirical_prepay' },
} as const;
type Cause = keyof typeof CAUSE;

const signed = (v: number, digits: number) => `${v > 0 ? '+' : ''}${v.toFixed(digits)}`;
/** The range of data each driver of the survival model was fitted on, in that driver's own unit. */
const ENVELOPE: Record<string, (lo: number, hi: number) => string> = {
  loan_age: (lo, hi) => `${lo.toFixed(0)} to ${hi.toFixed(0)} months`,
  rate_incentive: (lo, hi) => `${signed(lo, 2)} to ${signed(hi, 2)} pts`,
  unemployment: (lo, hi) => `${lo.toFixed(1)}% to ${hi.toFixed(1)}%`,
  unemployment_change_12m: (lo, hi) => `${signed(lo, 1)} to ${signed(hi, 1)} pts`,
  hpi_change_12m: (lo, hi) => `${signed(lo * 100, 1)}% to ${signed(hi * 100, 1)}%`,
  mtm_ltv: (lo, hi) => `${lo.toFixed(1)}% to ${hi.toFixed(1)}%`,
};

function figure(value: number | string): string {
  if (typeof value === 'string') return value;
  return value >= 1000 ? count(value) : String(Number(value.toPrecision(4)));
}

function Inventory({ models, selected, onSelect }: { models: ModelEntry[]; selected: string; onSelect: (id: string) => void }) {
  return (
    <div className="card">
      <div className="card-head"><h3>Model inventory</h3></div>
      <div className="table-scroll">
        <table>
          <thead>
            <tr>
              <th>Model</th>
              <th>Used for</th>
              <th className="num">Tier</th>
              <th>Status</th>
              <th>Version</th>
              <th>Artifact checksum</th>
            </tr>
          </thead>
          <tbody>
            {models.map((m) => (
              <tr key={m.id} className={m.id === selected ? 'selected' : ''} onClick={() => onSelect(m.id)} style={{ cursor: 'pointer' }}>
                <td style={{ minWidth: 200 }}>
                  <button type="button" className="plain-text" aria-pressed={m.id === selected}><b>{m.name}</b></button>
                  <div className="sub">{m.algorithm}</div>
                </td>
                <td style={{ minWidth: 260, maxWidth: 360 }}>{m.purpose}</td>
                <td className="num">{m.tier}</td>
                <td><span className={`badge ${m.validated ? 'badge-low' : 'badge-medium'}`}>{m.validated ? 'Validated' : 'Not validated'}</span></td>
                <td className="mono">{m.version}</td>
                <td>
                  <span className="mono" title={m.sha256 ?? undefined}>{m.sha256 ? m.sha256.slice(0, 12) : 'no artifact'}</span>
                  {m.checksumVerified && <div className="sub">matched its card at start-up</div>}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p className="chart-caption">
        Tier 1 models feed expected credit loss and capital figures; tier 2 support analysts. A model is listed as validated only
        if it ships a model card with out-of-time validation. Where a card declares a checksum, the service refuses to start on
        an artifact that does not match it.
      </p>
    </div>
  );
}

function CardDetail({ model, card }: { model: ModelEntry; card: ModelCard | undefined }) {
  const facts: [string, string][] = [];
  if (card?.target) facts.push(['Target', card.target]);
  if (card?.population) facts.push(['Population', card.population]);
  if (card?.trained_at) facts.push(['Trained', dateTime(card.trained_at)]);
  if (model.artifact) facts.push(['Artifact', model.artifact]);
  const limitations = card?.limitations ?? [];
  return (
    <div className="card">
      <div className="card-head">
        <h3>{model.name}</h3>
        <span className={`badge ${model.validated ? 'badge-low' : 'badge-medium'}`}>{model.validated ? 'Validated' : 'Not validated'}</span>
      </div>
      <p className="page-subtitle">{model.purpose}.</p>
      {Object.keys(model.headline).length > 0 && (
        <div className="result-grid" style={{ marginTop: 0 }}>
          {Object.entries(model.headline).map(([key, value]) => (
            <div className="stat" key={key}>
              <div className="label">{HEADLINE[key] ?? key}</div>
              <div className="value" style={{ fontSize: '1.3rem' }}>{figure(value)}</div>
            </div>
          ))}
        </div>
      )}
      {facts.length > 0 && (
        <dl className="facts">
          {facts.map(([k, v]) => <div key={k}><dt>{k}</dt><dd>{v}</dd></div>)}
        </dl>
      )}
      {model.gaps.length > 0 && (
        <>
          <div className="section-label" style={{ marginTop: '1.25rem' }}>What is missing</div>
          <ul className="prose">{model.gaps.map((g) => <li key={g}>{g}</li>)}</ul>
        </>
      )}
      {limitations.length > 0 && (
        <>
          <div className="section-label" style={{ marginTop: '1.25rem' }}>Known limitations</div>
          <ul className="prose">{limitations.map((l) => <li key={l}>{l}</li>)}</ul>
        </>
      )}
    </div>
  );
}

/** A tick at every whole year of loan age up to the last month observed. */
function yearsOfAge(lastMonth: number): number[] {
  const ticks: number[] = [];
  for (let month = 0; month <= lastMonth; month += 12) ticks.push(month);
  return ticks;
}

const LOG_TICKS = [0.001, 0.003, 0.01, 0.03, 0.1, 0.3, 1, 3, 10, 30, 100, 300, 1000];

/**
 * Predicted against realized by risk group, on a log axis: the groups span two orders of magnitude, and
 * on a linear axis everything below the riskiest group would be a flat line at zero.
 */
function Calibration({ rows, unit, digits = 2 }: { rows: { predicted: number; actual: number }[]; unit: string; digits?: number }) {
  const floor = LOG_TICKS[0];
  const data = rows.map((r, i) => ({ group: `${i + 1}`, Predicted: Math.max(r.predicted, floor), Realized: Math.max(r.actual, floor) }));
  if (data.length === 0) return null;
  const values = data.flatMap((d) => [d.Predicted, d.Realized]);
  const lowest = Math.min(...values), highest = Math.max(...values);
  const lo = [...LOG_TICKS].reverse().find((t) => t <= lowest) ?? floor;
  const hi = LOG_TICKS.find((t) => t >= highest) ?? LOG_TICKS[LOG_TICKS.length - 1];
  return (
    <ResponsiveContainer width="100%" height={240}>
      <LineChart data={data} margin={{ top: 6, right: 12, bottom: 0, left: 0 }}>
        <CartesianGrid {...GRID} />
        <XAxis dataKey="group" {...AXIS} />
        <YAxis scale="log" domain={[lo, hi]} ticks={LOG_TICKS.filter((t) => t >= lo && t <= hi)} allowDataOverflow
          tickFormatter={(v) => `${v}${unit}`} width={64} {...AXIS} />
        <Tooltip {...TOOLTIP} labelFormatter={(g) => `Risk decile ${g}`} formatter={(v, name) => [`${Number(v).toFixed(digits)}${unit}`, name]} />
        <Legend {...LEGEND} />
        <Line dataKey="Predicted" stroke="var(--hop-3)" strokeWidth={2} strokeDasharray="6 4" dot={{ r: 3, fill: 'var(--surface)' }} isAnimationActive={false} />
        <Line dataKey="Realized" stroke="var(--text)" strokeWidth={2} dot={{ r: 3, fill: 'var(--surface)' }} isAnimationActive={false} />
      </LineChart>
    </ResponsiveContainer>
  );
}

function Overlay({ card }: { card: SurvivalCard }) {
  const protocol = card.validation.overlay_protocol;
  const rows: [string, OverlayTrial, OverlayCause][] = [
    ['Default', protocol.default, card.overlay.default],
    ['Prepayment', protocol.prepay, card.overlay.prepay],
  ];
  return (
    <div className="card">
      <div className="card-head"><h3>Drift overlay</h3></div>
      <p className="page-subtitle">
        A model fitted on the past drifts. Scaling a hazard by its recent realized-to-predicted ratio only helps if the drift
        persists, so that was tested out of time first: a scalar estimated
        on {monthLabel(protocol.estimated_on[0])} to {monthLabel(protocol.estimated_on[1])} was judged
        on {monthLabel(protocol.judged_on[0])} to {monthLabel(protocol.judged_on[1])}. A cause gets an overlay in production only if
        that test moved it closer to what happened, and only if its latest ratio is more than two standard errors from 1.
      </p>
      <div className="table-scroll">
        <table>
          <thead>
            <tr>
              <th>Hazard</th>
              <th className="num">Scalar tried</th>
              <th className="num">Realized / predicted before</th>
              <th className="num">After</th>
              <th>Carries forward</th>
              <th className="num">Latest realized / predicted</th>
              <th className="num">In production</th>
            </tr>
          </thead>
          <tbody>
            {rows.map(([name, trial, live]) => (
              <tr key={name}>
                <td>{name}</td>
                <td className="num">×{trial.overlay.toFixed(3)}</td>
                <td className="num">{trial.actual_over_predicted_before.toFixed(3)}</td>
                <td className="num">{trial.actual_over_predicted_after.toFixed(3)}</td>
                <td>{trial.eligible ? 'Yes' : 'No, it moved further away'}</td>
                <td className="num">
                  {live.actual_over_predicted.toFixed(3)} ± {live.standard_error.toFixed(3)}
                  <span className="sub"> ({count(live.events)} events)</span>
                </td>
                <td className="num"><b>{live.applied ? `×${live.scalar.toFixed(4)}` : 'none'}</b></td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p className="chart-caption">
        The production figures are from loans held out of the fit, {monthLabel(card.overlay.window[0])} to {monthLabel(card.overlay.window[1])},
        the latest months in which every vintage reports.
      </p>
    </div>
  );
}

function SurvivalEvidence({ backtest, card }: { backtest: SurvivalBacktest; card: SurvivalCard | undefined }) {
  const [cause, setCause] = useState<Cause>('default');
  const [vintage, setVintage] = useState(backtest.vintages[0]?.orig_year ?? 0);
  const c = CAUSE[cause];

  const monthly = useMemo(() => backtest.monthly.filter((m) => m.all_vintages).map((m) => ({
    month: m.month,
    Predicted: +(m[c.predicted] * 1e4).toFixed(3),
    Realized: +(m[c.actual] * 1e4).toFixed(3),
  })), [backtest, c]);
  const cohort = backtest.vintages.find((v) => v.orig_year === vintage) ?? backtest.vintages[0];
  const curve = useMemo(() => (cohort ? cohort.ages.map((age, i) => ({
    age,
    Predicted: +(cohort[c.predicted][i] * 100).toFixed(3),
    Realized: +(cohort[c.empirical][i] * 100).toFixed(3),
  })) : []), [cohort, c]);
  const held = backtest.calibration.held_out[cause];
  const trial = card?.validation.overlay_protocol[cause];
  const basisPoints = (rows: { predicted: number; actual: number }[]) => rows.map((r) => ({ predicted: r.predicted * 1e4, actual: r.actual * 1e4 }));

  return (
    <>
      <div className="toolbar">
        <div className="section-label" style={{ margin: 0 }}>Backtests on loans held out of the fit</div>
        <div className="segmented" role="tablist" aria-label="Which hazard">
          {(Object.keys(CAUSE) as Cause[]).map((k) => (
            <button key={k} role="tab" aria-selected={cause === k} className={cause === k ? 'on' : ''} onClick={() => setCause(k)}>{CAUSE[k].label}</button>
          ))}
        </div>
      </div>

      <div className="widget-grid" style={{ marginBottom: '1.25rem' }}>
        <div className="card">
          <div className="card-head"><h3>Month by month</h3></div>
          <p className="page-subtitle">
            The monthly {c.noun} rate the model predicted against the one that happened, in basis points of loans at risk.
          </p>
          <ResponsiveContainer width="100%" height={260}>
            <LineChart data={monthly} margin={{ top: 6, right: 12, bottom: 0, left: 0 }}>
              <CartesianGrid {...GRID} />
              <XAxis dataKey="month" ticks={yearTicks(monthly.map((m) => m.month), 9)} tickFormatter={yearOf} {...AXIS} />
              <YAxis unit=" bp" width={64} {...AXIS} />
              <Tooltip {...TOOLTIP} labelFormatter={(m) => monthLabel(String(m))} formatter={(v, name) => [`${Number(v).toFixed(2)} bp`, name]} />
              <Legend {...LEGEND} />
              <Line type="monotone" dataKey="Realized" stroke="var(--text)" strokeWidth={2} dot={false} isAnimationActive={false} />
              <Line type="monotone" dataKey="Predicted" stroke="var(--hop-3)" strokeWidth={2} strokeDasharray="6 4" dot={false} isAnimationActive={false} />
            </LineChart>
          </ResponsiveContainer>
        </div>
        {cohort && (
          <div className="card">
            <div className="card-head">
              <h3>Vintage by vintage</h3>
              <select aria-label="Origination year" value={cohort.orig_year} onChange={(e) => setVintage(Number(e.target.value))}>
                {backtest.vintages.map((v) => <option key={v.orig_year} value={v.orig_year}>{v.orig_year} originations</option>)}
              </select>
            </div>
            <p className="page-subtitle">
              Cumulative {c.noun} of {count(cohort.n_loans)} loans: the model's curve, given the macro path that
              actually happened, against the Aalen-Johansen estimate of what the loans did.
            </p>
            <ResponsiveContainer width="100%" height={260}>
              <LineChart data={curve} margin={{ top: 6, right: 12, bottom: 0, left: -8 }}>
                <CartesianGrid {...GRID} />
                <XAxis dataKey="age" type="number" domain={[0, 'dataMax']} ticks={yearsOfAge(cohort.ages.length - 1)} tickFormatter={(a) => `${Number(a) / 12}y`} {...AXIS} />
                <YAxis unit="%" {...AXIS} />
                <Tooltip {...TOOLTIP} labelFormatter={(a) => `Loan age ${a} months`} formatter={(v, name) => [`${Number(v).toFixed(2)}%`, name]} />
                <Legend {...LEGEND} />
                <Line type="monotone" dataKey="Realized" stroke="var(--text)" strokeWidth={2} dot={false} isAnimationActive={false} />
                <Line type="monotone" dataKey="Predicted" stroke="var(--hop-3)" strokeWidth={2} strokeDasharray="6 4" dot={false} isAnimationActive={false} />
              </LineChart>
            </ResponsiveContainer>
          </div>
        )}
      </div>

      <div className="widget-grid" style={{ marginBottom: '1.25rem' }}>
        <div className="card">
          <div className="card-head"><h3>Calibration by risk decile</h3></div>
          <p className="page-subtitle">
            The production model, {monthLabel(backtest.calibration.held_out_window[0])} to {monthLabel(backtest.calibration.held_out_window[1])},
            before any overlay: realized over predicted is {held.actual_over_predicted.toFixed(2)} overall
            and {(held.top_percentile.actual / held.top_percentile.predicted).toFixed(2)} in the riskiest 1% of loan-months.
          </p>
          <Calibration rows={basisPoints(held.deciles)} unit=" bp" />
        </div>
        <div className="card">
          <div className="card-head"><h3>The same test, out of time</h3></div>
          <p className="page-subtitle">
            A model that saw nothing after {card ? monthLabel(card.validation.out_of_time.train_months[1]) : '2022'},
            judged on {monthLabel(backtest.calibration.out_of_time_window[0])} to {monthLabel(backtest.calibration.out_of_time_window[1])}.
            {trial && ` Realized over predicted is ${trial.actual_over_predicted_before.toFixed(2)}: that is the drift the refit on every month, and the overlay below, are there for.`}
          </p>
          <Calibration rows={basisPoints(backtest.calibration.out_of_time[cause])} unit=" bp" />
        </div>
      </div>

      {card && <Overlay card={card} />}

      {card && (
        <div className="card">
          <div className="card-head"><h3>What the model has seen</h3></div>
          <p className="page-subtitle">
            {count(card.training.rows)} loan-months of {count(card.training.loans)} loans, {monthLabel(card.training.months[0])} to {monthLabel(card.training.months[1])},
            with {count(card.training.held_out_loans)} more loans held out. Each driver was fitted over the range below (the 0.5th to
            the 99.5th percentile). A tree model holds its response constant outside that range, so every projection reports the months
            in which a scenario leaves it.
          </p>
          <dl className="facts" style={{ borderTop: 'none', paddingTop: 0, marginTop: 0 }}>
            {Object.entries(card.training.envelope).map(([driver, [lo, hi]]) => (
              <div key={driver}><dt>{featureLabel(driver)}</dt><dd>{ENVELOPE[driver]?.(lo, hi) ?? `${lo} to ${hi}`}</dd></div>
            ))}
          </dl>
        </div>
      )}
    </>
  );
}

function PdEvidence({ card }: { card: PdCard }) {
  const outOfTime = card.validation.out_of_time, inTime = card.validation.in_time_holdout;
  const drivers = Object.entries(card.feature_importance_gain).sort((a, b) => b[1] - a[1]).slice(0, 8);
  const percent = (rows: { predicted: number; actual: number }[]) => rows.map((r) => ({ predicted: r.predicted * 100, actual: r.actual * 100 }));
  return (
    <>
      <div className="widget-grid" style={{ marginBottom: '1.25rem' }}>
        <div className="card">
          <div className="card-head"><h3>Calibration out of time</h3></div>
          <p className="page-subtitle">
            Trained on the {outOfTime.train_vintages} vintages, scored on {count(outOfTime.n)} loans originated in {outOfTime.test_vintages}:
            it predicted {pct(outOfTime.mean_predicted_pd, 1)} and {pct(outOfTime.default_rate, 1)} defaulted. The ranking held
            (AUC {outOfTime.auc.toFixed(3)}, KS {outOfTime.ks.toFixed(3)}); the level carries the stress its training vintages lived through.
          </p>
          <Calibration rows={percent(outOfTime.deciles)} unit="%" />
        </div>
        <div className="card">
          <div className="card-head"><h3>Calibration in time</h3></div>
          <p className="page-subtitle">
            The production model on {count(inTime.n)} loans of its own vintages that were kept out of fitting and
            calibration: predicted {pct(inTime.mean_predicted_pd, 2)}, realized {pct(inTime.default_rate, 2)} (AUC {inTime.auc.toFixed(3)}).
          </p>
          <Calibration rows={percent(inTime.deciles)} unit="%" />
        </div>
      </div>

      <div className="widget-grid" style={{ marginBottom: '1.25rem' }}>
        <div className="card">
          <div className="card-head"><h3>Out of time, by vintage</h3></div>
          <table>
            <thead>
              <tr>
                <th>Originated</th>
                <th className="num">Loans</th>
                <th className="num">Predicted</th>
                <th className="num">Realized</th>
                <th className="num">AUC</th>
                <th className="num">KS</th>
              </tr>
            </thead>
            <tbody>
              {outOfTime.by_vintage.map((v) => (
                <tr key={v.orig_year}>
                  <td>{v.orig_year}</td>
                  <td className="num">{count(v.n)}</td>
                  <td className="num">{pct(v.mean_predicted_pd, 2)}</td>
                  <td className="num">{pct(v.default_rate, 2)}</td>
                  <td className="num">{v.auc.toFixed(3)}</td>
                  <td className="num">{v.ks.toFixed(3)}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <p className="chart-caption">
            Default within {card.horizon_months} months of origination. Discrimination is stable from one vintage to the next, which
            is what a ranking model is for; the level over time is the job of the survival model, which sees the macro path.
          </p>
        </div>
        <div className="card">
          <div className="card-head"><h3>What drives the score</h3></div>
          <ul className="list">
            {drivers.map(([feature, gain]) => {
              const direction = card.monotone_constraints[feature];
              return (
                <li key={feature} className="list-row">
                  <span className="grow">
                    {featureLabel(feature)}
                    {direction !== undefined && <div className="sub">constrained: risk can only {direction > 0 ? 'rise' : 'fall'} as it increases</div>}
                  </span>
                  <span className="bar" aria-hidden="true"><span style={{ width: `${(gain / drivers[0][1]) * 100}%` }} /></span>
                  <span className="num">{pct(gain, 1)}</span>
                </li>
              );
            })}
          </ul>
          <p className="chart-caption">
            Share of the model's total gain. The monotone constraints keep reason codes coherent: a higher credit score can never
            count against a borrower.
          </p>
        </div>
      </div>
    </>
  );
}

function Drift() {
  const drift = useQuery({ queryKey: ['pd-drift'], queryFn: pdDrift });
  const [open, setOpen] = useState<string | null>(null);
  if (drift.isPending) return <div className="card"><div className="skeleton" style={{ height: 120 }} /></div>;
  if (!drift.data) return <ErrorBanner error={drift.error} />;
  const rows = drift.data.score ? [drift.data.score, ...drift.data.features] : drift.data.features;
  const opened = rows.find((r) => r.feature === open);
  return (
    <div className="card">
      <div className="card-head"><h3>Is this still the population it was trained on?</h3></div>
      <p className="page-subtitle">
        Population stability of your {count(drift.data.loans)} loans against the training population, input by input.
        Below 0.10 is stable, above 0.25 a significant shift. A small portfolio shows some PSI from sampling alone, about
        (bins - 1) / loans; that noise floor is subtracted before the verdict. Select an input to see its distribution.
      </p>
      <div className="table-scroll">
        <table>
          <thead>
            <tr>
              <th>Input</th>
              <th className="num">Loans</th>
              <th className="num">PSI</th>
              <th className="num">Noise floor</th>
              <th>Verdict</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((f) => (
              <tr key={f.feature} className={f.feature === open ? 'selected' : ''} onClick={() => setOpen(f.feature === open ? null : f.feature)} style={{ cursor: 'pointer' }}>
                <td><button type="button" className="plain-text" aria-expanded={f.feature === open}>{featureLabel(f.feature)}</button></td>
                <td className="num">{count(f.observations)}</td>
                <td className="num">{f.psi.toFixed(3)}</td>
                <td className="num">{f.noiseFloor.toFixed(3)}</td>
                <td><span className={`badge ${DRIFT_BADGE[f.status]}`}>{DRIFT_LABEL[f.status]}</span></td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {opened && (
        <>
          <div className="section-label" style={{ marginTop: '1.25rem' }}>{featureLabel(opened.feature)}: share of loans in each bin</div>
          <ResponsiveContainer width="100%" height={230}>
            <BarChart data={opened.bins.map((b) => ({ bin: b.label, Training: +(b.expected * 100).toFixed(2), Portfolio: +(b.actual * 100).toFixed(2) }))}
              margin={{ top: 6, right: 12, bottom: 0, left: -8 }}>
              <CartesianGrid {...GRID} />
              <XAxis dataKey="bin" {...AXIS} interval={opened.bins.length > 12 ? 'preserveStartEnd' : 0} tick={{ ...AXIS.tick, fontSize: 11 }} />
              <YAxis unit="%" {...AXIS} />
              <Tooltip {...TOOLTIP} formatter={(v, name) => [`${Number(v).toFixed(1)}%`, name]} />
              <Legend {...LEGEND} iconType="square" />
              <Bar dataKey="Training" fill="var(--hop-4)" isAnimationActive={false} />
              <Bar dataKey="Portfolio" fill="var(--text)" isAnimationActive={false} />
            </BarChart>
          </ResponsiveContainer>
        </>
      )}
      <p className="chart-caption">Model {drift.data.modelVersion} · computed {dateTime(drift.data.computedAt)}, refreshed every ten minutes.</p>
    </div>
  );
}

export default function ModelGovernancePage() {
  const models = useQuery({ queryKey: ['models'], queryFn: listModels });
  const [selected, setSelected] = useState('survival');
  const model = models.data?.find((m) => m.id === selected);
  const card = useQuery({ queryKey: ['model-card', selected], queryFn: () => modelCard(selected), enabled: model?.validated === true, staleTime: Infinity });
  const backtest = useQuery({ queryKey: ['survival-backtest'], queryFn: survivalBacktest, enabled: selected === 'survival', staleTime: Infinity });

  return (
    <div>
      <div className="page-head-row">
        <div>
          <h2>Model governance</h2>
          <p className="page-subtitle">
            Every model behind the numbers in this console: what it is for, exactly which artifact is running, how it was
            validated, and where it should not be trusted.
          </p>
        </div>
      </div>
      <ErrorBanner error={models.error ?? card.error ?? backtest.error} />
      {models.isPending && <div className="card"><div className="skeleton" style={{ height: 200 }} /></div>}
      {models.data && <Inventory models={models.data} selected={selected} onSelect={setSelected} />}
      {model && <CardDetail model={model} card={card.data} />}
      {selected === 'survival' && backtest.data && <SurvivalEvidence backtest={backtest.data} card={card.data as SurvivalCard | undefined} />}
      {selected === 'pd_24m' && card.data && <PdEvidence card={card.data as PdCard} />}
      {selected === 'pd_24m' && <Drift />}
    </div>
  );
}
