import { useState } from 'react';
import { LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, Legend, ResponsiveContainer } from 'recharts';
import { regimeForecast } from '../api/client';
import ErrorBanner from '../components/ErrorBanner';
import type { RegimeForecast } from '../api/types';

interface CurvePoint {
  monthsAhead: number;
  calm: number;
  stressed: number;
}

export default function RegimeForecastPage() {
  const [monthsAhead, setMonthsAhead] = useState(6);
  const [single, setSingle] = useState<RegimeForecast | null>(null);
  const [curve, setCurve] = useState<CurvePoint[] | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [loading, setLoading] = useState(false);
  const [curveLoading, setCurveLoading] = useState(false);

  const fetchSingle = async () => {
    setLoading(true);
    setError(null);
    setSingle(null);
    try {
      setSingle(await regimeForecast(monthsAhead));
    } catch (e) {
      setError(e);
    } finally {
      setLoading(false);
    }
  };

  const fetchCurve = async () => {
    setCurveLoading(true);
    setError(null);
    setCurve(null);
    try {
      const horizons = Array.from({ length: 24 }, (_, i) => i + 1);
      const results = await Promise.all(horizons.map((m) => regimeForecast(m)));
      setCurve(
        results.map((r) => ({
          monthsAhead: r.monthsAhead,
          calm: Number((r.regimeProbabilities.calm * 100).toFixed(2)),
          stressed: Number((r.regimeProbabilities.stressed * 100).toFixed(2)),
        })),
      );
    } catch (e) {
      setError(e);
    } finally {
      setCurveLoading(false);
    }
  };

  return (
    <div>
      <h2>Macro Regime Forecast</h2>
      <p className="page-subtitle">
        N-step-ahead forecast of the portfolio's calm/stressed regime probability, via a Markov
        chain forecast on the 2-state HMM fitted on the portfolio delinquency + interest-rate
        series.
      </p>

      <div className="card">
        <div className="row-inline">
          <div className="field">
            <label htmlFor="monthsAhead">Months ahead</label>
            <input
              id="monthsAhead"
              type="number"
              min={0}
              value={monthsAhead}
              onChange={(e) => setMonthsAhead(Number(e.target.value))}
            />
          </div>
          <button onClick={fetchSingle} disabled={loading}>
            {loading ? 'Forecasting...' : 'Forecast'}
          </button>
          <button className="secondary" onClick={fetchCurve} disabled={curveLoading}>
            {curveLoading ? 'Plotting...' : 'Plot 24-month curve'}
          </button>
        </div>
        <ErrorBanner error={error} />
        {single && (
          <>
          <div className="mix" style={{ marginTop: '1rem' }} role="img" aria-label={`Calm ${(single.regimeProbabilities.calm * 100).toFixed(1)} percent, stressed ${(single.regimeProbabilities.stressed * 100).toFixed(1)} percent`}>
            <span className="low" style={{ flexGrow: single.regimeProbabilities.calm }} />
            <span className="high" style={{ flexGrow: single.regimeProbabilities.stressed }} />
          </div>
          <div className="result-grid">
            <div className="stat">
              <div className="label">As of</div>
              <div className="value" style={{ fontSize: '0.95rem' }}>{single.asOfMonth}</div>
            </div>
            <div className="stat">
              <div className="label">Forecast month</div>
              <div className="value" style={{ fontSize: '0.95rem' }}>{single.forecastMonth}</div>
            </div>
            <div className="stat">
              <div className="label">P(calm)</div>
              <div className="value">{(single.regimeProbabilities.calm * 100).toFixed(1)}%</div>
            </div>
            <div className="stat">
              <div className="label">P(stressed)</div>
              <div className="value">{(single.regimeProbabilities.stressed * 100).toFixed(1)}%</div>
            </div>
          </div>
          </>
        )}
      </div>

      {curve && (
        <div className="card">
          <h3>Regime probability by horizon</h3>
          <ResponsiveContainer width="100%" height={300}>
            <LineChart data={curve}>
              <CartesianGrid strokeDasharray="3 3" stroke="var(--border)" />
              <XAxis dataKey="monthsAhead" stroke="var(--border-strong)" tick={{ fill: 'var(--text-muted)', fontSize: 12 }} label={{ value: 'Months ahead', position: 'insideBottom', offset: -5, fill: 'var(--text-muted)' }} />
              <YAxis unit="%" domain={[0, 100]} stroke="var(--border-strong)" tick={{ fill: 'var(--text-muted)', fontSize: 12 }} />
              <Tooltip contentStyle={{ background: 'var(--surface)', border: '1px solid var(--border-strong)', borderRadius: 8, color: 'var(--text)' }} />
              <Legend verticalAlign="top" align="right" iconType="plainline" wrapperStyle={{ paddingBottom: 8, color: 'var(--text-muted)' }} />
              <Line type="monotone" dataKey="calm" stroke="var(--sig-low)" strokeWidth={2.5} name="P(calm)" dot={false} />
              <Line type="monotone" dataKey="stressed" stroke="var(--sig-high)" strokeWidth={2.5} name="P(stressed)" dot={false} />
            </LineChart>
          </ResponsiveContainer>
        </div>
      )}
    </div>
  );
}
