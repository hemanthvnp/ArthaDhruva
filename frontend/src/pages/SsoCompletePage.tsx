import { useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { landingPathFor, useAuth } from '../auth/AuthContext';
import type { Role } from '../api/types';

/** Landing page for the end of the SSO round trip: the API redirects here with the session in the
 * URL fragment (never sent to any server or logged), which is read once and then cleared from the
 * address bar. */
export default function SsoCompletePage() {
  const { login } = useAuth();
  const navigate = useNavigate();
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    const params = new URLSearchParams(window.location.hash.slice(1));
    const token = params.get('token');
    const username = params.get('username');
    const role = params.get('role') as Role | null;
    window.history.replaceState(null, '', window.location.pathname);
    if (!token || !username || !role) {
      setFailed(true);
      return;
    }
    login({ token, username, role, sandbox: params.get('sandbox') === 'true' });
    navigate(landingPathFor(role), { replace: true });
  }, [login, navigate]);

  return failed ? (
    <p style={{ padding: '2rem' }}>Single sign-on did not complete. <Link to="/login">Back to sign in</Link></p>
  ) : (
    <p style={{ padding: '2rem' }}>Signing you in...</p>
  );
}
