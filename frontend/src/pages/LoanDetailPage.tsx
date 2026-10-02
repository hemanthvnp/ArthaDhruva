import { useEffect, useState, type ChangeEvent } from 'react';
import { useParams, Link } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  ApiError,
  addLoanNote,
  deleteAttachment,
  downloadAttachment,
  earlyWarningScore,
  expectedLoss,
  getCachedScore,
  getLoan,
  getLoanCase,
  getLoanClients,
  listAttachments,
  listEarlyWarningCatalog,
  listTrajectoryCatalog,
  regimeForecast,
  score,
  trajectoryScore,
  updateLoanCase,
  uploadAttachment,
} from '../api/client';
import { useAuth } from '../auth/useAuth';
import { recordLoanVisit } from '../recentLoans';
import ErrorBanner from '../components/ErrorBanner';
import RiskBadge from '../components/RiskBadge';
import RiskMeter from '../components/RiskMeter';
import { dateTime, money, monthLabel, pct } from '../format';
import type {
  AttachmentView,
  CaseEventView,
  EarlyWarningResponse,
  ExpectedLossResponse,
  LoanCaseStatus,
  LoanCaseView,
  ScoreResponse,
  TrajectoryScoreResponse,
} from '../api/types';

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

const STATUS_LABEL: Record<LoanCaseStatus, string> = { NEW: 'New', REVIEWED: 'Reviewed', ESCALATED: 'Escalated', CLEARED: 'Cleared' };
const STAGE_BADGE = ['', 'badge-low', 'badge-medium', 'badge-high'];

// The review lifecycle, as the server enforces it. Mirrored here only so the form offers legal moves and
// asks for a reason up front; the server remains the authority and says so if this ever falls behind.
const NEXT: Record<LoanCaseStatus, LoanCaseStatus[]> = {
  NEW: ['REVIEWED', 'ESCALATED', 'CLEARED'],
  REVIEWED: ['ESCALATED', 'CLEARED'],
  ESCALATED: ['REVIEWED', 'CLEARED'],
  CLEARED: ['REVIEWED'],
};
const needsReason = (from: LoanCaseStatus, to: LoanCaseStatus) =>
  (to === 'ESCALATED' && from !== 'ESCALATED') || (from === 'ESCALATED' && to === 'CLEARED') || (from === 'CLEARED' && to === 'REVIEWED');

function describe(e: CaseEventView): string {
  const parts: string[] = [];
  if (e.fromStatus === null) parts.push(`opened as ${STATUS_LABEL[e.toStatus].toLowerCase()}`);
  else if (e.fromStatus !== e.toStatus) parts.push(`${STATUS_LABEL[e.fromStatus].toLowerCase()} to ${STATUS_LABEL[e.toStatus].toLowerCase()}`);
  if (e.fromAssignee !== e.toAssignee) parts.push(e.toAssignee ? `assigned to ${e.toAssignee}` : 'unassigned');
  if ((e.fromFlagged ?? false) !== e.toFlagged) parts.push(e.toFlagged ? 'flagged' : 'flag removed');
  return parts.join(', ') || 'no change';
}

/** Keyed by the loan: moving from one loan to another starts from a clean page, with nothing of the last one left on it. */
export default function LoanDetailPage() {
  const { loanId = '' } = useParams<{ loanId: string }>();
  return loanId ? <LoanDetail key={loanId} loanId={loanId} /> : null;
}

/**
 * One loan from every angle: what it is, what each model says about it, the macro backdrop, and the
 * review workflow around it (case, notes, history, documents).
 */
function LoanDetail({ loanId }: { loanId: string }) {
  const { auth } = useAuth();
  const queryClient = useQueryClient();

  const loan = useQuery({ queryKey: ['loan', loanId], queryFn: () => getLoan(loanId) });
  const recorded = useQuery({ queryKey: ['score', loanId], queryFn: () => getCachedScore(loanId) });
  const clients = useQuery({ queryKey: ['loan-clients', loanId], queryFn: () => getLoanClients(loanId) });
  const regime = useQuery({ queryKey: ['regime-forecast', 12], queryFn: () => regimeForecast(12) });
  const earlyWarningCatalog = useQuery({ queryKey: ['early-warning-catalog'], queryFn: listEarlyWarningCatalog, staleTime: Infinity });
  const trajectoryCatalog = useQuery({ queryKey: ['trajectory-catalog'], queryFn: listTrajectoryCatalog, staleTime: Infinity });
  const caseQuery = useQuery({ queryKey: ['loan-case', loanId], queryFn: () => getLoanCase(loanId) });
  const attachments = useQuery({ queryKey: ['attachments', loanId], queryFn: () => listAttachments(loanId) });

  const [pdResult, setPdResult] = useState<ScoreResponse | null>(null);
  const [elResult, setElResult] = useState<ExpectedLossResponse | null>(null);
  const [ewResult, setEwResult] = useState<EarlyWarningResponse | null>(null);
  const [trajResult, setTrajResult] = useState<TrajectoryScoreResponse | null>(null);
  const [actionError, setActionError] = useState<unknown>(null);
  const [busy, setBusy] = useState<string | null>(null);

  const [caseStatus, setCaseStatus] = useState<LoanCaseStatus>('NEW');
  const [assignedTo, setAssignedTo] = useState('');
  const [flagged, setFlagged] = useState(false);
  const [reason, setReason] = useState('');
  const [noteText, setNoteText] = useState('');
  const [caseError, setCaseError] = useState<unknown>(null);
  const [caseNotice, setCaseNotice] = useState<string | null>(null);
  const [caseSaving, setCaseSaving] = useState(false);

  const [attachmentError, setAttachmentError] = useState<unknown>(null);
  const [uploading, setUploading] = useState(false);
  const [workingOn, setWorkingOn] = useState<number | null>(null);

  useEffect(() => recordLoanVisit(loanId), [loanId]);

  // The form follows the server's copy of the case: on load, after a save, and when a conflict reloads it.
  const current = caseQuery.data;
  const [shown, setShown] = useState<LoanCaseView | undefined>(undefined);
  if (current && current !== shown) {
    setShown(current);
    setCaseStatus(current.status);
    setAssignedTo(current.assignedTo ?? '');
    setFlagged(current.flagged);
  }

  // The case lists and the note feed on other pages are built from the same data: they are stale now too.
  const showCase = (c: LoanCaseView) => {
    queryClient.setQueryData(['loan-case', loanId], c);
    for (const key of ['loan-cases', 'recent-notes', 'case-search']) {
      void queryClient.invalidateQueries({ queryKey: [key] });
    }
  };
  const features = loan.data ?? null;
  const ewEntry = earlyWarningCatalog.data?.find((e) => e.label.startsWith(`${loanId} `)) ?? null;
  const trajEntry = trajectoryCatalog.data?.find((t) => t.label.startsWith(`${loanId} `)) ?? null;
  const pd = pdResult?.calibratedProbability ?? recorded.data?.score.calibratedProbability;

  const run = async (key: string, action: () => Promise<void>) => {
    setBusy(key);
    setActionError(null);
    try {
      await action();
    } catch (e) {
      setActionError(e);
    } finally {
      setBusy(null);
    }
  };

  const computePd = () => run('pd', async () => {
    if (!features) return;
    setPdResult(await score({ ...features, loanId }));
    void queryClient.invalidateQueries({ queryKey: ['score', loanId] });
    void queryClient.invalidateQueries({ queryKey: ['loan-scores'] });
  });
  const computeEl = () => run('el', async () => {
    if (features) setElResult(await expectedLoss(features));
  });
  const computeEw = () => run('ew', async () => {
    if (ewEntry) setEwResult(await earlyWarningScore(ewEntry.features));
  });
  const computeTraj = () => run('traj', async () => {
    if (trajEntry) setTrajResult(await trajectoryScore(trajEntry.request));
  });

  const assignee = assignedTo.trim() || null;
  const dirty = current !== undefined && (caseStatus !== current.status || assignee !== current.assignedTo || flagged !== current.flagged);
  const reasonRequired = current !== undefined && needsReason(current.status, caseStatus);
  const fourEyes = current?.status === 'ESCALATED' && caseStatus === 'CLEARED' && current.escalatedBy === auth?.username;

  const saveCase = async () => {
    if (!current) return;
    setCaseSaving(true);
    setCaseError(null);
    setCaseNotice(null);
    try {
      showCase(await updateLoanCase(loanId, { status: caseStatus, assignedTo: assignee, flagged, reason: reason.trim() || undefined, expectedVersion: current.version }));
      setReason('');
    } catch (e) {
      if (e instanceof ApiError && e.status === 409) {
        // Someone else changed the case since it was loaded. Theirs is the truth: show it, drop this edit.
        await queryClient.invalidateQueries({ queryKey: ['loan-case', loanId] });
        setCaseNotice('Someone else changed this case while you were editing. Their version is shown; make your change again if it still applies.');
      } else {
        setCaseError(e);
      }
    } finally {
      setCaseSaving(false);
    }
  };

  const submitNote = async () => {
    if (!noteText.trim()) return;
    setCaseSaving(true);
    setCaseError(null);
    try {
      showCase(await addLoanNote(loanId, noteText.trim()));
      setNoteText('');
    } catch (e) {
      setCaseError(e);
    } finally {
      setCaseSaving(false);
    }
  };

  const handleUpload = async (e: ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    e.target.value = ''; // allow re-selecting the same file later
    if (!file) return;
    setUploading(true);
    setAttachmentError(null);
    try {
      const uploaded = await uploadAttachment(loanId, file);
      queryClient.setQueryData<AttachmentView[]>(['attachments', loanId], (previous) => [uploaded, ...(previous ?? [])]);
    } catch (err) {
      setAttachmentError(err);
    } finally {
      setUploading(false);
    }
  };

  const withAttachment = async (a: AttachmentView, action: () => Promise<void>) => {
    setWorkingOn(a.id);
    setAttachmentError(null);
    try {
      await action();
    } catch (err) {
      setAttachmentError(err);
    } finally {
      setWorkingOn(null);
    }
  };
  const handleDownload = (a: AttachmentView) => withAttachment(a, () => downloadAttachment(loanId, a.id, a.filename));
  const handleRemove = (a: AttachmentView) => withAttachment(a, async () => {
    if (!window.confirm(`Remove ${a.filename}? This cannot be undone.`)) return;
    await deleteAttachment(loanId, a.id);
    queryClient.setQueryData<AttachmentView[]>(['attachments', loanId], (previous) => (previous ?? []).filter((x) => x.id !== a.id));
  });

  return (
    <div>
      <p className="page-subtitle" style={{ marginBottom: '0.5rem' }}>
        <Link to="/loans">&larr; Loan portfolio</Link>
      </p>
      <div className="page-head-row">
        <div>
          <h2 style={{ display: 'flex', alignItems: 'center', gap: '0.75rem', flexWrap: 'wrap' }}>
            <span style={{ fontVariantNumeric: 'tabular-nums' }}>{loanId}</span>
            {pd !== undefined && <RiskBadge probability={pd} />}
            {current?.flagged && <span className="badge badge-high">Flagged</span>}
          </h2>
          {pd !== undefined && <div style={{ maxWidth: 320, margin: '0.5rem 0 0.75rem' }}><RiskMeter probability={pd} scale /></div>}
          <p className="page-subtitle">
            {clients.data === undefined
              ? 'Every model’s view of this loan, and the review workflow around it.'
              : clients.data.length === 0
                ? 'Not linked to a client account.'
                : `Linked to client account${clients.data.length > 1 ? 's' : ''}: ${clients.data.join(', ')}`}
          </p>
        </div>
      </div>

      <ErrorBanner error={loan.error} />
      {loan.data === null && (
        <p className="warn-banner" style={{ marginBottom: '1.25rem' }}>
          This loan is not in the portfolio, so the models cannot be run on it from here. Its recorded score, if any, and
          its case are shown.
        </p>
      )}

      {features && (
        <div className="card">
          <div className="card-head">
            <h3>The loan</h3>
            <Link to={`/lifetime-risk?loanId=${encodeURIComponent(loanId)}`}>Lifetime risk and stress scenarios &rarr;</Link>
          </div>
          <dl className="facts" style={{ borderTop: 'none', paddingTop: 0, marginTop: '0.5rem' }}>
            <div><dt>Amount</dt><dd>{money(features.originalUpb)}</dd></div>
            <div><dt>Note rate</dt><dd>{features.originalInterestRate}% over {features.originalLoanTerm} months</dd></div>
            <div><dt>Originated</dt><dd>{features.originationMonth ? monthLabel(features.originationMonth) : 'new application'}</dd></div>
            <div><dt>Credit score</dt><dd>{features.creditScore}</dd></div>
            <div><dt>Loan-to-value</dt><dd>{features.originalLtv}% (combined {features.originalCltv}%)</dd></div>
            <div><dt>Debt-to-income</dt><dd>{features.originalDti}%</dd></div>
            <div><dt>Property</dt><dd>{features.propertyType}, {features.numberOfUnits} unit{features.numberOfUnits === 1 ? '' : 's'}, {features.propertyState}</dd></div>
            <div><dt>Borrowers</dt><dd>{features.numberOfBorrowers}{features.firstTimeHomebuyerFlag === 'Y' ? ', first-time buyer' : ''}</dd></div>
          </dl>
        </div>
      )}

      <ErrorBanner error={actionError} />
      <div className="widget-grid" style={{ margin: '1.25rem 0' }}>
        <div className="card">
          <div className="card-head"><h3>Default risk</h3></div>
          <p className="page-subtitle">
            {recorded.data
              ? `Recorded ${dateTime(recorded.data.computedAt)}: ${pct(recorded.data.score.calibratedProbability)} within ${recorded.data.score.horizonMonths || 24} months of origination${recorded.data.score.modelVersion ? `, model ${recorded.data.score.modelVersion}` : ''}.`
              : 'Not scored yet. Scoring records the result against this loan.'}
          </p>
          {features && (
            <button onClick={computePd} disabled={busy === 'pd'}>{busy === 'pd' ? 'Scoring...' : recorded.data ? 'Score again' : 'Score'}</button>
          )}
          {pdResult && (
            <>
              <div className="result-grid">
                <div className="stat">
                  <div className="label">Probability of default</div>
                  <div className="value">{pct(pdResult.calibratedProbability)}</div>
                </div>
                {pdResult.baselineProbability != null && (
                  <div className="stat">
                    <div className="label">A typical loan</div>
                    <div className="value">{pct(pdResult.baselineProbability)}</div>
                  </div>
                )}
              </div>
              {pdResult.warnings && pdResult.warnings.length > 0 && (
                <div className="warn-banner">{pdResult.warnings.map((w) => <div key={w}>{w}</div>)}</div>
              )}
              {pdResult.reasonCodes && pdResult.reasonCodes.length > 0 && (
                <ul className="list" style={{ marginTop: '0.75rem' }}>
                  {pdResult.reasonCodes.map((r) => (
                    <li key={r.code + r.feature} className="list-row">
                      <span className="grow" style={{ whiteSpace: 'normal' }}>{r.description}</span>
                      <span className="num">+{(r.contribution * 100).toFixed(2)} pts</span>
                    </li>
                  ))}
                </ul>
              )}
            </>
          )}
        </div>

        <div className="card">
          <div className="card-head"><h3>Expected credit loss</h3></div>
          <p className="page-subtitle">Twelve-month and lifetime loss under the baseline scenario, from the loan's balance today.</p>
          {features && (
            <button className="secondary" onClick={computeEl} disabled={busy === 'el'}>{busy === 'el' ? 'Calculating...' : 'Calculate'}</button>
          )}
          {elResult && (
            <>
              <div className="result-grid">
                <div className="stat">
                  <div className="label">Default within 12 months</div>
                  <div className="value">{pct(elResult.pd)}</div>
                </div>
                <div className="stat">
                  <div className="label">Allowance (IFRS 9)</div>
                  <div className="value">{money(elResult.eclIfrs9)}</div>
                </div>
              </div>
              <dl className="facts">
                <div><dt>Stage</dt><dd><span className={`badge ${STAGE_BADGE[elResult.stage] ?? ''}`}>Stage {elResult.stage}</span></dd></div>
                <div><dt>Exposure today</dt><dd>{money(elResult.ead)}</dd></div>
                <div><dt>Loss given default</dt><dd>{pct(elResult.lgd, 1)}</dd></div>
                <div><dt>Lifetime loss (CECL)</dt><dd>{money(elResult.expectedLossLifetime)}</dd></div>
              </dl>
            </>
          )}
        </div>

        <div className="card">
          <div className="card-head"><h3>Early warning</h3></div>
          {ewEntry ? (
            <>
              <p className="page-subtitle">
                A payment-history snapshot exists for this loan. What happened afterwards:{' '}
                <strong style={{ color: ewEntry.actuallyWentDelinquent ? 'var(--danger)' : 'var(--ok)' }}>
                  {ewEntry.actuallyWentDelinquent ? 'it went delinquent' : 'it stayed current'}
                </strong>.
              </p>
              <button className="secondary" onClick={computeEw} disabled={busy === 'ew'}>{busy === 'ew' ? 'Scoring...' : 'Score the snapshot'}</button>
              {ewResult && (
                <div className="result-grid">
                  <div className="stat">
                    <div className="label">30+ days late within 3 months</div>
                    <div className="value">{pct(ewResult.calibratedRisk)}</div>
                  </div>
                </div>
              )}
            </>
          ) : (
            <p className="page-subtitle">
              No payment-history snapshot for this loan. The early-warning model needs one; try it on the sample
              snapshots on the <Link to="/early-warning">Early warning</Link> page.
            </p>
          )}
        </div>

        <div className="card">
          <div className="card-head"><h3>Macro backdrop</h3></div>
          {regime.data ? (
            <p className="page-subtitle">
              The regime decoded for {monthLabel(regime.data.asOfMonth.slice(0, 7))} is <strong>{regime.data.currentRegime}</strong>. Twelve months
              on, the chance of stress is {pct(regime.data.regimeProbabilities.stressed, 1)}, against a long-run share
              of {pct(regime.data.stationary.stressed, 1)}. See the <Link to="/regime-forecast">regime forecast</Link>.
            </p>
          ) : (
            <div className="skeleton" />
          )}
          {trajEntry && (
            <>
              <div className="section-label" style={{ marginTop: '1rem' }}>Payment trajectory</div>
              <p className="page-subtitle">An observed payment history exists for this loan ({trajEntry.request.months.length} months).</p>
              <button className="secondary" onClick={computeTraj} disabled={busy === 'traj'}>{busy === 'traj' ? 'Scoring...' : 'Score the trajectory'}</button>
              {trajResult && (
                <div className="result-grid">
                  <div className="stat">
                    <div className="label">Trajectory score</div>
                    <div className="value">{pct(trajResult.probability, 1)}</div>
                    <div className="sub">ranks histories; not a calibrated probability</div>
                  </div>
                </div>
              )}
            </>
          )}
        </div>
      </div>

      <div className="split" style={{ marginBottom: '1.25rem' }}>
        <div className="card">
          <div className="card-head">
            <h3>Case</h3>
            {current && <span className={`badge badge-${current.status.toLowerCase()}`}>{STATUS_LABEL[current.status]}</span>}
          </div>
          <ErrorBanner error={caseQuery.error ?? caseError} />
          {caseNotice && <p className="warn-banner" role="status">{caseNotice}</p>}
          <div className="row-inline" style={{ marginTop: '0.75rem' }}>
            <div className="field">
              <label htmlFor="caseStatus">Status</label>
              <select id="caseStatus" value={caseStatus} onChange={(e) => setCaseStatus(e.target.value as LoanCaseStatus)} disabled={!current}>
                {(current ? [current.status, ...NEXT[current.status]] : [caseStatus]).map((s) => (
                  <option key={s} value={s}>{STATUS_LABEL[s]}</option>
                ))}
              </select>
            </div>
            <div className="field">
              <label htmlFor="assignedTo">Assigned to</label>
              <input id="assignedTo" value={assignedTo} onChange={(e) => setAssignedTo(e.target.value)} placeholder="username" disabled={!current} />
            </div>
            <div className="field" style={{ flexDirection: 'row', alignItems: 'center', gap: '0.5rem', flex: 'none' }}>
              <input id="flagged" type="checkbox" checked={flagged} onChange={(e) => setFlagged(e.target.checked)} disabled={!current} />
              <label htmlFor="flagged" style={{ margin: 0 }}>Flagged for follow-up</label>
            </div>
          </div>
          {reasonRequired && (
            <div className="field" style={{ marginBottom: '0.6rem' }}>
              <label htmlFor="caseReason">Reason (required for this change, and kept in the history)</label>
              <input id="caseReason" value={reason} maxLength={500} onChange={(e) => setReason(e.target.value)} />
            </div>
          )}
          {fourEyes && (
            <p className="warn-banner">You escalated this case, so someone else has to clear it.</p>
          )}
          <div className="actions" style={{ marginTop: '0.25rem' }}>
            <button onClick={saveCase} disabled={caseSaving || !dirty || fourEyes || (reasonRequired && !reason.trim())}>
              {caseSaving ? 'Saving...' : 'Save'}
            </button>
            {current?.escalatedBy && current.status === 'ESCALATED' && <span className="sub">Escalated by {current.escalatedBy}</span>}
          </div>

          <div className="section-label" style={{ marginTop: '1.5rem' }}>Notes</div>
          <div className="row-inline">
            <div className="field" style={{ flex: 1 }}>
              <input
                aria-label="New note"
                value={noteText}
                maxLength={2000}
                onChange={(e) => setNoteText(e.target.value)}
                placeholder="Add a note..."
                onKeyDown={(e) => e.key === 'Enter' && submitNote()}
              />
            </div>
            <button className="secondary" onClick={submitNote} disabled={caseSaving || !noteText.trim()}>Add note</button>
          </div>
          {current && current.notes.length === 0 && <p className="empty">No notes yet.</p>}
          {current && current.notes.length > 0 && (
            <ul className="list">
              {current.notes.map((n) => (
                <li key={`${n.createdAt}-${n.author}`} className="list-row" style={{ alignItems: 'flex-start' }}>
                  <span className="grow" style={{ whiteSpace: 'normal' }}>
                    <strong>{n.author}</strong>
                    <div>{n.text}</div>
                  </span>
                  <span className="sub">{dateTime(n.createdAt)}</span>
                </li>
              ))}
            </ul>
          )}
        </div>

        <div className="card">
          <div className="card-head"><h3>History</h3></div>
          {current && current.history.length === 0 && <p className="empty">Nothing has been changed on this case yet.</p>}
          {current && current.history.length > 0 && (
            <ul className="list">
              {current.history.map((e) => (
                <li key={`${e.occurredAt}-${e.actor}`} className="list-row" style={{ alignItems: 'flex-start' }}>
                  <span className="grow" style={{ whiteSpace: 'normal' }}>
                    <strong>{e.actor}</strong> {describe(e)}
                    {e.reason && <div className="sub">“{e.reason}”</div>}
                    <div className="sub">{dateTime(e.occurredAt)}</div>
                  </span>
                </li>
              ))}
            </ul>
          )}
          <p className="chart-caption">Every change is recorded with who made it and cannot be edited afterwards.</p>
        </div>
      </div>

      <div className="card">
        <div className="card-head"><h3>Documents</h3></div>
        <ErrorBanner error={attachments.error ?? attachmentError} />
        <div className="row-inline" style={{ marginTop: '0.5rem' }}>
          <input type="file" aria-label="Attach a document" onChange={handleUpload} disabled={uploading} />
          {uploading && <span className="sub">Uploading...</span>}
        </div>
        {attachments.data && attachments.data.length === 0 && <p className="empty">No documents attached to this loan yet.</p>}
        {attachments.data && attachments.data.length > 0 && (
          <ul className="list">
            {attachments.data.map((a) => (
              <li key={a.id} className="list-row">
                <span className="grow">
                  <button type="button" className="plain-text" onClick={() => handleDownload(a)} disabled={workingOn === a.id}><b>{a.filename}</b></button>
                  <div className="sub">{formatBytes(a.sizeBytes)} &middot; {a.uploadedBy} &middot; {dateTime(a.uploadedAt)}</div>
                </span>
                {auth?.role === 'ADMIN' && (
                  <button className="danger-outline" onClick={() => handleRemove(a)} disabled={workingOn === a.id}>Remove</button>
                )}
              </li>
            ))}
          </ul>
        )}
      </div>

      <p className="page-subtitle">
        <Link to={`/assistant?loanId=${encodeURIComponent(loanId)}`}>Ask the assistant about this loan &rarr;</Link>
      </p>
    </div>
  );
}
