import BorrowerDocuments from '../components/BorrowerDocuments';
import ContactCard from '../components/ContactCard';
import ErrorBanner from '../components/ErrorBanner';
import Icon from '../components/Icon';
import { LoanSwitcher, NoLinkedLoans } from '../components/LoanSwitcher';
import { bandColor, bandPosition } from '../components/borrower';
import { useMyLoans } from '../components/useMyLoans';
import { dateOnly, dateTime } from '../format';

/** Generic on purpose: the categories that shape mortgage risk, never this borrower's own weights. */
const FACTORS: [string, string][] = [
  ['Payment history', 'paying on time, every time'],
  ['Credit utilization', "how much of your available credit you're using"],
  ['Loan-to-value ratio', "your loan balance compared with your home's value"],
  ['Length of credit history', 'how long your credit accounts have been open'],
];

export default function MyLoanPage() {
  const { loans, list, loan, choose } = useMyLoans();

  return (
    <div>
      <div className="page-head-row">
        <div>
          <h2>My loan</h2>
          <p className="page-subtitle">{loan ? `Loan ${loan.loanId}` : 'Your loan at a glance.'}</p>
        </div>
        {loan && <LoanSwitcher list={list} loan={loan} choose={choose} />}
      </div>

      <ErrorBanner error={loans.error} />
      {loans.isLoading && <p className="page-subtitle">Loading...</p>}
      {!loans.isLoading && !loans.error && list.length === 0 && <NoLinkedLoans />}

      {loan && (
        <>
          <div className="card">
            {loan.status ? (
              <div className="status-hero">
                <div>
                  <div className="section-label">Status</div>
                  <div className="status-word" style={{ color: bandColor(loan.status.band) }}>{loan.status.label}</div>
                  <div className="status-meta">Checked {dateTime(loan.status.checkedAt)}</div>
                </div>
                <div className="status-meter-col">
                  <div className="meter" role="img" aria-label={`Status: ${loan.status.label}`}>
                    <span className="pin" style={{ left: `${bandPosition(loan.status.band)}%` }} />
                  </div>
                  <div className="meter-scale" aria-hidden="true"><span>Low</span><span>Medium</span><span>High</span></div>
                  <div className="trend-line"><Icon name="trajectory" size={15} /> {loan.status.trend}</div>
                </div>
              </div>
            ) : (
              <>
                <div className="section-label">Status</div>
                <p className="page-subtitle">Your loan hasn't been checked yet. Its status will appear here after the first check.</p>
              </>
            )}
          </div>

          <div className="widget-grid">
            <div className="card">
              <div className="card-head">
                <h3>Review status</h3>
                {loan.openCase
                  ? <span className="badge badge-accent">{loan.openCase.status}</span>
                  : <span className="badge badge-low">No open items</span>}
              </div>
              {loan.openCase ? (
                <>
                  <p className="page-subtitle">{loan.openCase.description}</p>
                  {loan.openCase.openedAt && (
                    <dl className="facts">
                      <div><dt>Opened</dt><dd>{dateOnly(loan.openCase.openedAt)}</dd></div>
                    </dl>
                  )}
                </>
              ) : (
                <p className="page-subtitle">There's nothing pending on your loan.</p>
              )}
            </div>
            <div className="card">
              <div className="card-head"><h3>Your loan officer</h3></div>
              <ContactCard contact={loan.contact} />
            </div>
          </div>

          <div className="card">
            <div className="card-head"><h3>Documents</h3></div>
            <BorrowerDocuments loanId={loan.loanId} />
          </div>

          <div className="card">
            <div className="card-head"><h3>What affects your risk status</h3></div>
            <p className="page-subtitle">
              These are the general factors that shape mortgage risk. We don't publish the exact weighting behind your own status.
            </p>
            <ul className="prose">
              {FACTORS.map(([name, what]) => <li key={name}><strong>{name}</strong> &mdash; {what}</li>)}
            </ul>
          </div>
        </>
      )}
    </div>
  );
}
