import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { downloadLoanCasesCsv, listLoanCases, listRecentNotes } from '../api/client';
import { useAuth } from '../auth/AuthContext';
import ErrorBanner from '../components/ErrorBanner';
import type { LoanCaseSummary, RecentNoteView } from '../api/types';

type Filter = 'ALL' | 'MINE' | 'FLAGGED';

const loanLink = (id: string) => `/loans/${encodeURIComponent(id)}`;
const ago = (iso: string) => {
  const mins = Math.max(0, Math.round((Date.now() - new Date(iso).getTime()) / 60000));
  if (mins < 60) return `${mins}m ago`;
  if (mins < 60 * 24) return `${Math.round(mins / 60)}h ago`;
  return `${Math.round(mins / 1440)}d ago`;
};

/**
 * The "what's on my plate" view: every loan case across the system (status/assignment/flag), the
 * quick filters an analyst actually works from day to day, plus a cross-loan activity feed built
 * from the same notes each case's detail page already writes -- no separate audit mechanism
 * needed.
 */
export default function CasesPage() {
  const { auth } = useAuth();
  const [cases, setCases] = useState<LoanCaseSummary[]>([]);
  const [notes, setNotes] = useState<RecentNoteView[]>([]);
  const [error, setError] = useState<unknown>(null);
  const [loading, setLoading] = useState(true);
  const [filter, setFilter] = useState<Filter>('MINE');
  const [exportError, setExportError] = useState<unknown>(null);

  const exportCsv = () => {
    setExportError(null);
    downloadLoanCasesCsv().catch(setExportError);
  };

  const load = () => {
    setLoading(true);
    setError(null);
    Promise.all([listLoanCases(), listRecentNotes()])
      .then(([c, n]) => {
        setCases(c);
        setNotes(n);
      })
      .catch(setError)
      .finally(() => setLoading(false));
  };

  useEffect(load, []);

  const counts: Record<Filter, number> = {
    MINE: cases.filter((c) => c.assignedTo === auth?.username).length,
    FLAGGED: cases.filter((c) => c.flagged).length,
    ALL: cases.length,
  };
  const filtered = cases.filter((c) => {
    if (filter === 'MINE') return c.assignedTo === auth?.username;
    if (filter === 'FLAGGED') return c.flagged;
    return true;
  });
  const tabs: [Filter, string][] = [['MINE', 'Assigned to me'], ['FLAGGED', 'Flagged'], ['ALL', 'All cases']];

  return (
    <div>
      <div className="page-head-row">
        <div>
          <h2>Cases</h2>
          <p className="page-subtitle">Every loan someone has acted on, with a live feed of recent notes across the portfolio.</p>
        </div>
        <div className="actions" style={{ marginTop: 0 }}>
          <button className="secondary" onClick={exportCsv}>Export CSV</button>
          <button className="secondary" onClick={load} disabled={loading}>{loading ? 'Loading...' : 'Refresh'}</button>
        </div>
      </div>
      <ErrorBanner error={error} />
      <ErrorBanner error={exportError} />

      <div className="split">
        <div className="card">
          <div className="toolbar">
            <div className="segmented" role="tablist" aria-label="Case filter">
              {tabs.map(([key, label]) => (
                <button key={key} role="tab" aria-selected={filter === key} className={filter === key ? 'on' : ''} onClick={() => setFilter(key)}>
                  {label}<span className="count">{counts[key]}</span>
                </button>
              ))}
            </div>
          </div>

          {loading && filtered.length === 0 && (
            <div aria-busy="true">
              <div className="skeleton" /><div className="skeleton" style={{ width: '85%' }} /><div className="skeleton" style={{ width: '70%' }} />
            </div>
          )}
          {filtered.length === 0 && !loading && (
            <p className="empty">
              {filter === 'MINE'
                ? "No cases assigned to you yet. Assign one from a loan's detail page."
                : filter === 'FLAGGED'
                  ? 'No flagged loans right now.'
                  : 'No cases yet. Opening a loan detail page creates one.'}
            </p>
          )}
          {filtered.length > 0 && (
            <table>
              <thead>
                <tr><th>Loan</th><th>Status</th><th>Assigned to</th><th>Updated</th></tr>
              </thead>
              <tbody>
                {filtered.map((c) => (
                  <tr key={c.loanId}>
                    <td>
                      <Link to={loanLink(c.loanId)}>{c.loanId}</Link>
                      {c.flagged && <span className="badge badge-high" style={{ marginLeft: '0.5rem' }}>Flagged</span>}
                    </td>
                    <td><span className={`badge badge-${c.status.toLowerCase()}`}>{c.status.toLowerCase()}</span></td>
                    <td>{c.assignedTo ?? <span className="sub" style={{ color: 'var(--text-muted)' }}>Unassigned</span>}</td>
                    <td style={{ color: 'var(--text-muted)' }} title={new Date(c.updatedAt).toLocaleString()}>{ago(c.updatedAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>

        <div className="card">
          <div className="card-head"><h3>Recent activity</h3></div>
          {notes.length === 0 && !loading && <p className="empty">No notes yet across any loan.</p>}
          {notes.length > 0 && (
            <ul className="list">
              {notes.slice(0, 12).map((n, i) => (
                <li key={i} className="list-row" style={{ alignItems: 'flex-start' }}>
                  <span className="grow" style={{ whiteSpace: 'normal' }}>
                    <strong>{n.author}</strong> on <Link to={loanLink(n.loanId)}>{n.loanId}</Link>
                    <div className="sub">{n.text}</div>
                  </span>
                  <span className="sub">{ago(n.createdAt)}</span>
                </li>
              ))}
            </ul>
          )}
        </div>
      </div>
    </div>
  );
}
