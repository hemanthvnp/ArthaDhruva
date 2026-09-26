import { useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { login as loginRequest, ssoLoginUrl } from '../api/client';
import { landingPathFor, useAuth } from '../auth/AuthContext';
import ErrorBanner from '../components/ErrorBanner';
import type { LoginOutcome } from '../api/types';

const LAST_ORG_SLUG_KEY = 'arthadhruva.lastOrgSlug';

export default function LoginPage() {
  const { login } = useAuth();
  const navigate = useNavigate();
  const [orgSlug, setOrgSlug] = useState(() => {
    try {
      return localStorage.getItem(LAST_ORG_SLUG_KEY) ?? '';
    } catch {
      return '';
    }
  });
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [totpCode, setTotpCode] = useState('');
  const [mfaRequired, setMfaRequired] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [loading, setLoading] = useState(false);
  const ssoErrorCode = new URLSearchParams(window.location.search).get('ssoError');
  const ssoError = ssoErrorCode === 'no_account' ? 'Your identity provider signed you in, but there is no account for you here. Ask an administrator.' : ssoErrorCode ? 'Single sign-on could not be completed.' : null;

  const handleOutcome = (result: LoginOutcome) => {
    if ('setupRequired' in result) {
      navigate('/setup-2fa', { state: { setupToken: result.setupToken, username } });
      return;
    }
    if ('mfaRequired' in result) {
      // A CLIENT account that's opted into 2FA but hasn't supplied a code yet -- staff accounts
      // never land here (their missing/wrong code looks like a plain wrong password by design,
      // no hint given either way), so this only fires for the two-step optional-role handshake.
      setMfaRequired(true);
      return;
    }
    login({ token: result.token, username: result.username, role: result.role, sandbox: result.sandbox });
    navigate(landingPathFor(result.role), { replace: true });
  };

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setLoading(true);
    setError(null);
    try {
      const result = await loginRequest(orgSlug, username, password, totpCode || undefined);
      try {
        localStorage.setItem(LAST_ORG_SLUG_KEY, orgSlug);
      } catch {
        // best-effort convenience only -- a blocked/full localStorage must never break login
      }
      handleOutcome(result);
    } catch (e) {
      setError(e);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="auth-page">
      <form onSubmit={submit} className="auth-card">
        <h2 style={{ marginBottom: '0.25rem' }}>ArthaDhruva Risk Console</h2>
        <p className="page-subtitle">Sign in to continue</p>

        <div className="field" style={{ marginBottom: '0.8rem' }}>
          <label htmlFor="org-slug">Organization</label>
          <input id="org-slug" value={orgSlug} onChange={(e) => setOrgSlug(e.target.value)} autoFocus />
        </div>
        <div className="field" style={{ marginBottom: '0.8rem' }}>
          <label htmlFor="username">Username</label>
          <input id="username" value={username} onChange={(e) => setUsername(e.target.value)} />
        </div>
        <div className="field" style={{ marginBottom: '0.8rem' }}>
          <label htmlFor="password">Password</label>
          <input id="password" type="password" value={password} onChange={(e) => setPassword(e.target.value)} />
        </div>

        {/* Always visible, not just after mfaRequired: staff accounts require a code from the
         * start (atomic verification), and there's no way for the frontend to know that in
         * advance -- so anyone who already has an authenticator can fill this in up front,
         * while a CLIENT without one just leaves it blank. */}
        <div className="field" style={{ marginBottom: '0.4rem' }}>
          <label htmlFor="totp-code">Authenticator code {mfaRequired ? '' : '(if you have one)'}</label>
          <input
            id="totp-code"
            value={totpCode}
            onChange={(e) => setTotpCode(e.target.value)}
            inputMode="numeric"
          />
        </div>
        {mfaRequired && (
          <p className="page-subtitle" style={{ marginTop: '-0.2rem', marginBottom: '0.6rem' }}>
            This account has 2FA enabled -- enter your code above and sign in again.
          </p>
        )}

        <ErrorBanner error={error} />

        <div className="actions">
          <button type="submit" disabled={loading || !orgSlug || !username || !password} style={{ width: '100%' }}>
            {loading ? 'Signing in...' : 'Sign in'}
          </button>
        </div>
        {orgSlug && (
          <div className="actions">
            <button type="button" className="secondary" style={{ width: '100%' }} onClick={() => { window.location.href = ssoLoginUrl(orgSlug); }}>
              Sign in with single sign-on
            </button>
          </div>
        )}
        {ssoError && <p role="alert" style={{ color: '#e57373' }}>{ssoError}</p>}
        <p className="auth-foot">
          <Link to="/forgot-password">Forgot password?</Link> &middot; <Link to="/signup">Create an organization</Link>
        </p>
      </form>
    </div>
  );
}
