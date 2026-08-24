package com.arthadhruva.riskengine.score;

import jakarta.validation.constraints.*;

/**
 * The 16 origination-time features the PD model was trained on
 * (matches ALL_FEATURES in default_risk_model.ipynb / export_model.py).
 */
public record LoanFeatures(
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
