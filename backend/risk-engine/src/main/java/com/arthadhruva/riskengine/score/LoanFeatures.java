package com.arthadhruva.riskengine.score;

import jakarta.validation.constraints.*;

import java.util.Locale;

/**
 * The 16 origination-time loan attributes the credit models use, plus an optional loan id and
 * origination month.
 *
 * <p>Bean Validation here only rejects structurally impossible input (missing fields, non-positive
 * amounts). The model's own domain -- valid category codes, plausible ranges, and a warning when a
 * value lies outside what the model was trained on -- is checked by {@link LoanInputValidator}.
 *
 * @param originationMonth {@code YYYY-MM} when the loan was originated. The note rate enters the models
 *                         as its spread over the market mortgage rate when the rate was locked; omit it for
 *                         a new application being priced at today's rates.
 */
public record LoanFeatures(
        @Size(max = 80) String loanId,
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
        @NotBlank @Size(max = 8) String occupancyStatus,
        @NotBlank @Size(max = 8) String propertyType,
        @NotBlank @Size(max = 8) String loanPurpose,
        @NotBlank @Size(max = 8) String channel,
        @NotBlank @Size(max = 8) String firstTimeHomebuyerFlag,
        @NotBlank @Size(max = 8) String propertyState,
        @Pattern(regexp = "\\d{4}-(0[1-9]|1[0-2])", message = "must be YYYY-MM") String originationMonth
) {

    /** Same loan with category codes trimmed and upper-cased ("ca " and "CA" are the same state). */
    public LoanFeatures normalized() {
        return new LoanFeatures(loanId == null ? null : loanId.trim(), creditScore, originalDti, originalUpb, originalCltv,
                originalLtv, originalInterestRate, originalLoanTerm, numberOfBorrowers, numberOfUnits, miPercent,
                code(occupancyStatus), code(propertyType), code(loanPurpose), code(channel),
                code(firstTimeHomebuyerFlag), code(propertyState), originationMonth);
    }

    private static String code(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }
}
