import { useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { activateAccount } from '../api/client';
import { useAuth } from '../auth/AuthContext';
import ErrorBanner from '../components/ErrorBanner';
import BrandMark from '../components/BrandMark';

/** Reached from a CLIENT invite link created by an admin (see AdminCreateUserPage) -- the
 * activationToken lives in the URL, not in AuthContext, since the account has no session yet.
 * On success the client is logged in immediately, same as Setup2faPage's bootstrap flow. */
export default function ActivatePage() {
  const { login } = useAuth();
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const activationToken = searchParams.get('token') ?? '';

  const [password, setPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [error, setError] = useState<unknown>(null);
  const [loading, setLoading] = useState(false);

  const submit = async () => {
    setError(null);
    if (password !== confirmPassword) {
      setError(new Error('Password and confirmation do not match.'));
      return;
    }
    setLoading(true);
    try {
      const result = await activateAccount(activationToken, password);
      login({ token: result.token, username: result.username, role: result.role });
      navigate(result.role === 'CLIENT' ? '/my-loan' : '/score', { replace: true });
    } catch (e) {
      setError(e);
    } finally {
      setLoading(false);
    }
  };

  if (!activationToken) {
    return (
      <div className="auth-page">
        <div className="auth-card">
          <h2>Invalid activation link</h2>
          <p className="page-subtitle">This link is missing its activation token.</p>
        </div>
      </div>
    );
  }

  return (
    <div className="auth-page">
      <div className="auth-card">
        <div className="brand"><BrandMark /> ArthaDhruva</div>
        <h2 style={{ marginBottom: '0.25rem' }}>Activate your account</h2>
        <p className="page-subtitle">
          Set a password to finish setting up your ArthaDhruva account. Must be at least 10
          characters and contain a letter and a digit.
        </p>

        <div className="field" style={{ marginBottom: '0.8rem' }}>
          <label htmlFor="activate-password">Password</label>
          <input
            id="activate-password"
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            autoFocus
          />
        </div>
        <div className="field" style={{ marginBottom: '0.4rem' }}>
          <label htmlFor="activate-confirm-password">Confirm password</label>
          <input
            id="activate-confirm-password"
            type="password"
            value={confirmPassword}
            onChange={(e) => setConfirmPassword(e.target.value)}
          />
        </div>

        <ErrorBanner error={error} />

        <div className="actions">
          <button onClick={submit} disabled={loading || !password || !confirmPassword} style={{ width: '100%' }}>
            {loading ? 'Activating...' : 'Activate account'}
          </button>
        </div>
      </div>
    </div>
  );
}
