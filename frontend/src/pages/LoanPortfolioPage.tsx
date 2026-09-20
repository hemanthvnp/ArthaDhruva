import { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { listLoanCatalog, listLoanScores, score } from '../api/client';
import ErrorBanner from '../components/ErrorBanner';
import VirtualList from '../components/VirtualList';
import { getRecentLoans } from '../recentLoans';
import type { LoanFeatures, LoanScoreSummary, ScoreResponse } from '../api/types';

type RiskBand = 'LOW' | 'MEDIUM' | 'HIGH';
type SortKey = 'loanId' | 'calibratedProbability' | 'computedAt';

/** Thresholds are a judgment call, roughly matched to the spread actually observed across this
 * project's own sampled/seeded loans (which ranges from well under 1% to the mid-20s%). */
function riskBand(calibratedProbability: number): RiskBand {
  if (calibratedProbability < 0.02) return 'LOW';
  if (calibratedProbability < 0.1) return 'MEDIUM';
  return 'HIGH';
}

const BAND_COLOR: Record<RiskBand, string> = { LOW: '#2e7d32', MEDIUM: '#b8860b', HIGH: '#c0392b' };
const BAND_LABEL: Record<RiskBand, string> = { LOW: 'Low', MEDIUM: 'Medium', HIGH: 'High' };

function toCsv(rows: LoanScoreSummary[]): string {
  const header = 'loanId,calibratedProbability,rawProbability,computedAt,riskBand';
  const lines = rows.map((r) =>
    [r.loanId, r.calibratedProbability, r.rawProbability, r.computedAt, riskBand(r.calibratedProbability)].join(','),
  );
  return [header, ...lines].join('\n');
}

function downloadCsv(csv: string, filename: string) {
  const blob = new Blob([csv], { type: 'text/csv' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
}

/**
 * The no-form alternative to ScorePage: browse loans that have already been scored, and pick a
 * real loan (from export_loan_catalog.py's sample of the actual dataset) to score by ID -- the
 * app fetches its real feature values and calls /score itself, no manual field entry.
 */
const GRID = { display: 'grid', gridTemplateColumns: '2fr 1.2fr 1fr 1fr 2fr', gap: '0.5rem' } as const;

export default function LoanPortfolioPage() {
  const [catalog, setCatalog] = useState<LoanFeatures[]>([]);
  const [scores, setScores] = useState<LoanScoreSummary[]>([]);
  const [loadError, setLoadError] = useState<unknown>(null);
  const [loading, setLoading] = useState(true);

  const [selectedLoanId, setSelectedLoanId] = useState('');
  const [scoring, setScoring] = useState(false);
  const [scoreError, setScoreError] = useState<unknown>(null);
  const [justScored, setJustScored] = useState<{ loanId: string; result: ScoreResponse } | null>(null);

  const [search, setSearch] = useState('');
  const [bandFilter, setBandFilter] = useState<'ALL' | RiskBand>('ALL');
  const [sortKey, setSortKey] = useState<SortKey>('computedAt');
  const [sortDir, setSortDir] = useState<'asc' | 'desc'>('desc');
  const [recentLoans] = useState<string[]>(() => getRecentLoans());

  const loadAll = async () => {
    setLoading(true);
    setLoadError(null);
    try {
      const [catalogData, scoresData] = await Promise.all([listLoanCatalog(), listLoanScores()]);
      setCatalog(catalogData);
      setScores(scoresData);
    } catch (e) {
      setLoadError(e);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadAll();
  }, []);

  const catalogByLoanId = useMemo(() => new Map(catalog.map((l) => [l.loanId, l])), [catalog]);

  const filteredSorted = useMemo(() => {
    let rows = scores;
    if (search.trim()) {
      const needle = search.trim().toLowerCase();
      rows = rows.filter((r) => {
        const state = catalogByLoanId.get(r.loanId)?.propertyState?.toLowerCase();
        return r.loanId.toLowerCase().includes(needle) || (state && state.includes(needle));
      });
    }
    if (bandFilter !== 'ALL') {
      rows = rows.filter((r) => riskBand(r.calibratedProbability) === bandFilter);
    }
    const dir = sortDir === 'asc' ? 1 : -1;
    return [...rows].sort((a, b) => {
      if (sortKey === 'loanId') return a.loanId.localeCompare(b.loanId) * dir;
      if (sortKey === 'calibratedProbability') return (a.calibratedProbability - b.calibratedProbability) * dir;
      return (new Date(a.computedAt).getTime() - new Date(b.computedAt).getTime()) * dir;
    });
  }, [scores, search, bandFilter, sortKey, sortDir, catalogByLoanId]);

  const kpis = useMemo(() => {
    if (filteredSorted.length === 0) return null;
    const avg = filteredSorted.reduce((sum, r) => sum + r.calibratedProbability, 0) / filteredSorted.length;
    const counts: Record<RiskBand, number> = { LOW: 0, MEDIUM: 0, HIGH: 0 };
    for (const r of filteredSorted) counts[riskBand(r.calibratedProbability)]++;
    return { count: filteredSorted.length, avg, counts };
  }, [filteredSorted]);

  const toggleSort = (key: SortKey) => {
    if (sortKey === key) {
      setSortDir((d) => (d === 'asc' ? 'desc' : 'asc'));
    } else {
      setSortKey(key);
      setSortDir(key === 'loanId' ? 'asc' : 'desc');
    }
  };

  const sortArrow = (key: SortKey) => (sortKey === key ? (sortDir === 'asc' ? ' ↑' : ' ↓') : '');

  const scoreSelected = async () => {
    const loan = catalog.find((l) => l.loanId === selectedLoanId);
    if (!loan) return;
    setScoring(true);
    setScoreError(null);
    setJustScored(null);
    try {
      const result = await score(loan);
      setJustScored({ loanId: loan.loanId!, result });
      const scoresData = await listLoanScores();
      setScores(scoresData);
    } catch (e) {
      setScoreError(e);
    } finally {
      setScoring(false);
    }
  };

  return (
    <div>
      <h2>Loan Portfolio</h2>
      <p className="page-subtitle">
        Real loans, not a calculator: pick an actual loan from the dataset by its Loan ID below --
        the app fetches its real feature values and scores it, no manual field entry -- or browse
        every loan anyone has already scored.
      </p>

      {recentLoans.length > 0 && (
        <div className="card">
          <h3>Recently viewed</h3>
          <div className="row-inline">
            {recentLoans.map((id) => (
              <Link key={id} to={`/loans/${encodeURIComponent(id)}`} className="secondary" style={{ padding: '0.4rem 0.8rem', borderRadius: 6, border: '1px solid var(--border, #ddd)' }}>
                {id}
              </Link>
            ))}
          </div>
        </div>
      )}

      <div className="card">
        <h3>Score a real loan by ID</h3>
        <div className="row-inline">
          <div className="field" style={{ minWidth: 260 }}>
            <label htmlFor="loanIdPicker">Loan ID</label>
            <input
              id="loanIdPicker"
              list="loan-catalog-ids"
              value={selectedLoanId}
              onChange={(e) => setSelectedLoanId(e.target.value)}
              placeholder="Start typing a real Loan ID..."
            />
            <datalist id="loan-catalog-ids">
              {catalog.map((l) => (
                <option key={l.loanId} value={l.loanId}>
                  {`credit ${l.creditScore}, ${l.propertyState}, LTV ${l.originalLtv}%`}
                </option>
              ))}
            </datalist>
          </div>
          <button
            onClick={scoreSelected}
            disabled={scoring || !catalog.some((l) => l.loanId === selectedLoanId)}
          >
            {scoring ? 'Scoring...' : 'Fetch & score'}
          </button>
        </div>
        <ErrorBanner error={scoreError} />
        {justScored && (
          <div className="result-grid">
            <div className="stat">
              <div className="label">Loan</div>
              <div className="value" style={{ fontSize: '1.1rem' }}>{justScored.loanId}</div>
            </div>
            <div className="stat">
              <div className="label">Raw probability</div>
              <div className="value">{(justScored.result.rawProbability * 100).toFixed(2)}%</div>
            </div>
            <div className="stat">
              <div className="label">Calibrated probability</div>
              <div className="value">{(justScored.result.calibratedProbability * 100).toFixed(3)}%</div>
            </div>
          </div>
        )}
        <p className="page-subtitle" style={{ marginTop: '0.75rem' }}>
          {catalog.length.toLocaleString()} real loans available to pick from.
        </p>
      </div>

      <div className="card">
        <div className="row-inline" style={{ justifyContent: 'space-between' }}>
          <h3 style={{ margin: 0 }}>Scored loans ({scores.length})</h3>
          <div className="row-inline">
            <button className="secondary" onClick={() => downloadCsv(toCsv(filteredSorted), 'scored_loans.csv')} disabled={filteredSorted.length === 0}>
              Export CSV
            </button>
            <button className="secondary" onClick={loadAll} disabled={loading}>
              {loading ? 'Loading...' : 'Refresh'}
            </button>
          </div>
        </div>
        <ErrorBanner error={loadError} />

        {kpis && (
          <div className="result-grid" style={{ marginBottom: '1rem' }}>
            <div className="stat">
              <div className="label">Loans (filtered)</div>
              <div className="value">{kpis.count}</div>
            </div>
            <div className="stat">
              <div className="label">Average calibrated risk</div>
              <div className="value">{(kpis.avg * 100).toFixed(2)}%</div>
            </div>
            <div className="stat">
              <div className="label">Low / Medium / High</div>
              <div className="value" style={{ fontSize: '1.1rem' }}>
                <span style={{ color: BAND_COLOR.LOW }}>{kpis.counts.LOW}</span>
                {' / '}
                <span style={{ color: BAND_COLOR.MEDIUM }}>{kpis.counts.MEDIUM}</span>
                {' / '}
                <span style={{ color: BAND_COLOR.HIGH }}>{kpis.counts.HIGH}</span>
              </div>
            </div>
          </div>
        )}

        <div className="row-inline" style={{ marginBottom: '0.75rem' }}>
          <div className="field" style={{ minWidth: 220 }}>
            <label htmlFor="portfolioSearch">Search</label>
            <input
              id="portfolioSearch"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              placeholder="Loan ID or state..."
            />
          </div>
          <div className="field">
            <label htmlFor="bandFilter">Risk band</label>
            <select id="bandFilter" value={bandFilter} onChange={(e) => setBandFilter(e.target.value as 'ALL' | RiskBand)}>
              <option value="ALL">All</option>
              <option value="LOW">Low</option>
              <option value="MEDIUM">Medium</option>
              <option value="HIGH">High</option>
            </select>
          </div>
        </div>

        {scores.length === 0 && !loading && (
          <p className="page-subtitle">No loans scored yet -- score one above to get started.</p>
        )}
        {filteredSorted.length === 0 && scores.length > 0 && (
          <p className="page-subtitle">No loans match the current search/filter.</p>
        )}
        {filteredSorted.length > 0 && (
          <div role="table" aria-label="Scored loans">
            <div role="row" style={{ ...GRID, fontWeight: 600, borderBottom: '1px solid #3a4a68', padding: '0.4rem 0' }}>
              <div role="columnheader" style={{ cursor: 'pointer' }} onClick={() => toggleSort('loanId')}>Loan ID{sortArrow('loanId')}</div>
              <div role="columnheader" style={{ cursor: 'pointer' }} onClick={() => toggleSort('calibratedProbability')}>Calibrated risk{sortArrow('calibratedProbability')}</div>
              <div role="columnheader">Band</div>
              <div role="columnheader">Raw risk</div>
              <div role="columnheader" style={{ cursor: 'pointer' }} onClick={() => toggleSort('computedAt')}>Computed at{sortArrow('computedAt')}</div>
            </div>
            {/* Virtualized: only the visible rows are in the DOM, so a portfolio of thousands scrolls as smoothly as a dozen. */}
            <VirtualList
              items={filteredSorted}
              rowHeight={38}
              height={Math.min(480, filteredSorted.length * 38)}
              renderRow={(s) => {
                const band = riskBand(s.calibratedProbability);
                return (
                  <div role="row" style={{ ...GRID, alignItems: 'center', height: 38 }}>
                    <div role="cell"><Link to={`/loans/${encodeURIComponent(s.loanId)}`}>{s.loanId}</Link></div>
                    <div role="cell">{(s.calibratedProbability * 100).toFixed(3)}%</div>
                    <div role="cell" style={{ color: BAND_COLOR[band], fontWeight: 600 }}>{BAND_LABEL[band]}</div>
                    <div role="cell">{(s.rawProbability * 100).toFixed(2)}%</div>
                    <div role="cell">{new Date(s.computedAt).toLocaleString()}</div>
                  </div>
                );
              }}
            />
          </div>
        )}
      </div>
    </div>
  );
}
