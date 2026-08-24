package com.arthadhruva.riskengine.score;

import jakarta.validation.constraints.*;

/**
 * The 16 origination-time features the PD model was trained on
 * (matches ALL_FEATURES in default_risk_model.ipynb / export_model.py), plus an optional
 * caller-supplied loan identifier. When {@code loanId} is present, {@link ScoreController}
 * writes the score to the Redis-backed cache under it so it can be read back via
 * {@code GET /score/{loanId}} without recomputing; when absent, scoring is unchanged and
 * stateless as before.
 */
public record LoanFeatures(
        String loanId,
        @NotNull @Min(300) @Max(850) Integer creditScore,
        @NotNull @Positive Double originalDti,
        @NotNull @Positive Double originalUpb,
        @NotNull @Positive Double originalCltv,
        @NotNull @Positive Double originalLtv,
        @NotNull @Positive Double originalInterestRate,
        @NotNull @Positive Integer originalLoanTerm,
        @NotNull @Positive Integer numberOfBorrowers,
        @NotNull @Positive Integer numberOfUnits,
        @NotNull @PositiveOrZero Double miPercent,
        @NotBlank String occupancyStatus,
        @NotBlank String propertyType,
        @NotBlank String loanPurpose,
        @NotBlank String channel,
        @NotBlank String firstTimeHomebuyerFlag,
        @NotBlank String propertyState
) {
}
