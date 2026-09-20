import { memo, useState } from 'react';
import { Link } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { getDashboardConfig, listLoanCases, listLoanScores, listRecentNotes, saveDashboardConfig } from '../api/client';
import { useAuth } from '../auth/AuthContext';
import ErrorBanner from '../components/ErrorBanner';

const LABELS: Record<string, string> = {
  PORTFOLIO_KPI: 'Portfolio KPIs',
  MY_OPEN_CASES: 'My open cases',
  FLAGGED_CASES: 'Flagged cases',
  RECENT_NOTES: 'Recent notes',
  RECENT_SCORES: 'Recently scored loans',
};

// Each widget is memoized and owns its query, so changing the layout re-renders only the widgets
// that were added or moved, and a widget's data is cached across visits.
const PortfolioKpi = memo(function PortfolioKpi() {
  const { data } = useQuery({ queryKey: ['loan-scores'], queryFn: listLoanScores });
  const scores = data ?? [];
  const avg = scores.length ? scores.reduce((s, l) => s + l.calibratedProbability, 0) / scores.length : 0;
  return (
    <p>
      <strong>{scores.length}</strong> loans scored &middot; average PD <strong>{(avg * 100).toFixed(2)}%</strong>
    </p>
  );
});

const CasesWidget = memo(function CasesWidget({ mine, flagged }: { mine?: string; flagged?: boolean }) {
  const { data } = useQuery({ queryKey: ['loan-cases'], queryFn: listLoanCases });
  const rows = (data ?? []).filter((c) => (mine ? c.assignedTo === mine && c.status !== 'CLEARED' : true) && (flagged ? c.flagged : true));
  if (rows.length === 0) return <p>Nothing here.</p>;
  return (
    <ul>
      {rows.slice(0, 8).map((c) => (
        <li key={c.loanId}><Link to={`/loans/${encodeURIComponent(c.loanId)}`}>{c.loanId}</Link> &middot; {c.status}</li>
      ))}
    </ul>
  );
});

const NotesWidget = memo(function NotesWidget() {
  const { data } = useQuery({ queryKey: ['recent-notes'], queryFn: listRecentNotes });
  const rows = data ?? [];
  if (rows.length === 0) return <p>No notes yet.</p>;
  return (
    <ul>
      {rows.slice(0, 6).map((n, i) => (
        <li key={i}><strong>{n.author}</strong> on {n.loanId}: {n.text}</li>
      ))}
    </ul>
  );
});

const ScoresWidget = memo(function ScoresWidget() {
  const { data } = useQuery({ queryKey: ['loan-scores'], queryFn: listLoanScores });
  const rows = data ?? [];
  if (rows.length === 0) return <p>No loans scored yet.</p>;
  return (
    <ul>
      {rows.slice(0, 6).map((s) => (
        <li key={s.loanId}><Link to={`/loans/${encodeURIComponent(s.loanId)}`}>{s.loanId}</Link> &middot; {(s.calibratedProbability * 100).toFixed(2)}%</li>
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

  if (!config.data) return <div><h2>Dashboard</h2><ErrorBanner error={config.error} /><p>Loading...</p></div>;
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

  return (
    <div>
      <h2>Dashboard</h2>
      <p className="page-subtitle">Your own layout; colleagues keep theirs.</p>
      <div className="actions"><button onClick={() => setEditing(!editing)}>{editing ? 'Done' : 'Customize'}</button></div>
      <ErrorBanner error={save.error} />

      {editing && (
        <div className="card">
          <h3>Widgets</h3>
          {available.map((id) => (
            <div key={id} style={{ display: 'flex', gap: '0.6rem', alignItems: 'center', marginBottom: '0.3rem' }}>
              <label style={{ minWidth: 200 }}>
                <input type="checkbox" checked={widgets.includes(id)} onChange={() => toggle(id)} /> {LABELS[id] ?? id}
              </label>
              {widgets.includes(id) && (
                <>
                  <button onClick={() => move(id, -1)} aria-label={`move ${id} up`}>Up</button>
                  <button onClick={() => move(id, 1)} aria-label={`move ${id} down`}>Down</button>
                </>
              )}
            </div>
          ))}
        </div>
      )}

      {widgets.map((id) => (
        <div className="card" key={id}>
          <h3>{LABELS[id] ?? id}</h3>
          {render(id)}
        </div>
      ))}
    </div>
  );
}
