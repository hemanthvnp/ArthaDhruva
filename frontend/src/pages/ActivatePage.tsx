import { useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { activateAccount } from '../api/client';
import { sessionFrom } from '../auth/session';
import { useAuth } from '../auth/useAuth';
import ErrorBanner from '../components/ErrorBanner';
import BrandMark from '../components/BrandMark';

/** Reached from an invite link: a CLIENT invited by an org admin, or an organization's first admin
 * invited when a platform admin approves an access request. The activationToken lives in the URL,
 * not in AuthContext, since the account has no session yet. A CLIENT is logged in immediately;
 * roles with mandatory 2FA are sent to sign in instead, which starts 2FA enrollment. */
export default function ActivatePage() {
  const { login } = useAuth();
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const activationToken = searchParams.get('token') ?? '';

  const [password, setPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [error, setError] = useState<unknown>(null);
  const [loading, setLoading] = useState(false);
  const [signIn, setSignIn] = useState<{ organization: string; username: string } | null>(null);

  const submit = async () => {
    setError(null);
    if (password !== confirmPassword) {
      setError(new Error('Password and confirmation do not match.'));
      return;
    }
    setLoading(true);
    try {
      const result = await activateAccount(activationToken, password);
      if ('signInRequired' in result) {
        try {
          localStorage.setItem('arthadhruva.lastOrgSlug', result.organization);
        } catch {
          // storage unavailable -- the slug is shown on screen instead
        }
        setSignIn({ organization: result.organization, username: result.username });
        return;
      }
      login(sessionFrom(result));
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

  if (signIn) {
    return (
      <div className="auth-page">
        <div className="auth-card">
          <div className="brand"><BrandMark /> ArthaDhruva</div>
          <h2 style={{ marginBottom: '0.25rem' }}>Password set</h2>
          <p className="page-subtitle">
            Sign in to organization <strong>{signIn.organization}</strong> as <strong>{signIn.username}</strong>. You will
            be asked to set up two-factor authentication.
          </p>
          <Link to="/login">Go to sign in</Link>
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
