import { useQuery } from '@tanstack/react-query';
import { loginAttempts } from '../api/client';
import ErrorBanner from '../components/ErrorBanner';
import { dateTime } from '../format';

export default function LoginAttemptsPage() {
  const attempts = useQuery({ queryKey: ['login-attempts'], queryFn: () => loginAttempts(100) });

  return (
    <div>
      <div className="page-head-row">
        <div>
          <h2>Sign-in attempts</h2>
          <p className="page-subtitle">
            Every attempt to sign in, with the username and the outcome and never the password. Repeated failures
            against one account lock it for a while. Like the audit log, this record cannot be edited.
          </p>
        </div>
        <button className="secondary" onClick={() => attempts.refetch()} disabled={attempts.isFetching}>
          {attempts.isFetching ? 'Refreshing...' : 'Refresh'}
        </button>
      </div>

      <div className="card">
        <ErrorBanner error={attempts.error} />
        {attempts.isPending && <div className="skeleton" style={{ height: 120 }} />}
        {attempts.data && attempts.data.length === 0 && <p className="empty">No sign-in attempts yet.</p>}
        {attempts.data && attempts.data.length > 0 && (
          <div className="table-scroll">
            <table>
              <thead>
                <tr>
                  <th>When</th>
                  <th>Username</th>
                  <th>Outcome</th>
                </tr>
              </thead>
              <tbody>
                {attempts.data.map((e) => (
                  <tr key={e.id}>
                    <td>{dateTime(e.occurredAt)}</td>
                    <td>{e.username}</td>
                    <td><span className={`badge ${e.success ? 'badge-low' : 'badge-high'}`}>{e.success ? 'Signed in' : 'Refused'}</span></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {attempts.data && attempts.data.length > 0 && <p className="chart-caption">The latest {attempts.data.length} attempts.</p>}
      </div>
    </div>
  );
}
