import { useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { requestAccess } from '../api/client';
import type { AccessRequestSubmission } from '../api/types';
import ErrorBanner from '../components/ErrorBanner';
import BrandMark from '../components/BrandMark';

const FIELDS = [
  ['companyName', 'Institution name', 'text', true],
  ['contactName', 'Your name', 'text', true],
  ['workEmail', 'Work email', 'email', true],
  ['jobTitle', 'Role / title (optional)', 'text', false],
] as const;

/** Banks procure through security review and a pilot, so this records a request for the team to
 * follow up on; the organization is provisioned by a platform admin afterwards. */
export default function SignupPage() {
  const [form, setForm] = useState<AccessRequestSubmission>({
    companyName: '', contactName: '', workEmail: '', jobTitle: '', message: '',
  });
  const [error, setError] = useState<unknown>(null);
  const [done, setDone] = useState(false);
  const [loading, setLoading] = useState(false);

  const set = (k: keyof AccessRequestSubmission) =>
    (e: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement>) => setForm({ ...form, [k]: e.target.value });

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setLoading(true);
    setError(null);
    try {
      await requestAccess(form);
      setDone(true);
    } catch (err) {
      setError(err);
    } finally {
      setLoading(false);
    }
  };

  const missingRequired = FIELDS.some(([key, , , required]) => required && !form[key].trim());

  return (
    <div className="auth-page">
      <form onSubmit={submit} className="auth-card">
        <div className="brand"><BrandMark /> ArthaDhruva</div>
        <h2 style={{ marginBottom: '0.25rem' }}>Request access</h2>
        <p className="page-subtitle">
          Tell us about your institution. We'll arrange a security review and a pilot environment.
        </p>
        {done ? (
          <>
            <p>
              Thanks. Your request has been received, and our team will contact you at <strong>{form.workEmail}</strong>.
            </p>
            <Link to="/login">Back to sign in</Link>
          </>
        ) : (
          <>
            {FIELDS.map(([key, label, type]) => (
              <div className="field" style={{ marginBottom: '0.8rem' }} key={key}>
                <label htmlFor={key}>{label}</label>
                <input id={key} type={type} value={form[key]} onChange={set(key)} />
              </div>
            ))}
            <div className="field" style={{ marginBottom: '0.8rem' }}>
              <label htmlFor="message">What would you like to evaluate? (optional)</label>
              <textarea id="message" rows={3} maxLength={2000} value={form.message} onChange={set('message')} />
            </div>
            <ErrorBanner error={error} />
            <div className="actions">
              <button type="submit" disabled={loading || missingRequired} style={{ width: '100%' }}>
                {loading ? 'Sending...' : 'Request access'}
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
