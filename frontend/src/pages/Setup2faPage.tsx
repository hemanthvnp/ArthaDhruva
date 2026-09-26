import { useEffect, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { totpConfirmWithToken, totpSetupWithToken } from '../api/client';
import { useAuth } from '../auth/AuthContext';
import ErrorBanner from '../components/ErrorBanner';

interface LocationState {
  setupToken?: string;
  username?: string;
}

/** Reached only from LoginPage's `setupRequired` branch (a mandatory-2FA account's first-ever
 * login, before it has enrolled) -- the setupToken is a short-lived, narrowly-scoped credential
 * (see JwtService#issueSetupToken / SecurityConfig's ROLE_TOTP_SETUP matcher), not a real
 * session, so it's passed via router state rather than stored in AuthContext. */
export default function Setup2faPage() {
  const { login } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const { setupToken, username } = (location.state as LocationState) ?? {};

  const [qrCodeDataUri, setQrCodeDataUri] = useState<string | null>(null);
  const [secret, setSecret] = useState<string | null>(null);
  const [code, setCode] = useState('');
  const [error, setError] = useState<unknown>(null);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (!setupToken) {
      navigate('/login', { replace: true });
      return;
    }
    totpSetupWithToken(setupToken)
      .then((result) => {
        setQrCodeDataUri(result.qrCodeDataUri);
        setSecret(result.secret);
      })
      .catch(setError);
  }, [setupToken, navigate]);

  const confirm = async () => {
    if (!setupToken) return;
    setLoading(true);
    setError(null);
    try {
      const result = await totpConfirmWithToken(setupToken, code);
      if ('token' in result) {
        login({ token: result.token, username: result.username, role: result.role });
        navigate(result.role === 'CLIENT' ? '/my-loan' : '/score', { replace: true });
      }
    } catch (e) {
      setError(e);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="auth-page">
      <div className="auth-card">
        <h2 style={{ marginBottom: '0.25rem' }}>Set up two-factor authentication</h2>
        <p className="page-subtitle">
          {username ? `Required for ${username}'s role. ` : ''}
          Scan the QR code with an authenticator app (Google Authenticator, Microsoft
          Authenticator, etc.), then enter the 6-digit code it shows.
        </p>

        {qrCodeDataUri && (
          <div style={{ textAlign: 'center', margin: '1rem 0' }}>
            <img src={qrCodeDataUri} alt="TOTP QR code" style={{ maxWidth: '100%' }} />
          </div>
        )}
        {secret && (
          <p className="page-subtitle" style={{ wordBreak: 'break-all' }}>
            Can't scan? Enter this key manually: <strong>{secret}</strong>
          </p>
        )}

        <div className="field" style={{ marginTop: '1rem' }}>
          <label htmlFor="confirm-code">6-digit code</label>
          <input id="confirm-code" value={code} onChange={(e) => setCode(e.target.value)} inputMode="numeric" />
        </div>

        <ErrorBanner error={error} />

        <div className="actions">
          <button onClick={confirm} disabled={loading || !code} style={{ width: '100%' }}>
            {loading ? 'Verifying...' : 'Confirm'}
          </button>
        </div>
      </div>
    </div>
  );
}
