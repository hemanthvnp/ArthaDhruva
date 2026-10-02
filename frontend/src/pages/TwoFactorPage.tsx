import { useEffect, useState } from 'react';
import { totpConfirm, totpDisable, totpSetup, totpStatus } from '../api/client';
import { sessionFrom } from '../auth/session';
import { useAuth } from '../auth/useAuth';
import ErrorBanner from '../components/ErrorBanner';
import type { TotpStatusResponse } from '../api/types';

export default function TwoFactorPage() {
  const [status, setStatus] = useState<TotpStatusResponse | null>(null);
  const [qrCodeDataUri, setQrCodeDataUri] = useState<string | null>(null);
  const [secret, setSecret] = useState<string | null>(null);
  const [confirmCode, setConfirmCode] = useState('');
  const [disableCode, setDisableCode] = useState('');
  const [error, setError] = useState<unknown>(null);
  const [info, setInfo] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const { login } = useAuth();

  const loadStatus = () => {
    totpStatus()
      .then(setStatus)
      .catch(setError);
  };

  useEffect(loadStatus, []);

  const startSetup = async () => {
    setError(null);
    setInfo(null);
    setLoading(true);
    try {
      const result = await totpSetup();
      setQrCodeDataUri(result.qrCodeDataUri);
      setSecret(result.secret);
    } catch (e) {
      setError(e);
    } finally {
      setLoading(false);
    }
  };

  const confirmSetup = async () => {
    setError(null);
    setLoading(true);
    try {
      // Enabling 2FA signs every other session out; this one continues on the session returned.
      const outcome = await totpConfirm(confirmCode);
      login(sessionFrom('session' in outcome ? outcome.session : outcome));
      setQrCodeDataUri(null);
      setSecret(null);
      setConfirmCode('');
      setInfo('2FA enabled. Other sessions have been signed out.');
      loadStatus();
    } catch (e) {
      setError(e);
    } finally {
      setLoading(false);
    }
  };

  const disable = async () => {
    setError(null);
    setLoading(true);
    try {
      login(sessionFrom((await totpDisable(disableCode)).session));
      setDisableCode('');
      setInfo('2FA disabled. Other sessions have been signed out.');
      loadStatus();
    } catch (e) {
      setError(e);
    } finally {
      setLoading(false);
    }
  };

  if (!status) {
    return (
      <div>
        <h2>Two-factor authentication</h2>
        <ErrorBanner error={error} />
      </div>
    );
  }

  return (
    <div>
      <h2>Two-factor authentication</h2>
      <p className="page-subtitle">
        {status.required
          ? 'Required for your role.'
          : 'Optional: add a second factor with an authenticator app.'}
      </p>

      <div className="card">
        {status.enabled ? (
          <>
            <p style={{ color: 'var(--ok)' }}>2FA is enabled.</p>
            {status.required ? (
              <p className="page-subtitle">
                It is required for your role and cannot be turned off here. If you have lost your
                authenticator, ask an administrator to reset it.
              </p>
            ) : (
              <div style={{ marginTop: '1rem' }}>
                <label style={{ fontSize: '0.82rem', color: 'var(--text-muted)', fontWeight: 500 }}>
                  Disable 2FA
                </label>
                <div className="row-inline" style={{ marginTop: '0.5rem' }}>
                  <div className="field">
                    <input
                      placeholder="6-digit code"
                      value={disableCode}
                      onChange={(e) => setDisableCode(e.target.value)}
                      inputMode="numeric"
                    />
                  </div>
                  <button className="danger-outline" disabled={loading || !disableCode} onClick={disable}>
                    Disable
                  </button>
                </div>
              </div>
            )}
          </>
        ) : qrCodeDataUri ? (
          <>
            <p className="page-subtitle">
              Scan with an authenticator app, then enter the code it shows to confirm.
            </p>
            <div style={{ textAlign: 'center', margin: '1rem 0' }}>
              <img src={qrCodeDataUri} alt="TOTP QR code" style={{ maxWidth: '100%' }} />
            </div>
            {secret && (
              <p className="page-subtitle" style={{ wordBreak: 'break-all' }}>
                Can't scan? Enter this key manually: <strong>{secret}</strong>
              </p>
            )}
            <div className="field" style={{ marginTop: '0.8rem' }}>
              <label htmlFor="tf-confirm-code">6-digit code</label>
              <input
                id="tf-confirm-code"
                value={confirmCode}
                onChange={(e) => setConfirmCode(e.target.value)}
                inputMode="numeric"
              />
            </div>
            <div className="actions">
              <button onClick={confirmSetup} disabled={loading || !confirmCode}>
                Confirm
              </button>
            </div>
          </>
        ) : (
          <div className="actions" style={{ marginTop: 0 }}>
            <button onClick={startSetup} disabled={loading}>
              Enable 2FA
            </button>
          </div>
        )}

        <ErrorBanner error={error} />
        {info && (
          <p className="page-subtitle" style={{ color: 'var(--ok)', marginTop: '0.8rem' }}>
            {info}
          </p>
        )}
      </div>
    </div>
  );
}
