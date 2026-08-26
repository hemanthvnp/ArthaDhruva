import { useState } from 'react';
import { activateUser, deactivateUser, resetUserPassword } from '../api/client';
import ErrorBanner from '../components/ErrorBanner';

export default function ManageUsersPage() {
  const [username, setUsername] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [error, setError] = useState<unknown>(null);
  const [status, setStatus] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  const run = async (action: () => Promise<{ message?: string; enabled?: boolean }>) => {
    setError(null);
    setStatus(null);
    setLoading(true);
    try {
      const result = await action();
      setStatus(
        result.message ?? `"${username}" is now ${result.enabled ? 'enabled' : 'disabled'}.`
      );
    } catch (e) {
      setError(e);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div>
      <h2>Manage Users</h2>
      <p className="page-subtitle">
        There's no user directory yet -- type the exact username to act on. Deactivating an
        account takes effect immediately, even for a token issued before the change.
      </p>

      <div className="card">
        <div className="field">
          <label htmlFor="manage-username">Username</label>
          <input id="manage-username" value={username} onChange={(e) => setUsername(e.target.value)} />
        </div>

        <div style={{ marginTop: '1rem' }}>
          <label style={{ fontSize: '0.82rem', color: 'var(--text-muted)', fontWeight: 500 }}>
            Reset password
          </label>
          <div className="row-inline" style={{ marginTop: '0.5rem' }}>
            <div className="field">
              <input
                type="password"
                placeholder="New password"
                value={newPassword}
                onChange={(e) => setNewPassword(e.target.value)}
              />
            </div>
            <button
              className="secondary"
              disabled={loading || !username || !newPassword}
              onClick={() => run(() => resetUserPassword(username, newPassword))}
            >
              Reset password
            </button>
          </div>
        </div>

        <div className="actions">
          <button disabled={loading || !username} onClick={() => run(() => deactivateUser(username))}>
            Deactivate
          </button>
          <button className="secondary" disabled={loading || !username} onClick={() => run(() => activateUser(username))}>
            Activate
          </button>
        </div>

        <ErrorBanner error={error} />
        {status && (
          <p className="page-subtitle" style={{ color: 'var(--ok)', marginTop: '0.8rem' }}>
            {status}
          </p>
        )}
      </div>
    </div>
  );
}
