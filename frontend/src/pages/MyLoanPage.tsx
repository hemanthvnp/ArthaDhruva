import { useEffect, useState } from 'react';
import { myLoans } from '../api/client';
import ErrorBanner from '../components/ErrorBanner';
import type { MyLoanView } from '../api/types';

export default function MyLoanPage() {
  const [loans, setLoans] = useState<MyLoanView[]>([]);
  const [error, setError] = useState<unknown>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    (async () => {
      try {
        setLoans(await myLoans());
      } catch (e) {
        setError(e);
      } finally {
        setLoading(false);
      }
    })();
  }, []);

  return (
    <div>
      <h2>My Loan</h2>
      <p className="page-subtitle">Your loan's most recently computed risk score.</p>

      <ErrorBanner error={error} />
      {loading && <p className="page-subtitle">Loading...</p>}

      {!loading && loans.length === 0 && (
        <div className="card">
          <p className="page-subtitle">
            No loans are linked to your account yet. Contact your bank if you believe this is an error.
          </p>
        </div>
      )}

      {loans.map((loan) => (
        <div className="card" key={loan.loanId}>
          <h3>Loan {loan.loanId}</h3>
          {loan.calibratedProbability === null ? (
            <p className="page-subtitle">This loan hasn't been scored yet.</p>
          ) : (
            <>
              <div className="result-grid">
                <div className="stat">
                  <div className="label">Default risk</div>
                  <div className="value">{(loan.calibratedProbability * 100).toFixed(3)}%</div>
                </div>
                <div className="stat">
                  <div className="label">Last updated</div>
                  <div className="value" style={{ fontSize: '0.95rem' }}>
                    {loan.computedAt ? new Date(loan.computedAt).toLocaleString() : '-'}
                  </div>
                </div>
              </div>
            </>
          )}
        </div>
      ))}
    </div>
  );
}
