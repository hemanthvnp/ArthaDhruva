package com.arthadhruva.riskengine.earlywarning;

import jakarta.validation.constraints.*;

/**
 * A single currently-current-loan monthly snapshot: origination-time features plus the loan-age,
 * equity/paydown/rate-lock trend features, prior-history flags, and the HMM regime label the
 * model was trained on (matches ALL_FEATURES in notebooks/early_warning_delinquency.ipynb v3 /
 * export_early_warning_model.py).
 *
 * Unlike {@link com.arthadhruva.riskengine.score.LoanFeatures}, this endpoint does not compute
 * the trend features from raw monthly history itself -- eltv, upb_paydown_ratio,
 * rate_lock_severity and their 3-/6-month changes are the caller's responsibility to derive from
 * the loan's actual performance history (the same snapshot-generation job that built the
 * training/calibration parquet files would do this in practice). This mirrors the same kind of
 * stated simplification as EAD in ExpectedLossController: the model's real feature engineering
 * lives in the batch/notebook pipeline, not reimplemented a second time here.
 */
public record EarlyWarningFeatures(
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
        @NotNull @PositiveOrZero Integer loanAge,
        @NotNull @Positive Double eltv,
        @NotNull @Positive Double currentInterestRate,
        @NotNull Double upbPaydownRatio,
        @NotNull Double rateLockSeverity,
        @NotNull Double eltvChange3m,
        @NotNull Double upbPaydownChange3m,
        @NotNull Double rateLockSeverityChange3m,
        @NotNull Double eltvChange6m,
        @NotNull Double upbPaydownChange6m,
        @NotNull Double rateLockSeverityChange6m,
        @NotBlank String occupancyStatus,
        @NotBlank String propertyType,
        @NotBlank String loanPurpose,
        @NotBlank String channel,
        @NotBlank String firstTimeHomebuyerFlag,
        @NotBlank String propertyState,
        @NotBlank String hmmRegime,
        boolean priorAssistance,
        boolean priorModification,
        boolean priorDisaster,
        boolean upbStalled
) {
}
