import { useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { signup } from '../api/client';
import ErrorBanner from '../components/ErrorBanner';

export default function SignupPage() {
  const [form, setForm] = useState({ organizationName: '', slug: '', adminUsername: '', email: '', password: '' });
  const [error, setError] = useState<unknown>(null);
  const [done, setDone] = useState<{ organization: string; trialDays: number } | null>(null);
  const [loading, setLoading] = useState(false);

  const set = (k: keyof typeof form) => (e: React.ChangeEvent<HTMLInputElement>) => setForm({ ...form, [k]: e.target.value });

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setLoading(true);
    setError(null);
    try {
      setDone(await signup({ ...form, slug: form.slug.toLowerCase() }));
    } catch (err) {
      setError(err);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="auth-page">
      <form onSubmit={submit} className="auth-card">
        <h2 style={{ marginBottom: '0.25rem' }}>Start your free trial</h2>
        <p className="page-subtitle">Create an organization and its first admin account.</p>
        {done ? (
          <>
            <p>
              Organization <strong>{done.organization}</strong> created with a {done.trialDays}-day trial. Sign in with its
              slug; you will be asked to set up two-factor authentication.
            </p>
            <Link to="/login">Go to sign in</Link>
          </>
        ) : (
          <>
            {(
              [
                ['organizationName', 'Organization name', 'text'],
                ['slug', 'Organization slug (lowercase, used to sign in)', 'text'],
                ['adminUsername', 'Admin username', 'text'],
                ['email', 'Email', 'email'],
                ['password', 'Password (10+ characters, letter and digit)', 'password'],
              ] as const
            ).map(([key, label, type]) => (
              <div className="field" style={{ marginBottom: '0.8rem' }} key={key}>
                <label htmlFor={key}>{label}</label>
                <input id={key} type={type} value={form[key]} onChange={set(key)} />
              </div>
            ))}
            <ErrorBanner error={error} />
            <div className="actions">
              <button type="submit" disabled={loading || Object.values(form).some((v) => !v)} style={{ width: '100%' }}>
                {loading ? 'Creating...' : 'Create organization'}
              </button>
            </div>
            <p style={{ fontSize: '0.8rem', marginTop: '1rem', textAlign: 'center' }}>
              <Link to="/login">Already have an account?</Link>
            </p>
          </>
        )}
      </form>
    </div>
  );
}
