import { useEffect, useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { exchangeSsoCode } from '../api/client';
import { landingPathFor, sessionFrom } from '../auth/session';
import { useAuth } from '../auth/useAuth';

/** The end of the SSO round trip. The API redirects here with a single-use, short-lived code in the URL
 * fragment (never sent to a server or written to a log); it is removed from the address bar at once
 * and exchanged for a session with a POST, so no session token ever appears in a URL. */
export default function SsoCompletePage() {
  const { login } = useAuth();
  const navigate = useNavigate();
  // Read once, on the first render: the effect below removes the code from the address bar.
  const [code] = useState(() => new URLSearchParams(window.location.hash.slice(1)).get('code'));
  const [failed, setFailed] = useState(code === null);
  const exchanged = useRef(false);

  useEffect(() => {
    if (exchanged.current) return; // the code works once; React's development double-invoke must not spend it twice
    exchanged.current = true;
    window.history.replaceState(null, '', window.location.pathname);
    if (!code) return;
    exchangeSsoCode(code)
      .then((session) => {
        login(sessionFrom(session));
        navigate(landingPathFor(session.role), { replace: true });
      })
      .catch(() => setFailed(true));
  }, [code, login, navigate]);

  return failed ? (
    <p style={{ padding: '2rem' }}>Single sign-on did not complete. <Link to="/login">Back to sign in</Link></p>
  ) : (
    <p style={{ padding: '2rem' }}>Signing you in...</p>
  );
}
