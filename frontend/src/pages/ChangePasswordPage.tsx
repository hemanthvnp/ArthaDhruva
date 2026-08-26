import { useState } from 'react';
import { changePassword } from '../api/client';
import ErrorBanner from '../components/ErrorBanner';

export default function ChangePasswordPage() {
  const [currentPassword, setCurrentPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [confirmNewPassword, setConfirmNewPassword] = useState('');
  const [error, setError] = useState<unknown>(null);
  const [success, setSuccess] = useState(false);
  const [loading, setLoading] = useState(false);

  const submit = async () => {
    setError(null);
    setSuccess(false);
    if (newPassword !== confirmNewPassword) {
      setError(new Error('New password and confirmation do not match.'));
      return;
    }
    setLoading(true);
    try {
      await changePassword(currentPassword, newPassword);
      setSuccess(true);
      setCurrentPassword('');
      setNewPassword('');
      setConfirmNewPassword('');
    } catch (e) {
      setError(e);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div>
      <h2>Change Password</h2>
      <p className="page-subtitle">
        Passwords must be at least 10 characters and contain a letter and a digit.
      </p>

      <div className="card">
        <div className="field-grid">
          <div className="field">
            <label htmlFor="current-password">Current password</label>
            <input
              id="current-password"
              type="password"
              value={currentPassword}
              onChange={(e) => setCurrentPassword(e.target.value)}
            />
          </div>
          <div className="field">
            <label htmlFor="new-password-self">New password</label>
            <input
              id="new-password-self"
              type="password"
              value={newPassword}
              onChange={(e) => setNewPassword(e.target.value)}
            />
          </div>
          <div className="field">
            <label htmlFor="confirm-new-password">Confirm new password</label>
            <input
              id="confirm-new-password"
              type="password"
              value={confirmNewPassword}
              onChange={(e) => setConfirmNewPassword(e.target.value)}
            />
          </div>
        </div>

        <div className="actions">
          <button onClick={submit} disabled={loading || !currentPassword || !newPassword || !confirmNewPassword}>
            {loading ? 'Updating...' : 'Update password'}
          </button>
        </div>
        <ErrorBanner error={error} />
        {success && (
          <p className="page-subtitle" style={{ color: 'var(--ok)', marginTop: '0.8rem' }}>
            Password updated.
          </p>
        )}
      </div>
    </div>
  );
}
