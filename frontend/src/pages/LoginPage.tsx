import { useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { login as loginRequest } from '../api/client';
import { useAuth } from '../auth/AuthContext';
import ErrorBanner from '../components/ErrorBanner';

export default function LoginPage() {
  const { login } = useAuth();
  const navigate = useNavigate();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<unknown>(null);
  const [loading, setLoading] = useState(false);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setLoading(true);
    setError(null);
    try {
      const result = await loginRequest(username, password);
      login({ token: result.token, username: result.username, role: result.role });
      navigate('/score', { replace: true });
    } catch (e) {
      setError(e);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div style={{ minHeight: '100vh', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
      <form onSubmit={submit} className="card" style={{ width: 340 }}>
        <h2 style={{ marginBottom: '0.25rem' }}>ArthaDhruva Risk Console</h2>
        <p className="page-subtitle">Sign in to continue</p>

        <div className="field" style={{ marginBottom: '0.8rem' }}>
          <label htmlFor="username">Username</label>
          <input id="username" value={username} onChange={(e) => setUsername(e.target.value)} autoFocus />
        </div>
        <div className="field" style={{ marginBottom: '0.4rem' }}>
          <label htmlFor="password">Password</label>
          <input id="password" type="password" value={password} onChange={(e) => setPassword(e.target.value)} />
        </div>

        <ErrorBanner error={error} />

        <div className="actions">
          <button type="submit" disabled={loading || !username || !password} style={{ width: '100%' }}>
            {loading ? 'Signing in...' : 'Sign in'}
          </button>
        </div>
      </form>
    </div>
  );
}
