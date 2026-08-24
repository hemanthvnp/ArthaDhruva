package com.arthadhruva.riskengine.cvar;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Request for {@code POST /cvar}: a portfolio of loans plus simulation parameters. Both
 * {@code confidenceLevel} and {@code numScenarios} are optional -- the compact constructor
 * fills in sensible defaults (95% / 50,000 scenarios) when either is omitted, before Bean
 * Validation checks the resulting (always non-null) values are in range.
 */
public record CvarRequest(
        @NotEmpty @Size(max = 10_000) List<@Valid LoanRiskProfile> loans,
        @DecimalMin("0.5") @DecimalMax("0.999") Double confidenceLevel,
        @Min(1_000) @Max(200_000) Integer numScenarios
) {
    public CvarRequest {
        if (confidenceLevel == null) {
            confidenceLevel = 0.95;
        }
        if (numScenarios == null) {
            numScenarios = 50_000;
        }
    }
}
