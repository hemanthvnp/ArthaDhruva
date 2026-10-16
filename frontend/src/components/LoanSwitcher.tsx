import type { MyLoanView } from '../api/types';

/** Shown only to a borrower with more than one linked loan. */
export function LoanSwitcher({ list, loan, choose }: { list: MyLoanView[]; loan: MyLoanView; choose: (id: string) => void }) {
  if (list.length < 2) return null;
  return (
    <div className="field loan-switch">
      <label htmlFor="my-loan-pick">Loan</label>
      <select id="my-loan-pick" value={loan.loanId} onChange={(e) => choose(e.target.value)}>
        {list.map((l) => <option key={l.loanId} value={l.loanId}>{l.loanId}</option>)}
      </select>
    </div>
  );
}

export function NoLinkedLoans() {
  return (
    <div className="card">
      <p className="page-subtitle">
        No loans are linked to your account yet. Contact your lender if you believe this is an error.
      </p>
    </div>
  );
}
