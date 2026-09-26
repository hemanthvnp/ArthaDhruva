import { useEffect, useState } from 'react';
import { auditLog } from '../api/client';
import ErrorBanner from '../components/ErrorBanner';
import type { AuditLogEntry } from '../api/types';

export default function AuditLogPage() {
  const [entries, setEntries] = useState<AuditLogEntry[]>([]);
  const [error, setError] = useState<unknown>(null);
  const [loading, setLoading] = useState(true);
  const [visible, setVisible] = useState(25);

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
      <div className="page-head-row">
        <div>
          <h2>Audit Log</h2>
          <p className="page-subtitle">
            Every call to every model-serving endpoint, persisted immutably (SR 11-7-style audit trail) via a Spring AOP
            aspect. Admin-only.
          </p>
        </div>
      </div>

      <div className="card">
        <div className="toolbar">
          <span style={{ color: 'var(--text-muted)', fontSize: '0.88rem' }}>
            Showing {Math.min(visible, entries.length)} of {entries.length} most recent events
          </span>
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
                <th style={{ textAlign: 'right' }}>Latency</th>
              </tr>
            </thead>
            <tbody>
              {entries.slice(0, visible).map((e) => (
                <tr key={e.id}>
                  <td>{new Date(e.occurredAt).toLocaleString()}</td>
                  <td><code>{e.endpoint}</code></td>
                  <td><span className={`badge ${e.success ? 'badge-low' : 'badge-high'}`}>{e.success ? 'OK' : 'Failed'}</span></td>
                  <td className="num" style={{ textAlign: 'right' }}>{e.latencyMs} ms</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {!loading && entries.length === 0 && <p className="empty">No audit events yet.</p>}
        {visible < entries.length && (
          <div className="actions" style={{ justifyContent: 'center' }}>
            <button className="secondary" onClick={() => setVisible((v) => v + 25)}>Show 25 more</button>
          </div>
        )}
      </div>
    </div>
  );
}
