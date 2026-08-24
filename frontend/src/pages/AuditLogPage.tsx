import { useEffect, useState } from 'react';
import { auditLog } from '../api/client';
import ErrorBanner from '../components/ErrorBanner';
import type { AuditLogEntry } from '../api/types';

export default function AuditLogPage() {
  const [entries, setEntries] = useState<AuditLogEntry[]>([]);
  const [error, setError] = useState<unknown>(null);
  const [loading, setLoading] = useState(true);

  const load = async () => {
    setLoading(true);
    setError(null);
    try {
      setEntries(await auditLog(100));
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
      <h2>Audit Log</h2>
      <p className="page-subtitle">
        Every call to every model-serving endpoint, persisted immutably (SR 11-7-style audit
        trail) via a Spring AOP aspect. Admin-only.
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
                <th>Endpoint</th>
                <th>Success</th>
                <th>Latency (ms)</th>
              </tr>
            </thead>
            <tbody>
              {entries.map((e) => (
                <tr key={e.id}>
                  <td>{new Date(e.occurredAt).toLocaleString()}</td>
                  <td>{e.endpoint}</td>
                  <td style={{ color: e.success ? 'var(--ok)' : 'var(--danger)' }}>
                    {e.success ? 'yes' : 'no'}
                  </td>
                  <td>{e.latencyMs}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {!loading && entries.length === 0 && <p className="page-subtitle">No audit events yet.</p>}
      </div>
    </div>
  );
}
