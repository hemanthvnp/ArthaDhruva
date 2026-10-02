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
 * Request for {@code POST /cvar}: a portfolio of loans plus simulation parameters. Every parameter is
 * optional; the compact constructor fills in the defaults before Bean Validation checks the ranges.
 *
 * @param confidenceLevel    the quantile, e.g. 0.999 for a one-in-a-thousand loss (default 0.99)
 * @param numScenarios       Monte Carlo scenarios (default 20,000)
 * @param assetCorrelation   the share of each loan's fate driven by the common factor (default 0.15, the
 *                           Basel IRB correlation for residential mortgages; 0 makes defaults independent)
 * @param seed               fixes the random streams so a result can be reproduced; the response reports
 *                           the seed it used
 * @param importanceSampling tilt the common factor toward the tail (default true)
 * @param contributions      also attribute expected shortfall to individual loans (default true)
 */
public record CvarRequest(
        @NotEmpty @Size(max = 10_000) List<@Valid LoanRiskProfile> loans,
        @DecimalMin("0.5") @DecimalMax("0.9999") Double confidenceLevel,
        @Min(1_000) @Max(200_000) Integer numScenarios,
        @DecimalMin("0.0") @DecimalMax("0.6") Double assetCorrelation,
        Long seed,
        Boolean importanceSampling,
        Boolean contributions
) {
    public static final double DEFAULT_ASSET_CORRELATION = 0.15;

    public CvarRequest {
        if (confidenceLevel == null) {
            confidenceLevel = 0.99;
        }
        if (numScenarios == null) {
            numScenarios = 20_000;
        }
        if (assetCorrelation == null) {
            assetCorrelation = DEFAULT_ASSET_CORRELATION;
        }
        if (importanceSampling == null) {
            importanceSampling = true;
        }
        if (contributions == null) {
            contributions = true;
        }
    }
}
