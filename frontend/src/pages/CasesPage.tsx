import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { downloadLoanCasesCsv, listLoanCases, listRecentNotes } from '../api/client';
import { useAuth } from '../auth/AuthContext';
import ErrorBanner from '../components/ErrorBanner';
import type { LoanCaseSummary, RecentNoteView } from '../api/types';

type Filter = 'ALL' | 'MINE' | 'FLAGGED';

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

  const filtered = cases.filter((c) => {
    if (filter === 'MINE') return c.assignedTo === auth?.username;
    if (filter === 'FLAGGED') return c.flagged;
    return true;
  });

  return (
    <div>
      <h2>Cases</h2>
      <p className="page-subtitle">
        Every loan someone has acted on -- status, assignment, and flags -- plus a live feed of
        recent notes across the whole portfolio.
      </p>

      <div className="card">
        <div className="row-inline" style={{ justifyContent: 'space-between' }}>
          <div className="field">
            <label htmlFor="caseFilter">Show</label>
            <select id="caseFilter" value={filter} onChange={(e) => setFilter(e.target.value as Filter)}>
              <option value="MINE">Assigned to me</option>
              <option value="FLAGGED">Flagged</option>
              <option value="ALL">All cases</option>
            </select>
          </div>
          <div className="row-inline">
            <button className="secondary" onClick={exportCsv}>
              Export CSV
            </button>
            <button className="secondary" onClick={load} disabled={loading}>
              {loading ? 'Loading...' : 'Refresh'}
            </button>
          </div>
        </div>
        <ErrorBanner error={error} />
        <ErrorBanner error={exportError} />
        {filtered.length === 0 && !loading && (
          <p className="page-subtitle">
            {filter === 'MINE'
              ? "No cases assigned to you yet -- assign one from a loan's detail page."
              : filter === 'FLAGGED'
                ? 'No flagged loans right now.'
                : 'No cases yet -- opening a loan detail page creates one.'}
          </p>
        )}
        {filtered.length > 0 && (
          <table>
            <thead>
              <tr>
                <th>Loan ID</th>
                <th>Status</th>
                <th>Assigned to</th>
                <th>Flagged</th>
                <th>Updated</th>
              </tr>
            </thead>
            <tbody>
              {filtered.map((c) => (
                <tr key={c.loanId}>
                  <td>
                    <Link to={`/loans/${encodeURIComponent(c.loanId)}`}>{c.loanId}</Link>
                  </td>
                  <td>{c.status}</td>
                  <td>{c.assignedTo ?? '-'}</td>
                  <td>{c.flagged ? 'Yes' : '-'}</td>
                  <td>{new Date(c.updatedAt).toLocaleString()}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      <div className="card">
        <h3>Recent activity</h3>
        {notes.length === 0 && !loading && <p className="page-subtitle">No notes yet across any loan.</p>}
        {notes.length > 0 && (
          <ul style={{ paddingLeft: '1.2rem' }}>
            {notes.map((n, i) => (
              <li key={i} style={{ marginBottom: '0.5rem' }}>
                <strong>{n.author}</strong> on{' '}
                <Link to={`/loans/${encodeURIComponent(n.loanId)}`}>{n.loanId}</Link> (
                {new Date(n.createdAt).toLocaleString()}): {n.text}
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  );
}
