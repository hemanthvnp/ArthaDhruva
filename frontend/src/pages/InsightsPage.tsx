import { useQuery, useQueryClient } from '@tanstack/react-query';
import { borrowerSegments, noteTopics } from '../api/client';
import ErrorBanner from '../components/ErrorBanner';

export default function InsightsPage() {
  const queryClient = useQueryClient();
  const topics = useQuery({ queryKey: ['note-topics'], queryFn: () => noteTopics() });
  const segments = useQuery({ queryKey: ['borrower-segments'], queryFn: () => borrowerSegments() });

  const refresh = async () => {
    const [t, s] = await Promise.all([noteTopics(true), borrowerSegments(true)]);
    queryClient.setQueryData(['note-topics'], t);
    queryClient.setQueryData(['borrower-segments'], s);
  };

  return (
    <div>
      <div className="page-head-row">
        <div>
          <h2>ML Insights</h2>
          <p className="page-subtitle">
            Unsupervised clustering, recomputed nightly and on demand. Topics group your case notes by what they talk
            about; segments group loans by their own characteristics.
          </p>
        </div>
        <button onClick={refresh}>Recompute now</button>
      </div>
      <ErrorBanner error={topics.error ?? segments.error} />

      <div className="card">
        <h3>Case-note topics</h3>
        {topics.data && <p className="page-subtitle">{topics.data.noteCount} notes &middot; computed {new Date(topics.data.computedAt).toLocaleString()}</p>}
        {topics.data?.topics.length === 0 && <p>Not enough notes yet to find topics.</p>}
        {topics.data?.topics.map((t, i) => (
          <div key={i} className="topic">
            <div className="chips" style={{ alignItems: 'center' }}>
              {t.terms.map((term) => <span key={term} className="badge badge-accent">{term}</span>)}
              <span style={{ color: 'var(--text-muted)', fontSize: '0.8rem' }}>{t.size} notes</span>
            </div>
            <ul>{t.examples.map((e, j) => <li key={j}>{e}</li>)}</ul>
          </div>
        ))}
      </div>

      <div className="card">
        <h3>Borrower segments</h3>
        {segments.data && <p className="page-subtitle">{segments.data.loanCount} loans &middot; computed {new Date(segments.data.computedAt).toLocaleString()}</p>}
        <table>
          <thead><tr><th>Loans</th><th>Defining traits</th><th>Avg credit score</th><th>Avg LTV</th><th>Avg balance</th></tr></thead>
          <tbody>
            {segments.data?.segments.map((s, i) => (
              <tr key={i}>
                <td>{s.size}</td>
                <td>{s.definingTraits.map((t) => <span key={t.feature} className="badge" style={{ marginRight: 4 }}>{t.direction} {t.feature}</span>)}</td>
                <td>{Math.round(s.averages.creditScore)}</td>
                <td>{s.averages.originalLtv.toFixed(1)}</td>
                <td>{Math.round(s.averages.originalUpb).toLocaleString()}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
