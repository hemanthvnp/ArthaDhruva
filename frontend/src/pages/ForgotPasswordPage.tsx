import { useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { requestPasswordReset } from '../api/client';
import ErrorBanner from '../components/ErrorBanner';

export default function ForgotPasswordPage() {
  const [orgSlug, setOrgSlug] = useState('');
  const [username, setUsername] = useState('');
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [loading, setLoading] = useState(false);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setLoading(true);
    setError(null);
    try {
      setMessage((await requestPasswordReset(orgSlug, username)).message);
    } catch (err) {
      setError(err);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div style={{ minHeight: '100vh', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
      <form onSubmit={submit} className="card" style={{ width: 340 }}>
        <h2 style={{ marginBottom: '0.25rem' }}>Reset your password</h2>
        <p className="page-subtitle">We will email a reset link if the account has an email on file.</p>
        <div className="field" style={{ marginBottom: '0.8rem' }}>
          <label htmlFor="org">Organization</label>
          <input id="org" value={orgSlug} onChange={(e) => setOrgSlug(e.target.value)} autoFocus />
        </div>
        <div className="field" style={{ marginBottom: '0.8rem' }}>
          <label htmlFor="user">Username</label>
          <input id="user" value={username} onChange={(e) => setUsername(e.target.value)} />
        </div>
        <ErrorBanner error={error} />
        {message && <p role="status">{message}</p>}
        <div className="actions">
          <button type="submit" disabled={loading || !orgSlug || !username} style={{ width: '100%' }}>
            {loading ? 'Sending...' : 'Send reset link'}
          </button>
        </div>
        <p style={{ fontSize: '0.8rem', marginTop: '1rem', textAlign: 'center' }}>
          <Link to="/login">Back to sign in</Link>
        </p>
      </form>
    </div>
  );
}
