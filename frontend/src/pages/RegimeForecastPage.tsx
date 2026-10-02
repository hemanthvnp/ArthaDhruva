import { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { Area, AreaChart, CartesianGrid, ReferenceLine, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { regimeForecast } from '../api/client';
import ErrorBanner from '../components/ErrorBanner';
import { AXIS, GRID, TOOLTIP, yearOf, yearTicks } from '../components/chartTheme';
import { duration, monthLabel, pct } from '../format';

const HORIZONS: [number, string][] = [[12, '1 year'], [24, '2 years'], [60, '5 years'], [120, '10 years']];

export default function RegimeForecastPage() {
  const [horizon, setHorizon] = useState(24);
  const forecast = useQuery({ queryKey: ['regime-forecast', horizon], queryFn: () => regimeForecast(horizon), placeholderData: keepPreviousData });
  const f = forecast.data;

  const path = useMemo(() => {
    if (!f) return [];
    return [
      { month: f.asOfMonth.slice(0, 7), stressed: f.currentRegime === 'stressed' ? 100 : 0 },
      ...f.path.map((p) => ({ month: p.month.slice(0, 7), stressed: +(p.regimeProbabilities.stressed * 100).toFixed(2) })),
    ];
  }, [f]);

  const stressedSpell = f?.expectedDurationMonths.stressed;
  const calmSpell = f?.expectedDurationMonths.calm;

  return (
    <div>
      <div className="page-head-row">
        <div>
          <h2>Regime forecast</h2>
          <p className="page-subtitle">
            A two-state hidden Markov model labels every month calm or stressed. From the last month it has decoded, the
            chance of each regime follows the fitted transition matrix, and from any start it settles into the long-run mix.
            This is the chain the <Link to="/lifetime-risk">lifetime risk</Link> projections average over.
          </p>
        </div>
        <div className="segmented" role="tablist" aria-label="Forecast horizon">
          {HORIZONS.map(([months, label]) => (
            <button key={months} role="tab" aria-selected={horizon === months} className={horizon === months ? 'on' : ''} onClick={() => setHorizon(months)}>{label}</button>
          ))}
        </div>
      </div>

      <ErrorBanner error={forecast.error} />
      {forecast.isPending && <div className="card"><div className="skeleton" style={{ height: 180 }} /></div>}

      {f && (
        <>
          {f.dataAgeMonths > 3 && (
            <p className="warn-banner" role="status" style={{ marginTop: 0, marginBottom: '1.25rem' }}>
              The regime was last decoded for {monthLabel(f.asOfMonth.slice(0, 7))}, {f.dataAgeMonths} months ago. The forecast starts
              there, not today.
            </p>
          )}

          <div className="kpi-row">
            <div className="kpi">
              <div className="label">Regime in {monthLabel(f.asOfMonth.slice(0, 7))}</div>
              <div className="value" style={{ textTransform: 'capitalize' }}>{f.currentRegime}</div>
              <div className="hint">the last decoded month</div>
            </div>
            <div className="kpi accent">
              <div className="label">Stressed in {monthLabel(f.forecastMonth.slice(0, 7))}</div>
              <div className="value">{pct(f.regimeProbabilities.stressed, 1)}</div>
              <div className="hint">{f.monthsAhead} months ahead</div>
            </div>
            <div className="kpi">
              <div className="label">Long-run stressed share</div>
              <div className="value">{pct(f.stationary.stressed, 1)}</div>
              <div className="hint">where every forecast converges</div>
            </div>
            <div className="kpi">
              <div className="label">A stress spell lasts</div>
              <div className="value">{stressedSpell == null ? 'forever' : duration(stressedSpell)}</div>
              <div className="hint">on average; a calm one {calmSpell == null ? 'never ends' : duration(calmSpell)}</div>
            </div>
          </div>

          <div className="card">
            <div className="card-head"><h3>Probability of the stressed regime</h3></div>
            <div className="mix" role="img" style={{ margin: '0.5rem 0 1rem' }}
              aria-label={`In ${monthLabel(f.forecastMonth.slice(0, 7))}: calm ${pct(f.regimeProbabilities.calm, 1)}, stressed ${pct(f.regimeProbabilities.stressed, 1)}`}>
              <span className="low" style={{ flexGrow: f.regimeProbabilities.calm }} />
              <span className="high" style={{ flexGrow: f.regimeProbabilities.stressed }} />
            </div>
            <ResponsiveContainer width="100%" height={300}>
              <AreaChart data={path} margin={{ top: 6, right: 12, bottom: 0, left: -8 }}>
                <CartesianGrid {...GRID} />
                <XAxis dataKey="month" ticks={yearTicks(path.map((p) => p.month))} tickFormatter={yearOf} {...AXIS} />
                <YAxis unit="%" domain={[0, 100]} {...AXIS} />
                <Tooltip {...TOOLTIP} labelFormatter={(m) => monthLabel(String(m))} formatter={(v) => [`${Number(v).toFixed(1)}%`, 'Stressed']} />
                <ReferenceLine y={f.stationary.stressed * 100} stroke="var(--text-muted)" strokeDasharray="6 4"
                  label={{ value: 'long-run share', position: 'insideTopRight', fill: 'var(--text-muted)', fontSize: 12 }} />
                <Area type="monotone" dataKey="stressed" stroke="var(--sig-high)" strokeWidth={2.2} fill="var(--sig-high)" fillOpacity={0.12} isAnimationActive={false} />
              </AreaChart>
            </ResponsiveContainer>
            <p className="chart-caption">
              The chain was estimated from 99 months containing a single stress episode, so the length of a stress spell and
              the long-run share are uncertain. Baseline lifetime figures inherit that uncertainty; the stress scenarios do
              not, because they force the regime.
            </p>
          </div>
        </>
      )}
    </div>
  );
}
