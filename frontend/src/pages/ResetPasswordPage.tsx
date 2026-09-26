import { useState, type FormEvent } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { completePasswordReset } from '../api/client';
import ErrorBanner from '../components/ErrorBanner';

export default function ResetPasswordPage() {
  const [params] = useSearchParams();
  const token = params.get('token') ?? '';
  const [password, setPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [loading, setLoading] = useState(false);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setLoading(true);
    setError(null);
    try {
      setMessage((await completePasswordReset(token, password)).message);
    } catch (err) {
      setError(err);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="auth-page">
      <form onSubmit={submit} className="auth-card">
        <h2 style={{ marginBottom: '0.25rem' }}>Choose a new password</h2>
        {!token && <p>This link is missing its token. Request a new reset link.</p>}
        {message ? (
          <>
            <p role="status">{message}</p>
            <Link to="/login">Sign in</Link>
          </>
        ) : (
          <>
            <div className="field" style={{ marginBottom: '0.8rem' }}>
              <label htmlFor="pw">New password</label>
              <input id="pw" type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoFocus />
            </div>
            <div className="field" style={{ marginBottom: '0.8rem' }}>
              <label htmlFor="pw2">Confirm password</label>
              <input id="pw2" type="password" value={confirm} onChange={(e) => setConfirm(e.target.value)} />
            </div>
            {confirm && password !== confirm && <p style={{ color: '#e57373' }}>Passwords do not match.</p>}
            <ErrorBanner error={error} />
            <div className="actions">
              <button type="submit" disabled={loading || !token || !password || password !== confirm} style={{ width: '100%' }}>
                {loading ? 'Saving...' : 'Set password'}
              </button>
            </div>
          </>
        )}
      </form>
    </div>
  );
}
