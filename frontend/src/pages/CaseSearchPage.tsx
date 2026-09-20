import { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { bulkUpdateCases, searchCases } from '../api/client';
import ErrorBanner from '../components/ErrorBanner';
import { useDebounced } from '../hooks/useDebounced';
import type { BulkItemResult, LoanCaseStatus } from '../api/types';

const STATUSES: LoanCaseStatus[] = ['NEW', 'REVIEWED', 'ESCALATED', 'CLEARED'];

export default function CaseSearchPage() {
  const queryClient = useQueryClient();
  const [status, setStatus] = useState<LoanCaseStatus | ''>('');
  const [flagged, setFlagged] = useState<'' | 'true' | 'false'>('');
  const [assignedTo, setAssignedTo] = useState('');
  const [state, setState] = useState('');
  const [page, setPage] = useState(0);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [bulkResult, setBulkResult] = useState<BulkItemResult[] | null>(null);

  // Text boxes are debounced: one request when typing pauses, not one per keystroke.
  const dAssignee = useDebounced(assignedTo);
  const dState = useDebounced(state);

  const params = useMemo(
    () => ({
      status: status || undefined,
      flagged: flagged === '' ? undefined : flagged === 'true',
      assignedTo: dAssignee || undefined,
      state: dState || undefined,
      page,
      size: 25,
    }),
    [status, flagged, dAssignee, dState, page],
  );

  const query = useQuery({ queryKey: ['case-search', params], queryFn: () => searchCases(params), placeholderData: keepPreviousData });

  const bulk = useMutation({
    mutationFn: (change: { status?: LoanCaseStatus; flagged?: boolean }) => bulkUpdateCases([...selected], change),
    onSuccess: (results) => {
      setBulkResult(results);
      setSelected(new Set());
      queryClient.invalidateQueries({ queryKey: ['case-search'] });
    },
  });

  const toggle = (id: string) =>
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });

  const reset = <T,>(setter: (v: T) => void) => (v: T) => {
    setter(v);
    setPage(0);
  };

  const data = query.data;

  return (
    <div>
      <h2>Case Search</h2>
      <p className="page-subtitle">Combine any filters; results are always limited to your organization.</p>

      <div className="card" style={{ display: 'flex', gap: '0.8rem', flexWrap: 'wrap' }}>
        <div className="field">
          <label htmlFor="f-status">Status</label>
          <select id="f-status" value={status} onChange={(e) => reset(setStatus)(e.target.value as LoanCaseStatus | '')}>
            <option value="">Any</option>
            {STATUSES.map((s) => <option key={s}>{s}</option>)}
          </select>
        </div>
        <div className="field">
          <label htmlFor="f-flag">Flagged</label>
          <select id="f-flag" value={flagged} onChange={(e) => reset(setFlagged)(e.target.value as '' | 'true' | 'false')}>
            <option value="">Any</option>
            <option value="true">Flagged</option>
            <option value="false">Not flagged</option>
          </select>
        </div>
        <div className="field">
          <label htmlFor="f-assignee">Assigned to</label>
          <input id="f-assignee" value={assignedTo} onChange={(e) => reset(setAssignedTo)(e.target.value)} />
        </div>
        <div className="field">
          <label htmlFor="f-state">Property state</label>
          <input id="f-state" value={state} maxLength={2} onChange={(e) => reset(setState)(e.target.value.toUpperCase())} />
        </div>
      </div>

      <ErrorBanner error={query.error ?? bulk.error} />

      {selected.size > 0 && (
        <div className="card" style={{ display: 'flex', gap: '0.6rem', alignItems: 'center' }}>
          <strong>{selected.size} selected</strong>
          <button onClick={() => bulk.mutate({ flagged: true })} disabled={bulk.isPending}>Flag</button>
          <button onClick={() => bulk.mutate({ flagged: false })} disabled={bulk.isPending}>Unflag</button>
          <button onClick={() => bulk.mutate({ status: 'REVIEWED' })} disabled={bulk.isPending}>Mark reviewed</button>
          <button onClick={() => bulk.mutate({ status: 'CLEARED' })} disabled={bulk.isPending}>Clear</button>
        </div>
      )}
      {bulkResult && (
        <p role="status">
          {bulkResult.filter((r) => r.ok).length} updated
          {bulkResult.some((r) => !r.ok) && `, ${bulkResult.filter((r) => !r.ok).map((r) => `${r.loanId}: ${r.error}`).join('; ')}`}
        </p>
      )}

      <div className="card">
        {!data ? (
          <p>Loading...</p>
        ) : data.items.length === 0 ? (
          <p>No cases match.</p>
        ) : (
          <table>
            <thead>
              <tr><th /><th>Loan</th><th>Status</th><th>Assigned</th><th>Flagged</th><th>Updated</th></tr>
            </thead>
            <tbody>
              {data.items.map((c) => (
                <tr key={c.loanId}>
                  <td><input type="checkbox" aria-label={`select ${c.loanId}`} checked={selected.has(c.loanId)} onChange={() => toggle(c.loanId)} /></td>
                  <td><Link to={`/loans/${encodeURIComponent(c.loanId)}`}>{c.loanId}</Link></td>
                  <td>{c.status}</td>
                  <td>{c.assignedTo ?? '-'}</td>
                  <td>{c.flagged ? 'Yes' : ''}</td>
                  <td>{new Date(c.updatedAt).toLocaleString()}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        {data && (
          <div className="actions">
            <button onClick={() => setPage(page - 1)} disabled={page === 0}>Previous</button>
            <span style={{ margin: '0 0.8rem' }}>Page {data.page + 1} of {Math.max(1, Math.ceil(data.total / data.size))} ({data.total} total)</span>
            <button onClick={() => setPage(page + 1)} disabled={!data.hasMore}>Next</button>
          </div>
        )}
      </div>
    </div>
  );
}
