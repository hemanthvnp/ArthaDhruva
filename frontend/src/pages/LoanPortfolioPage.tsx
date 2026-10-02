import { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { downloadLoanScoresCsv, listLoanScores, score } from '../api/client';
import ErrorBanner from '../components/ErrorBanner';
import LoanPicker from '../components/LoanPicker';
import RiskBadge from '../components/RiskBadge';
import { riskBand, type RiskBand } from '../components/risk';
import RiskMeter from '../components/RiskMeter';
import VirtualList from '../components/VirtualList';
import { count, dateTime, pct } from '../format';
import { getRecentLoans } from '../recentLoans';
import type { LoanFeatures, ScoreResponse } from '../api/types';

type SortKey = 'loanId' | 'calibratedProbability' | 'computedAt';

const GRID = { display: 'grid', gridTemplateColumns: '2fr 1.2fr 1fr 1.6fr 2fr', gap: '0.5rem' } as const;
const ROW_HEIGHT = 40;

/**
 * The portfolio as a list of loans: pick any loan to score it from its recorded attributes, and browse
 * the scores recorded so far. The portfolio-level figures (allowance, staging, stress) are on Portfolio Risk.
 */
export default function LoanPortfolioPage() {
  const queryClient = useQueryClient();
  const scores = useQuery({ queryKey: ['loan-scores'], queryFn: listLoanScores });

  const [scoring, setScoring] = useState(false);
  const [scoreError, setScoreError] = useState<unknown>(null);
  const [justScored, setJustScored] = useState<{ loanId: string; result: ScoreResponse } | null>(null);
  const [exportError, setExportError] = useState<unknown>(null);

  const [search, setSearch] = useState('');
  const [bandFilter, setBandFilter] = useState<'ALL' | RiskBand>('ALL');
  const [sortKey, setSortKey] = useState<SortKey>('computedAt');
  const [sortDir, setSortDir] = useState<'asc' | 'desc'>('desc');
  const [recentLoans] = useState<string[]>(() => getRecentLoans());

  const rows = useMemo(() => {
    const needle = search.trim().toLowerCase();
    const filtered = (scores.data ?? []).filter((r) =>
      (!needle || r.loanId.toLowerCase().includes(needle)) && (bandFilter === 'ALL' || riskBand(r.calibratedProbability) === bandFilter));
    const dir = sortDir === 'asc' ? 1 : -1;
    return filtered.sort((a, b) => {
      if (sortKey === 'loanId') return a.loanId.localeCompare(b.loanId) * dir;
      if (sortKey === 'calibratedProbability') return (a.calibratedProbability - b.calibratedProbability) * dir;
      return (new Date(a.computedAt).getTime() - new Date(b.computedAt).getTime()) * dir;
    });
  }, [scores.data, search, bandFilter, sortKey, sortDir]);

  const mix = useMemo(() => {
    const counts: Record<RiskBand, number> = { LOW: 0, MEDIUM: 0, HIGH: 0 };
    let total = 0;
    for (const r of rows) {
      counts[riskBand(r.calibratedProbability)]++;
      total += r.calibratedProbability;
    }
    return { counts, average: rows.length > 0 ? total / rows.length : 0 };
  }, [rows]);

  const toggleSort = (key: SortKey) => {
    if (sortKey === key) {
      setSortDir((d) => (d === 'asc' ? 'desc' : 'asc'));
    } else {
      setSortKey(key);
      setSortDir(key === 'loanId' ? 'asc' : 'desc');
    }
  };
  const header = (key: SortKey, label: string) => (
    <div role="columnheader" aria-sort={sortKey === key ? (sortDir === 'asc' ? 'ascending' : 'descending') : 'none'}>
      <button type="button" className="plain-text" onClick={() => toggleSort(key)}>
        {label}{sortKey === key ? (sortDir === 'asc' ? ' ↑' : ' ↓') : ''}
      </button>
    </div>
  );

  const scoreLoan = async (loan: LoanFeatures) => {
    setScoring(true);
    setScoreError(null);
    setJustScored(null);
    try {
      setJustScored({ loanId: loan.loanId!, result: await score(loan) });
      void queryClient.invalidateQueries({ queryKey: ['loan-scores'] });
    } catch (e) {
      setScoreError(e);
    } finally {
      setScoring(false);
    }
  };

  const recorded = scores.data?.length ?? 0;

  return (
    <div>
      <div className="page-head-row">
        <div>
          <h2>Loan portfolio</h2>
          <p className="page-subtitle">
            Pick any loan in the portfolio to score it from its recorded attributes, or browse the scores recorded so far.
            For the portfolio as a whole, the allowance, staging and stress results are on <Link to="/portfolio-risk">Portfolio risk</Link>.
          </p>
        </div>
      </div>

      {recentLoans.length > 0 && (
        <div className="card">
          <div className="card-head"><h3>Recently viewed</h3></div>
          <div className="chips">
            {recentLoans.map((id) => (
              <Link key={id} to={`/loans/${encodeURIComponent(id)}`} className="chip plain">{id}</Link>
            ))}
          </div>
        </div>
      )}

      <div className="card">
        <div className="card-head"><h3>Score a loan</h3></div>
        <LoanPicker action="Score" busy={scoring} onPick={scoreLoan} />
        <ErrorBanner error={scoreError} />
        {justScored && (
          <div className="hero-score" role="status">
            <div>
              <div className="meta">
                <Link to={`/loans/${encodeURIComponent(justScored.loanId)}`}>{justScored.loanId}</Link>: default within {justScored.result.horizonMonths || 24} months
              </div>
              <div className="big">{pct(justScored.result.calibratedProbability, 2)}</div>
            </div>
            <RiskBadge probability={justScored.result.calibratedProbability} />
            {justScored.result.reasonCodes && justScored.result.reasonCodes.length > 0 && (
              <div className="secondary-stat" style={{ maxWidth: 360 }}>
                <div className="label">Main reason</div>
                <div className="sub" style={{ whiteSpace: 'normal' }}>{justScored.result.reasonCodes[0].description}</div>
              </div>
            )}
            <div style={{ flexBasis: '100%' }}><RiskMeter probability={justScored.result.calibratedProbability} scale /></div>
          </div>
        )}
      </div>

      <div className="card">
        <div className="card-head">
          <h3>Recorded scores</h3>
          <div className="actions" style={{ marginTop: 0 }}>
            <button className="secondary" onClick={() => { setExportError(null); downloadLoanScoresCsv().catch(setExportError); }} disabled={recorded === 0}>Export CSV</button>
            <button className="secondary" onClick={() => scores.refetch()} disabled={scores.isFetching}>{scores.isFetching ? 'Refreshing...' : 'Refresh'}</button>
          </div>
        </div>
        <ErrorBanner error={scores.error ?? exportError} />

        {rows.length > 0 && (
          <>
            <div className="mix" role="img" aria-label={`${mix.counts.LOW} low, ${mix.counts.MEDIUM} medium, ${mix.counts.HIGH} high risk`}>
              {mix.counts.LOW > 0 && <span className="low" style={{ flexGrow: mix.counts.LOW }} />}
              {mix.counts.MEDIUM > 0 && <span className="mid" style={{ flexGrow: mix.counts.MEDIUM }} />}
              {mix.counts.HIGH > 0 && <span className="high" style={{ flexGrow: mix.counts.HIGH }} />}
            </div>
            <div className="mix-legend" style={{ marginBottom: '1rem' }}>
              <span><i style={{ background: 'var(--sig-low)' }} /><b>{mix.counts.LOW}</b>Low, under 2%</span>
              <span><i style={{ background: 'var(--sig-mid)' }} /><b>{mix.counts.MEDIUM}</b>Medium, 2% to 10%</span>
              <span><i style={{ background: 'var(--sig-high)' }} /><b>{mix.counts.HIGH}</b>High, above 10%</span>
              <span><b>{pct(mix.average, 2)}</b>average</span>
            </div>
          </>
        )}

        <div className="form-row">
          <input aria-label="Search by loan ID" value={search} onChange={(e) => setSearch(e.target.value)} placeholder="Search by loan ID" />
          <select aria-label="Risk band" value={bandFilter} onChange={(e) => setBandFilter(e.target.value as 'ALL' | RiskBand)}>
            <option value="ALL">Every band</option>
            <option value="LOW">Low</option>
            <option value="MEDIUM">Medium</option>
            <option value="HIGH">High</option>
          </select>
        </div>

        {scores.isPending && <div className="skeleton" style={{ height: 120 }} />}
        {scores.data && recorded === 0 && <p className="empty">No loan has been scored yet. Score one above to begin.</p>}
        {recorded > 0 && rows.length === 0 && <p className="empty">No score matches the search.</p>}
        {rows.length > 0 && (
          <div role="table" aria-label="Recorded scores">
            <div role="row" className="grid-head" style={GRID}>
              {header('loanId', 'Loan')}
              {header('calibratedProbability', 'Default risk')}
              <div role="columnheader">Band</div>
              <div role="columnheader">Model</div>
              {header('computedAt', 'Scored')}
            </div>
            {/* Virtualized: only the visible rows are in the DOM, so hundreds of rows scroll as smoothly as a dozen. */}
            <VirtualList
              items={rows}
              rowHeight={ROW_HEIGHT}
              height={Math.min(480, rows.length * ROW_HEIGHT)}
              renderRow={(s) => (
                <div role="row" style={{ ...GRID, alignItems: 'center', height: ROW_HEIGHT, borderBottom: '1px solid var(--border)' }}>
                  <div role="cell"><Link to={`/loans/${encodeURIComponent(s.loanId)}`}>{s.loanId}</Link></div>
                  <div role="cell" style={{ fontVariantNumeric: 'tabular-nums' }}>{pct(s.calibratedProbability)}</div>
                  <div role="cell"><RiskBadge probability={s.calibratedProbability} /></div>
                  <div role="cell" className="sub">{s.modelVersion ?? ''}</div>
                  <div role="cell" className="sub">{dateTime(s.computedAt)}</div>
                </div>
              )}
            />
          </div>
        )}
        {recorded > 0 && (
          <p className="chart-caption">
            The {count(recorded)} most recently scored loans. The export contains every recorded score.
          </p>
        )}
      </div>
    </div>
  );
}
