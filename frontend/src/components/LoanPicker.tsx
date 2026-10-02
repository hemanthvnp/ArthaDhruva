import { useId, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { getLoan, listLoanCatalog, searchLoans } from '../api/client';
import { useDebounced } from '../hooks/useDebounced';
import type { LoanFeatures } from '../api/types';

/**
 * Picks one loan of the portfolio by id. The suggestions are the first page of the portfolio until the
 * user types; from then on they come from a server-side search (debounced), so the picker works the
 * same for four hundred loans and for four hundred thousand without loading the portfolio whole.
 */
export default function LoanPicker({ initialId = '', busy = false, action, onPick }: {
  initialId?: string;
  busy?: boolean;
  action: string;
  onPick: (loan: LoanFeatures) => void;
}) {
  const listId = useId();
  const [id, setId] = useState(initialId);
  const [missing, setMissing] = useState(false);
  const [looking, setLooking] = useState(false);
  const term = useDebounced(id.trim(), 250);

  const suggestions = useQuery({
    queryKey: ['loan-suggestions', term],
    queryFn: () => (term.length >= 2 ? searchLoans(term, 20) : listLoanCatalog(50)),
    staleTime: 60_000,
  });

  const pick = async () => {
    const wanted = id.trim();
    if (!wanted) return;
    setMissing(false);
    setLooking(true);
    try {
      const loan = suggestions.data?.find((l) => l.loanId === wanted) ?? (await getLoan(wanted));
      if (loan) onPick(loan);
      else setMissing(true);
    } finally {
      setLooking(false);
    }
  };

  return (
    <>
      <div className="row-inline">
        <div className="field" style={{ minWidth: 260 }}>
          <label htmlFor={`${listId}-input`}>Loan ID</label>
          <input
            id={`${listId}-input`}
            list={listId}
            value={id}
            onChange={(e) => {
              setId(e.target.value);
              setMissing(false);
            }}
            onKeyDown={(e) => e.key === 'Enter' && pick()}
            placeholder="Start typing a loan ID"
            autoComplete="off"
          />
          <datalist id={listId}>
            {(suggestions.data ?? []).map((l) => (
              <option key={l.loanId} value={l.loanId}>
                {`credit ${l.creditScore}, LTV ${l.originalLtv}, ${l.propertyState}${l.originationMonth ? `, ${l.originationMonth}` : ''}`}
              </option>
            ))}
          </datalist>
        </div>
        <button onClick={pick} disabled={busy || looking || !id.trim()}>
          {busy || looking ? 'Working...' : action}
        </button>
      </div>
      {missing && <p className="error-banner">No loan with this ID in the portfolio.</p>}
    </>
  );
}
