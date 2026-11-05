package com.arthadhruva.riskengine.score;

import com.arthadhruva.riskengine.survival.DelinquencyHistory;

import java.util.List;

/**
 * A loan of a portfolio as a run sees it: its origination features and, when the organization supplied them, where it
 * stands now. A loan with neither field is projected exactly as before they existed.
 *
 * @param daysPastDue current delinquency; null when not supplied
 * @param history     recent payment history; null when not supplied
 */
public record PortfolioLoan(LoanFeatures loan, Integer daysPastDue, DelinquencyHistory history) {

    public static PortfolioLoan of(LoanFeatures loan) {
        return new PortfolioLoan(loan, null, null);
    }

    public static PortfolioLoan of(LoanFeatures loan, LoanStatus status) {
        return status == null ? of(loan) : new PortfolioLoan(loan, status.daysPastDue(), status.history());
    }

    /** Loans with no status, for callers that have only origination data. */
    public static List<PortfolioLoan> all(List<LoanFeatures> loans) {
        return loans.stream().map(PortfolioLoan::of).toList();
    }

    public String loanId() {
        return loan.loanId();
    }
}
