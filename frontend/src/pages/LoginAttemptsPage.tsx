import { useEffect, useState } from 'react';
import { loginAttempts } from '../api/client';
import ErrorBanner from '../components/ErrorBanner';
import type { LoginAttemptEntry } from '../api/types';

export default function LoginAttemptsPage() {
  const [entries, setEntries] = useState<LoginAttemptEntry[]>([]);
  const [error, setError] = useState<unknown>(null);
  const [loading, setLoading] = useState(true);

  const load = async () => {
    setLoading(true);
    setError(null);
    try {
      setEntries(await loginAttempts(100));
    } catch (e) {
      setError(e);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
  }, []);

  return (
    <div>
      <h2>Login Attempts</h2>
      <p className="page-subtitle">
        A credential-free record of every login attempt -- username and outcome only, never the
        password. 5 failed attempts against the same account within a short window locks it for
        15 minutes. Admin-only.
      </p>

      <div className="card">
        <div className="actions" style={{ marginTop: 0, marginBottom: '0.9rem' }}>
          <button className="secondary" onClick={load} disabled={loading}>
            {loading ? 'Refreshing...' : 'Refresh'}
          </button>
        </div>
        <ErrorBanner error={error} />
        <div style={{ overflowX: 'auto' }}>
          <table>
            <thead>
              <tr>
                <th>Occurred at</th>
                <th>Username</th>
                <th>Success</th>
              </tr>
            </thead>
            <tbody>
              {entries.map((e) => (
                <tr key={e.id}>
                  <td>{new Date(e.occurredAt).toLocaleString()}</td>
                  <td>{e.username}</td>
                  <td style={{ color: e.success ? 'var(--ok)' : 'var(--danger)' }}>
                    {e.success ? 'yes' : 'no'}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {!loading && entries.length === 0 && <p className="page-subtitle">No login attempts yet.</p>}
      </div>
    </div>
  );
}
