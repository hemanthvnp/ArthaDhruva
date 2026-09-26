import { useEffect, useState, type ChangeEvent } from 'react';
import { useParams, Link } from 'react-router-dom';
import {
  addLoanNote,
  downloadAttachment,
  earlyWarningScore,
  expectedLoss,
  getLoanCase,
  getLoanClients,
  listAttachments,
  listEarlyWarningCatalog,
  listLoanCatalog,
  listLoanScores,
  listTrajectoryCatalog,
  regimeForecast,
  score,
  trajectoryScore,
  updateLoanCase,
  uploadAttachment,
} from '../api/client';
import { recordLoanVisit } from '../recentLoans';
import ErrorBanner from '../components/ErrorBanner';
import RiskBadge from '../components/RiskBadge';
import type {
  AttachmentView,
  EarlyWarningCatalogEntry,
  EarlyWarningResponse,
  ExpectedLossResponse,
  LoanCaseStatus,
  LoanCaseView,
  LoanFeatures,
  LoanScoreSummary,
  RegimeForecast as RegimeForecastType,
  ScoreResponse,
  TrajectoryCatalogEntry,
  TrajectoryScoreResponse,
} from '../api/types';

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

const STATUS_OPTIONS: LoanCaseStatus[] = ['NEW', 'REVIEWED', 'ESCALATED', 'CLEARED'];

/**
 * The "one loan, every angle" view: PD, Expected Loss, Early-Warning and Trajectory (when this
 * exact loan happens to also be in those smaller catalogs), current macro regime context, and
 * the human case/notes workflow -- all in one place instead of five separate tool pages.
 */
export default function LoanDetailPage() {
  const { loanId } = useParams<{ loanId: string }>();

  const [loan, setLoan] = useState<LoanFeatures | null>(null);
  const [lastScore, setLastScore] = useState<LoanScoreSummary | null>(null);
  const [notFoundInCatalog, setNotFoundInCatalog] = useState(false);

  const [pdResult, setPdResult] = useState<ScoreResponse | null>(null);
  const [elResult, setElResult] = useState<ExpectedLossResponse | null>(null);
  const [regime, setRegime] = useState<RegimeForecastType | null>(null);

  const [ewEntry, setEwEntry] = useState<EarlyWarningCatalogEntry | null>(null);
  const [ewResult, setEwResult] = useState<EarlyWarningResponse | null>(null);
  const [trajEntry, setTrajEntry] = useState<TrajectoryCatalogEntry | null>(null);
  const [trajResult, setTrajResult] = useState<TrajectoryScoreResponse | null>(null);

  const [loanError, setLoanError] = useState<unknown>(null);
  const [actionError, setActionError] = useState<unknown>(null);
  const [busy, setBusy] = useState<string | null>(null);

  const [caseView, setCaseView] = useState<LoanCaseView | null>(null);
  const [caseStatus, setCaseStatus] = useState<LoanCaseStatus>('NEW');
  const [assignedTo, setAssignedTo] = useState('');
  const [flagged, setFlagged] = useState(false);
  const [noteText, setNoteText] = useState('');
  const [caseError, setCaseError] = useState<unknown>(null);
  const [caseSaving, setCaseSaving] = useState(false);

  const [clients, setClients] = useState<string[] | null>(null);

  const [attachments, setAttachments] = useState<AttachmentView[]>([]);
  const [attachmentError, setAttachmentError] = useState<unknown>(null);
  const [uploading, setUploading] = useState(false);
  const [downloadingId, setDownloadingId] = useState<number | null>(null);

  useEffect(() => {
    if (!loanId) return;
    recordLoanVisit(loanId);
    setLoanError(null);
    getLoanClients(loanId).then(setClients).catch(() => setClients([]));

    Promise.all([listLoanCatalog(), listLoanScores(), listEarlyWarningCatalog(), listTrajectoryCatalog(), regimeForecast(6)])
      .then(([catalog, scores, ewCatalog, trajCatalog, regimeData]) => {
        const found = catalog.find((l) => l.loanId === loanId) ?? null;
        setLoan(found);
        setNotFoundInCatalog(!found);
        setLastScore(scores.find((s) => s.loanId === loanId) ?? null);
        setRegime(regimeData);

        const ewMatch = ewCatalog.find((e) => e.label.startsWith(loanId + ' ')) ?? null;
        setEwEntry(ewMatch);
        const trajMatch = trajCatalog.find((t) => t.label.startsWith(loanId + ' ')) ?? null;
        setTrajEntry(trajMatch);
      })
      .catch(setLoanError);

    getLoanCase(loanId)
      .then((c) => {
        setCaseView(c);
        setCaseStatus(c.status);
        setAssignedTo(c.assignedTo ?? '');
        setFlagged(c.flagged);
      })
      .catch(setCaseError);

    listAttachments(loanId).then(setAttachments).catch(setAttachmentError);
  }, [loanId]);

  if (!loanId) return null;

  const computePd = async () => {
    if (!loan) return;
    setBusy('pd');
    setActionError(null);
    try {
      setPdResult(await score(loan));
      listLoanScores().then((scores) => setLastScore(scores.find((s) => s.loanId === loanId) ?? null));
    } catch (e) {
      setActionError(e);
    } finally {
      setBusy(null);
    }
  };

  const computeEl = async () => {
    if (!loan) return;
    setBusy('el');
    setActionError(null);
    try {
      setElResult(await expectedLoss(loan));
    } catch (e) {
      setActionError(e);
    } finally {
      setBusy(null);
    }
  };

  const computeEw = async () => {
    if (!ewEntry) return;
    setBusy('ew');
    setActionError(null);
    try {
      setEwResult(await earlyWarningScore(ewEntry.features));
    } catch (e) {
      setActionError(e);
    } finally {
      setBusy(null);
    }
  };

  const computeTraj = async () => {
    if (!trajEntry) return;
    setBusy('traj');
    setActionError(null);
    try {
      setTrajResult(await trajectoryScore(trajEntry.request));
    } catch (e) {
      setActionError(e);
    } finally {
      setBusy(null);
    }
  };

  const saveCase = async () => {
    setCaseSaving(true);
    setCaseError(null);
    try {
      setCaseView(await updateLoanCase(loanId, caseStatus, assignedTo || null, flagged));
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
      setAttachments((prev) => [uploaded, ...prev]);
    } catch (err) {
      setAttachmentError(err);
    } finally {
      setUploading(false);
    }
  };

  const handleDownload = async (a: AttachmentView) => {
    setDownloadingId(a.id);
    setAttachmentError(null);
    try {
      await downloadAttachment(loanId, a.id, a.filename);
    } catch (err) {
      setAttachmentError(err);
    } finally {
      setDownloadingId(null);
    }
  };

  const submitNote = async () => {
    if (!noteText.trim()) return;
    setCaseSaving(true);
    setCaseError(null);
    try {
      const updated = await addLoanNote(loanId, noteText.trim());
      setCaseView(updated);
      setNoteText('');
    } catch (e) {
      setCaseError(e);
    } finally {
      setCaseSaving(false);
    }
  };

  return (
    <div>
      <p className="page-subtitle" style={{ marginBottom: '0.5rem' }}>
        <Link to="/loans">&larr; Loan Portfolio</Link>
      </p>
      <div className="page-head-row">
        <div>
          <h2 style={{ display: 'flex', alignItems: 'center', gap: '0.75rem', flexWrap: 'wrap' }}>
            <span style={{ fontVariantNumeric: 'tabular-nums' }}>{loanId}</span>
            {(pdResult ?? lastScore) && <RiskBadge probability={(pdResult ?? lastScore)!.calibratedProbability} />}
          </h2>
          <p className="page-subtitle">Every model's view of this loan, plus the review workflow around it.</p>
        </div>
      </div>
      {clients && (
        <p className="page-subtitle">
          {clients.length === 0
            ? 'Not linked to any client account.'
            : `Linked to client account${clients.length > 1 ? 's' : ''}: ${clients.join(', ')}`}
        </p>
      )}

      <ErrorBanner error={loanError} />

      {notFoundInCatalog && (
        <p className="warn-banner">
          This loan isn't in the current sampled catalog, so its origination features aren't
          available here to recompute PD/Expected Loss -- showing its last known score only, if
          any.
        </p>
      )}

      <div className="widget-grid" style={{ marginBottom: '1.25rem' }}>
      <div className="card">
        <h3>Default risk &amp; expected loss</h3>
        {lastScore && (
          <p className="page-subtitle">
            Last computed: {(lastScore.calibratedProbability * 100).toFixed(3)}% calibrated risk,{' '}
            {new Date(lastScore.computedAt).toLocaleString()}
          </p>
        )}
        {loan ? (
          <div className="actions">
            <button onClick={computePd} disabled={busy === 'pd'}>
              {busy === 'pd' ? 'Scoring...' : 'Compute PD'}
            </button>
            <button className="secondary" onClick={computeEl} disabled={busy === 'el'}>
              {busy === 'el' ? 'Calculating...' : 'Compute Expected Loss'}
            </button>
          </div>
        ) : (
          !notFoundInCatalog && <p className="page-subtitle">Loading loan features...</p>
        )}
        <ErrorBanner error={actionError} />
        <div className="result-grid">
          {pdResult && (
            <>
              <div className="stat">
                <div className="label">Raw PD</div>
                <div className="value">{(pdResult.rawProbability * 100).toFixed(2)}%</div>
              </div>
              <div className="stat">
                <div className="label">Calibrated PD</div>
                <div className="value">{(pdResult.calibratedProbability * 100).toFixed(3)}%</div>
              </div>
            </>
          )}
          {elResult && (
            <>
              <div className="stat">
                <div className="label">LGD</div>
                <div className="value">{(elResult.lgd * 100).toFixed(2)}%</div>
              </div>
              <div className="stat">
                <div className="label">Expected loss</div>
                <div className="value">${elResult.expectedLoss.toFixed(2)}</div>
              </div>
            </>
          )}
        </div>
      </div>

      <div className="card">
        <h3>Early-warning delinquency</h3>
        {ewEntry ? (
          <>
            <p className="page-subtitle">
              Real snapshot found for this loan ({ewEntry.label}). Actual outcome:{' '}
              <strong style={{ color: ewEntry.actuallyWentDelinquent ? '#c0392b' : '#2e7d32' }}>
                {ewEntry.actuallyWentDelinquent ? 'went delinquent' : 'did not go delinquent'}
              </strong>
            </p>
            <button onClick={computeEw} disabled={busy === 'ew'}>
              {busy === 'ew' ? 'Scoring...' : 'Compute early-warning risk'}
            </button>
            {ewResult && (
              <div className="result-grid">
                <div className="stat">
                  <div className="label">Calibrated risk</div>
                  <div className="value">{(ewResult.calibratedRisk * 100).toFixed(2)}%</div>
                </div>
              </div>
            )}
          </>
        ) : (
          <p className="page-subtitle">
            Not available -- this loan isn't one of the sampled early-warning snapshots (that
            model needs trend/regime features not derivable from origination data alone). Try the{' '}
            <Link to="/early-warning">Early-Warning Delinquency</Link> page directly.
          </p>
        )}
      </div>

      <div className="card">
        <h3>Trajectory score</h3>
        {trajEntry ? (
          <>
            <p className="page-subtitle">Real observed trajectory found ({trajEntry.label}).</p>
            <button onClick={computeTraj} disabled={busy === 'traj'}>
              {busy === 'traj' ? 'Scoring...' : 'Compute trajectory score'}
            </button>
            {trajResult && (
              <div className="result-grid">
                <div className="stat">
                  <div className="label">Probability</div>
                  <div className="value">{(trajResult.probability * 100).toFixed(1)}%</div>
                </div>
              </div>
            )}
          </>
        ) : (
          <p className="page-subtitle">
            Not available -- this loan isn't one of the sampled trajectory histories. Try the{' '}
            <Link to="/trajectory">Trajectory Score</Link> page directly.
          </p>
        )}
      </div>

      <div className="card">
        <h3>Macro context</h3>
        {regime && (
          <p className="page-subtitle">
            Current regime forecast (6 months ahead): {(regime.regimeProbabilities.calm * 100).toFixed(1)}% calm,{' '}
            {(regime.regimeProbabilities.stressed * 100).toFixed(1)}% stressed. See{' '}
            <Link to="/regime-forecast">Regime Forecast</Link> for other horizons.
          </p>
        )}
      </div>

      </div>

      <div className="card">
        <h3>Case &amp; notes</h3>
        <ErrorBanner error={caseError} />
        <div className="row-inline">
          <div className="field">
            <label htmlFor="caseStatus">Status</label>
            <select id="caseStatus" value={caseStatus} onChange={(e) => setCaseStatus(e.target.value as LoanCaseStatus)}>
              {STATUS_OPTIONS.map((s) => (
                <option key={s} value={s}>
                  {s}
                </option>
              ))}
            </select>
          </div>
          <div className="field">
            <label htmlFor="assignedTo">Assigned to</label>
            <input id="assignedTo" value={assignedTo} onChange={(e) => setAssignedTo(e.target.value)} placeholder="username" />
          </div>
          <div className="field" style={{ flexDirection: 'row', alignItems: 'center', gap: '0.5rem' }}>
            <input id="flagged" type="checkbox" checked={flagged} onChange={(e) => setFlagged(e.target.checked)} />
            <label htmlFor="flagged" style={{ margin: 0 }}>
              Flagged for follow-up
            </label>
          </div>
          <button onClick={saveCase} disabled={caseSaving}>
            {caseSaving ? 'Saving...' : 'Save'}
          </button>
        </div>

        <h4 style={{ marginTop: '1.2rem' }}>Notes</h4>
        <div className="row-inline">
          <div className="field" style={{ flex: 1 }}>
            <input
              value={noteText}
              onChange={(e) => setNoteText(e.target.value)}
              placeholder="Add a note..."
              onKeyDown={(e) => e.key === 'Enter' && submitNote()}
            />
          </div>
          <button className="secondary" onClick={submitNote} disabled={caseSaving || !noteText.trim()}>
            Add note
          </button>
        </div>
        {caseView && caseView.notes.length === 0 && <p className="page-subtitle">No notes yet.</p>}
        {caseView && caseView.notes.length > 0 && (
          <ul style={{ marginTop: '0.8rem', paddingLeft: '1.2rem' }}>
            {caseView.notes.map((n, i) => (
              <li key={i} style={{ marginBottom: '0.5rem' }}>
                <strong>{n.author}</strong> ({new Date(n.createdAt).toLocaleString()}): {n.text}
              </li>
            ))}
          </ul>
        )}
      </div>

      <div className="card">
        <h3>Attachments</h3>
        <ErrorBanner error={attachmentError} />
        <div className="row-inline">
          <input type="file" onChange={handleUpload} disabled={uploading} />
          {uploading && <span className="page-subtitle">Uploading...</span>}
        </div>
        {attachments.length === 0 ? (
          <p className="page-subtitle" style={{ marginTop: '0.6rem' }}>
            No files attached to this loan yet.
          </p>
        ) : (
          <ul style={{ marginTop: '0.8rem', paddingLeft: '1.2rem' }}>
            {attachments.map((a) => (
              <li key={a.id} style={{ marginBottom: '0.4rem' }}>
                <button
                  className="secondary"
                  style={{ padding: '0.1rem 0.5rem' }}
                  onClick={() => handleDownload(a)}
                  disabled={downloadingId === a.id}
                >
                  {downloadingId === a.id ? 'Downloading...' : a.filename}
                </button>{' '}
                <span className="page-subtitle">
                  ({formatBytes(a.sizeBytes)}, uploaded by {a.uploadedBy} on{' '}
                  {new Date(a.uploadedAt).toLocaleString()})
                </span>
              </li>
            ))}
          </ul>
        )}
      </div>

      <p className="page-subtitle">
        <Link to={`/assistant?loanId=${encodeURIComponent(loanId)}`}>Ask the AI assistant about this loan &rarr;</Link>
      </p>
    </div>
  );
}
