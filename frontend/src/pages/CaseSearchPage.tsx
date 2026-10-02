import { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { bulkUpdateCases, searchCases } from '../api/client';
import ErrorBanner from '../components/ErrorBanner';
import { useDebounced } from '../hooks/useDebounced';
import type { BulkItemResult, LoanCaseStatus } from '../api/types';
import { count, dateTime } from '../format';

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
  const [reason, setReason] = useState('');

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
    mutationFn: (change: { status?: LoanCaseStatus; flagged?: boolean }) =>
      bulkUpdateCases([...selected], { ...change, reason: reason.trim() || undefined }),
    onSuccess: (results) => {
      setBulkResult(results);
      setSelected(new Set());
      setReason('');
      queryClient.invalidateQueries({ queryKey: ['case-search'] });
      queryClient.invalidateQueries({ queryKey: ['loan-cases'] });
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
      <div className="page-head-row">
        <div>
          <h2>Case search</h2>
          <p className="page-subtitle">Combine any filters, then act on many cases at once. Each case is changed on its own: one that breaks a workflow rule is reported and the rest go through.</p>
        </div>
      </div>

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
        <div className="card" style={{ display: 'flex', gap: '0.6rem', alignItems: 'center', flexWrap: 'wrap' }}>
          <strong>{selected.size} selected</strong>
          <button className="secondary" onClick={() => bulk.mutate({ flagged: true })} disabled={bulk.isPending}>Flag</button>
          <button className="secondary" onClick={() => bulk.mutate({ flagged: false })} disabled={bulk.isPending}>Remove flag</button>
          <button className="secondary" onClick={() => bulk.mutate({ status: 'REVIEWED' })} disabled={bulk.isPending}>Mark reviewed</button>
          <button className="secondary" onClick={() => bulk.mutate({ status: 'ESCALATED' })} disabled={bulk.isPending || !reason.trim()}>Escalate</button>
          <button className="secondary" onClick={() => bulk.mutate({ status: 'CLEARED' })} disabled={bulk.isPending}>Clear</button>
          <input aria-label="Reason" value={reason} maxLength={500} onChange={(e) => setReason(e.target.value)}
            placeholder="Reason: needed to escalate, to clear an escalation, or to reopen" style={{ flex: 1, minWidth: 260 }} />
        </div>
      )}
      {bulkResult && (
        <div className={bulkResult.some((r) => !r.ok) ? 'warn-banner' : 'card'} role="status" style={{ marginBottom: '1.25rem' }}>
          {bulkResult.filter((r) => r.ok).length} updated.
          {bulkResult.filter((r) => !r.ok).map((r) => <div key={r.loanId}>{r.loanId}: {r.error}</div>)}
        </div>
      )}

      <div className="card">
        {!data ? (
          <div className="skeleton" style={{ height: 120 }} />
        ) : data.items.length === 0 ? (
          <p className="empty">No cases match.</p>
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
                  <td><span className={`badge badge-${c.status.toLowerCase()}`}>{c.status.toLowerCase()}</span></td>
                  <td>{c.assignedTo ?? <span className="sub">Unassigned</span>}</td>
                  <td>{c.flagged && <span className="badge badge-high">Flagged</span>}</td>
                  <td>{dateTime(c.updatedAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        {data && (
          <div className="actions">
            <button className="secondary" onClick={() => setPage(page - 1)} disabled={page === 0}>Previous</button>
            <span className="sub">Page {data.page + 1} of {Math.max(1, Math.ceil(data.total / data.size))} &middot; {count(data.total)} cases</span>
            <button className="secondary" onClick={() => setPage(page + 1)} disabled={!data.hasMore}>Next</button>
          </div>
        )}
      </div>
    </div>
  );
}
