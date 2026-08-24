package com.arthadhruva.riskengine.cvar;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * One loan's risk inputs to the {@link CvarEngine} simulation: probability of default, loss
 * given default (both as fractions in [0, 1]), and exposure at default (a currency amount).
 * {@code loanId} is optional and carried through only for traceability in the audit log.
 */
public record LoanRiskProfile(
        String loanId,
        @NotNull @DecimalMin("0") @DecimalMax("1") Double pd,
        @NotNull @DecimalMin("0") @DecimalMax("1") Double lgd,
        @NotNull @Positive Double ead
) {
}
