import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { activateUser, deactivateUser, listUsers, resetUserPassword, resetUserTotp } from '../api/client';
import ErrorBanner from '../components/ErrorBanner';
import { dateOnly } from '../format';

export default function ManageUsersPage() {
  const users = useQuery({ queryKey: ['users'], queryFn: listUsers });
  const [search, setSearch] = useState('');

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
      setStatus(result.message ?? `"${username}" is now ${result.enabled ? 'enabled' : 'disabled'}.`);
      void users.refetch();
    } catch (e) {
      setError(e);
    } finally {
      setLoading(false);
    }
  };

  const filtered = (users.data ?? []).filter((u) => u.username.toLowerCase().includes(search.toLowerCase()));

  return (
    <div>
      <h2>Manage users</h2>
      <p className="page-subtitle">
        Every account in your organization. Select a row, then act on it below. Deactivating an account takes
        effect at once: its open sessions stop working on their next request.
      </p>

      <div className="card">
        <div className="row-inline" style={{ justifyContent: 'space-between' }}>
          <div className="field" style={{ minWidth: 260 }}>
            <label htmlFor="userSearch">Search</label>
            <input id="userSearch" value={search} onChange={(e) => setSearch(e.target.value)} placeholder="Filter by username..." />
          </div>
          <button className="secondary" onClick={() => users.refetch()} disabled={users.isFetching}>
            {users.isFetching ? 'Loading...' : 'Refresh'}
          </button>
        </div>
        <ErrorBanner error={users.error} />
        {users.isPending && <div className="skeleton" style={{ height: 120 }} />}
        {filtered.length > 0 && (
          <table>
            <thead>
              <tr>
                <th>Username</th>
                <th>Role</th>
                <th>Status</th>
                <th>2FA</th>
                <th>Loans</th>
                <th>Created</th>
              </tr>
            </thead>
            <tbody>
              {filtered.map((u) => (
                <tr key={u.username} className={username === u.username ? 'selected' : ''} onClick={() => setUsername(u.username)} style={{ cursor: 'pointer' }}>
                  <td>{u.username}</td>
                  <td><span className={`badge ${u.role === 'ADMIN' ? 'badge-accent' : ''}`}>{u.role.toLowerCase()}</span></td>
                  <td>
                    {!u.enabled ? <span className="badge badge-high">Disabled</span>
                      : u.locked ? <span className="badge badge-high">Locked</span>
                      : !u.activated ? <span className="badge badge-medium">Pending</span>
                      : <span className="badge badge-low">Active</span>}
                  </td>
                  <td>{u.totpEnabled ? <span className="badge badge-low">Enrolled</span> : <span className="badge">Off</span>}</td>
                  <td>{u.loanIds.length || '-'}</td>
                  <td>{dateOnly(u.createdAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        {users.data && filtered.length === 0 && <p className="empty">No matching users.</p>}
      </div>

      <div className="card">
        <h3>Act on a user</h3>
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
          <button className="secondary" disabled={loading || !username} onClick={() => run(() => resetUserTotp(username))}>
            Reset 2FA
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
