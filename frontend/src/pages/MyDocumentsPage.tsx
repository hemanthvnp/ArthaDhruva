import BorrowerDocuments from '../components/BorrowerDocuments';
import ErrorBanner from '../components/ErrorBanner';
import { LoanSwitcher, NoLinkedLoans } from '../components/LoanSwitcher';
import { useMyLoans } from '../components/useMyLoans';

export default function MyDocumentsPage() {
  const { loans, list, loan, choose } = useMyLoans();
  return (
    <div>
      <div className="page-head-row">
        <div>
          <h2>Documents</h2>
          <p className="page-subtitle">Files on your loan, from you and from your lender.</p>
        </div>
        {loan && <LoanSwitcher list={list} loan={loan} choose={choose} />}
      </div>
      <ErrorBanner error={loans.error} />
      {loans.isLoading && <p className="page-subtitle">Loading...</p>}
      {!loans.isLoading && !loans.error && list.length === 0 && <NoLinkedLoans />}
      {loan && (
        <div className="card">
          <BorrowerDocuments loanId={loan.loanId} />
        </div>
      )}
    </div>
  );
}
