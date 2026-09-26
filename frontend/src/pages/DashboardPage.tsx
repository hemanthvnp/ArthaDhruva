import { memo, useState, type ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { getDashboardConfig, listLoanCases, listLoanScores, listRecentNotes, saveDashboardConfig } from '../api/client';
import { useAuth } from '../auth/AuthContext';
import ErrorBanner from '../components/ErrorBanner';
import RiskBadge, { riskBand } from '../components/RiskBadge';
import RiskMeter from '../components/RiskMeter';

const LABELS: Record<string, string> = {
  PORTFOLIO_KPI: 'Portfolio KPIs',
  MY_OPEN_CASES: 'My open cases',
  FLAGGED_CASES: 'Flagged cases',
  RECENT_NOTES: 'Recent notes',
  RECENT_SCORES: 'Recently scored loans',
};

const loanLink = (id: string) => `/loans/${encodeURIComponent(id)}`;
const pct = (p: number) => `${(p * 100).toFixed(2)}%`;
const ago = (iso: string) => {
  const mins = Math.max(0, Math.round((Date.now() - new Date(iso).getTime()) / 60000));
  if (mins < 60) return `${mins}m ago`;
  if (mins < 60 * 24) return `${Math.round(mins / 60)}h ago`;
  return `${Math.round(mins / 1440)}d ago`;
};

function Skeleton() {
  return (
    <div aria-busy="true" aria-label="Loading">
      <div className="skeleton" style={{ width: '90%' }} />
      <div className="skeleton" style={{ width: '70%' }} />
      <div className="skeleton" style={{ width: '80%' }} />
    </div>
  );
}

function Empty({ children }: { children: ReactNode }) {
  return <div className="empty">{children}</div>;
}

// Each widget is memoized and owns its query, so changing the layout re-renders only the widgets
// that were added or moved, and a widget's data is cached across visits.
const PortfolioKpi = memo(function PortfolioKpi() {
  const scores = useQuery({ queryKey: ['loan-scores'], queryFn: listLoanScores });
  const cases = useQuery({ queryKey: ['loan-cases'], queryFn: listLoanCases });
  const rows = scores.data ?? [];
  const avg = rows.length ? rows.reduce((s, l) => s + l.calibratedProbability, 0) / rows.length : 0;
  const high = rows.filter((r) => riskBand(r.calibratedProbability) === 'HIGH').length;
  const flagged = (cases.data ?? []).filter((c) => c.flagged).length;
  const med = rows.filter((r) => riskBand(r.calibratedProbability) === 'MEDIUM').length;
  const low = rows.length - high - med;
  return (
    <>
    <div className="kpi-row">
      <div className="kpi accent">
        <div className="label">Loans scored</div>
        <div className="value">{rows.length}</div>
        <div className="hint">in your portfolio</div>
      </div>
      <div className="kpi">
        <div className="label">Average PD</div>
        <div className="value">{pct(avg)}</div>
        <div className="hint">probability of default</div>
      </div>
      <div className="kpi">
        <div className="label">High risk</div>
        <div className="value">{high}</div>
        <div className="hint">{rows.length ? `${Math.round((high / rows.length) * 100)}% of portfolio` : 'no loans yet'}</div>
      </div>
      <div className="kpi">
        <div className="label">Flagged cases</div>
        <div className="value">{flagged}</div>
        <div className="hint">need attention</div>
      </div>
    </div>
    <div className="card">
      <div className="card-head"><h3>Portfolio risk mix</h3></div>
      {rows.length === 0 ? (
        <p className="empty">Score loans to see how your portfolio splits across the risk bands.</p>
      ) : (
        <>
          <div className="mix" role="img" aria-label={`${low} low, ${med} medium, ${high} high risk loans`}>
            {low > 0 && <span className="low" style={{ flexGrow: low }} />}
            {med > 0 && <span className="mid" style={{ flexGrow: med }} />}
            {high > 0 && <span className="high" style={{ flexGrow: high }} />}
          </div>
          <div className="mix-legend">
            <span><i style={{ background: 'var(--sig-low)' }} /><b>{low}</b>Low risk</span>
            <span><i style={{ background: 'var(--sig-mid)' }} /><b>{med}</b>Medium</span>
            <span><i style={{ background: 'var(--sig-high)' }} /><b>{high}</b>High risk</span>
            <span style={{ marginLeft: 'auto' }}>Average PD <b>{pct(avg)}</b></span>
          </div>
          <div style={{ marginTop: '1.1rem' }}><RiskMeter probability={avg} scale /></div>
        </>
      )}
    </div>
    </>
  );
});

const CasesWidget = memo(function CasesWidget({ mine, flagged }: { mine?: string; flagged?: boolean }) {
  const { data, isPending } = useQuery({ queryKey: ['loan-cases'], queryFn: listLoanCases });
  if (isPending) return <Skeleton />;
  const rows = (data ?? []).filter((c) => (mine ? c.assignedTo === mine && c.status !== 'CLEARED' : true) && (flagged ? c.flagged : true));
  if (rows.length === 0) return <Empty>{mine ? 'No open cases assigned to you.' : 'No flagged cases. Nice.'}</Empty>;
  return (
    <ul className="list">
      {rows.slice(0, 8).map((c) => (
        <li key={c.loanId} className="list-row">
          <span className="grow">
            <Link to={loanLink(c.loanId)}>{c.loanId}</Link>
            <div className="sub">{c.assignedTo ? `Assigned to ${c.assignedTo}` : 'Unassigned'} &middot; {ago(c.updatedAt)}</div>
          </span>
          <span className="badge">{c.status.replaceAll('_', ' ').toLowerCase()}</span>
        </li>
      ))}
    </ul>
  );
});

const NotesWidget = memo(function NotesWidget() {
  const { data, isPending } = useQuery({ queryKey: ['recent-notes'], queryFn: listRecentNotes });
  if (isPending) return <Skeleton />;
  const rows = data ?? [];
  if (rows.length === 0) return <Empty>No notes yet. Notes added to cases appear here.</Empty>;
  return (
    <ul className="list">
      {rows.slice(0, 6).map((n, i) => (
        <li key={i} className="list-row">
          <span className="grow">
            <strong>{n.author}</strong> on <Link to={loanLink(n.loanId)}>{n.loanId}</Link>
            <div className="sub">{n.text}</div>
          </span>
          <span className="sub">{ago(n.createdAt)}</span>
        </li>
      ))}
    </ul>
  );
});

const ScoresWidget = memo(function ScoresWidget() {
  const { data, isPending } = useQuery({ queryKey: ['loan-scores'], queryFn: listLoanScores });
  if (isPending) return <Skeleton />;
  const rows = data ?? [];
  if (rows.length === 0) return <Empty>No loans scored yet. <Link to="/score">Score your first loan</Link>.</Empty>;
  return (
    <ul className="list">
      {rows.slice(0, 6).map((s) => (
        <li key={s.loanId} className="list-row">
          <span className="grow">
            <Link to={loanLink(s.loanId)}>{s.loanId}</Link>
            <div className="sub">{ago(s.computedAt)}</div>
          </span>
          <span style={{ display: 'flex', gap: '0.6rem', alignItems: 'center' }}>
            <span className="num">{pct(s.calibratedProbability)}</span>
            <RiskBadge probability={s.calibratedProbability} />
          </span>
        </li>
      ))}
    </ul>
  );
});

export default function DashboardPage() {
  const { auth } = useAuth();
  const queryClient = useQueryClient();
  const config = useQuery({ queryKey: ['dashboard-config'], queryFn: getDashboardConfig });
  const [editing, setEditing] = useState(false);
  const save = useMutation({
    mutationFn: saveDashboardConfig,
    onSuccess: (c) => queryClient.setQueryData(['dashboard-config'], c),
  });

  if (!config.data) {
    return (
      <div>
        <h2>Dashboard</h2>
        <ErrorBanner error={config.error} />
        {!config.error && <Skeleton />}
      </div>
    );
  }
  const { widgets, available } = config.data;

  const toggle = (id: string) =>
    save.mutate(widgets.includes(id) ? widgets.filter((w) => w !== id) : [...widgets, id]);
  const move = (id: string, delta: number) => {
    const i = widgets.indexOf(id);
    const j = i + delta;
    if (j < 0 || j >= widgets.length) return;
    const next = [...widgets];
    [next[i], next[j]] = [next[j], next[i]];
    save.mutate(next);
  };

  const render = (id: string) => {
    switch (id) {
      case 'PORTFOLIO_KPI': return <PortfolioKpi />;
      case 'MY_OPEN_CASES': return <CasesWidget mine={auth?.username} />;
      case 'FLAGGED_CASES': return <CasesWidget flagged />;
      case 'RECENT_NOTES': return <NotesWidget />;
      case 'RECENT_SCORES': return <ScoresWidget />;
      default: return null;
    }
  };

  // KPI tiles span the full width; every other widget sits in a two-column grid beneath them.
  const kpi = widgets.filter((id) => id === 'PORTFOLIO_KPI');
  const rest = widgets.filter((id) => id !== 'PORTFOLIO_KPI');

  return (
    <div>
      <div className="page-head-row">
        <div>
          <h2>Welcome back, {auth?.username}</h2>
          <p className="page-subtitle">Your layout is yours alone; colleagues keep their own.</p>
        </div>
        <button className={editing ? '' : 'secondary'} onClick={() => setEditing(!editing)}>
          {editing ? 'Done' : 'Customize'}
        </button>
      </div>
      <ErrorBanner error={save.error} />

      {editing && (
        <div className="card">
          <div className="card-head"><h3>Widgets</h3></div>
          <p className="page-subtitle">Toggle what appears, then reorder with the arrows.</p>
          <div className="chips">
            {available.map((id) => (
              <label key={id} className={`chip${widgets.includes(id) ? ' on' : ''}`}>
                <input type="checkbox" checked={widgets.includes(id)} onChange={() => toggle(id)} />
                {widgets.includes(id) ? '✓ ' : '+ '}{LABELS[id] ?? id}
              </label>
            ))}
          </div>
          {widgets.length > 1 && (
            <ul className="list" style={{ marginTop: '1rem' }}>
              {widgets.map((id, i) => (
                <li key={id} className="list-row">
                  <span>{LABELS[id] ?? id}</span>
                  <span style={{ display: 'flex', gap: '0.4rem' }}>
                    <button className="secondary" disabled={i === 0} onClick={() => move(id, -1)} aria-label={`move ${LABELS[id] ?? id} up`}>Up</button>
                    <button className="secondary" disabled={i === widgets.length - 1} onClick={() => move(id, 1)} aria-label={`move ${LABELS[id] ?? id} down`}>Down</button>
                  </span>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}

      {widgets.length === 0 && <div className="card"><Empty>No widgets selected. Choose Customize to add some.</Empty></div>}

      {kpi.map((id) => <div key={id}>{render(id)}</div>)}

      <div className="widget-grid">
        {rest.map((id) => (
          <div className="card" key={id}>
            <div className="card-head"><h3>{LABELS[id] ?? id}</h3></div>
            {render(id)}
          </div>
        ))}
      </div>
    </div>
  );
}
