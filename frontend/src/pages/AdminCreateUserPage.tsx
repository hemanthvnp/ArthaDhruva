import { useState } from 'react';
import { createUser } from '../api/client';
import ErrorBanner from '../components/ErrorBanner';
import type { CreateUserResponse, Role } from '../api/types';

export default function AdminCreateUserPage() {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [role, setRole] = useState<Role>('ANALYST');
  const [loanIds, setLoanIds] = useState<string[]>(['']);
  const [result, setResult] = useState<CreateUserResponse | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [loading, setLoading] = useState(false);

  const updateLoanId = (i: number, value: string) =>
    setLoanIds(loanIds.map((l, idx) => (idx === i ? value : l)));
  const addLoanRow = () => setLoanIds([...loanIds, '']);
  const removeLoanRow = (i: number) => setLoanIds(loanIds.filter((_, idx) => idx !== i));

  const submit = async () => {
    setLoading(true);
    setError(null);
    setResult(null);
    try {
      const cleanLoanIds = loanIds.map((l) => l.trim()).filter(Boolean);
      const created = await createUser({
        username,
        password,
        role,
        loanIds: role === 'CLIENT' ? cleanLoanIds : undefined,
      });
      setResult(created);
      setUsername('');
      setPassword('');
      setLoanIds(['']);
    } catch (e) {
      setError(e);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div>
      <h2>Create User</h2>
      <p className="page-subtitle">
        There's no self-registration on this platform -- every account is provisioned here by an
        admin. For a CLIENT account, attach the loanId(s) they should be able to view on their
        "My Loan" page.
      </p>

      <div className="card">
        <div className="field-grid">
          <div className="field">
            <label htmlFor="new-username">Username</label>
            <input id="new-username" value={username} onChange={(e) => setUsername(e.target.value)} />
          </div>
          <div className="field">
            <label htmlFor="new-password">Password</label>
            <input
              id="new-password"
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
            />
          </div>
          <div className="field">
            <label htmlFor="new-role">Role</label>
            <select id="new-role" value={role} onChange={(e) => setRole(e.target.value as Role)}>
              <option value="ANALYST">ANALYST</option>
              <option value="ADMIN">ADMIN</option>
              <option value="CLIENT">CLIENT</option>
            </select>
          </div>
        </div>

        {role === 'CLIENT' && (
          <div style={{ marginTop: '1rem' }}>
            <label style={{ fontSize: '0.82rem', color: 'var(--text-muted)', fontWeight: 500 }}>
              Linked loan IDs
            </label>
            {loanIds.map((loanId, i) => (
              <div className="row-inline" key={i} style={{ marginTop: '0.5rem' }}>
                <div className="field">
                  <input value={loanId} onChange={(e) => updateLoanId(i, e.target.value)} placeholder="e.g. L-10293" />
                </div>
                <button className="danger-outline" onClick={() => removeLoanRow(i)} disabled={loanIds.length <= 1}>
                  Remove
                </button>
              </div>
            ))}
            <button className="secondary" onClick={addLoanRow} style={{ marginTop: '0.5rem' }}>
              + Add loan
            </button>
          </div>
        )}

        <div className="actions">
          <button onClick={submit} disabled={loading || !username || !password}>
            {loading ? 'Creating...' : 'Create user'}
          </button>
        </div>
        <ErrorBanner error={error} />
        {result && (
          <p className="page-subtitle" style={{ color: 'var(--ok)', marginTop: '0.8rem' }}>
            Created "{result.username}" as {result.role}
            {result.loanIds.length > 0 ? ` with loans: ${result.loanIds.join(', ')}` : ''}.
          </p>
        )}
      </div>
    </div>
  );
}
