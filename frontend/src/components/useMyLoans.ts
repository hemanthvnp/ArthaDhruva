import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { myLoans } from '../api/client';

/** The borrower's own loans and the one being shown: the one chosen, else the first. */
export function useMyLoans() {
  const loans = useQuery({ queryKey: ['my-loans'], queryFn: myLoans });
  const [chosen, setChosen] = useState<string | null>(null);
  const list = loans.data ?? [];
  const loan = list.find((l) => l.loanId === chosen) ?? list[0];
  return { loans, list, loan, choose: setChosen };
}
