package com.arthadhruva.riskengine.cvar;

import java.util.List;

/**
 * @param valueAtRisk                 the loss exceeded with probability {@code 1 - confidenceLevel}
 * @param conditionalValueAtRisk      expected shortfall: the mean loss in that tail; never below valueAtRisk
 * @param valueAtRiskConfidenceInterval [lo, hi] 95% interval for Monte Carlo error, from the spread of
 *                                    independent sections of the simulation
 * @param expectedLoss                sum of PD x LGD x EAD, exact (not simulated)
 * @param unexpectedLoss              valueAtRisk minus expectedLoss: the capital-like buffer above provisions
 * @param asrfValueAtRisk             the closed-form VaR of an infinitely fine-grained portfolio with the
 *                                    same loans (the Basel IRB formula)
 * @param granularityAddOn            valueAtRisk minus asrfValueAtRisk: what concentration in individual
 *                                    loans adds
 * @param effectiveLoans              the number of equal-sized loans with the same concentration
 *                                    (1 / Herfindahl index of exposures)
 * @param factorShift                 mean of the common factor under importance sampling (0 = not tilted)
 * @param systematicVarianceShare     share of loss variance explained by the common factor
 * @param seed                        reruns with this seed reproduce the result exactly
 * @param topContributions            the loans that contribute most to expected shortfall
 * @param exceedanceCurve             P(loss > x) at increasing loss levels
 */
public record CvarResult(
        double valueAtRisk,
        double conditionalValueAtRisk,
        double[] valueAtRiskConfidenceInterval,
        double[] conditionalValueAtRiskConfidenceInterval,
        double expectedLoss,
        double unexpectedLoss,
        double asrfValueAtRisk,
        double granularityAddOn,
        double totalExposure,
        double effectiveLoans,
        int numLoans,
        int numScenarios,
        double confidenceLevel,
        double assetCorrelation,
        boolean importanceSampling,
        double factorShift,
        double systematicVarianceShare,
        long seed,
        List<Contribution> topContributions,
        List<ExceedancePoint> exceedanceCurve,
        long elapsedMillis
) {
    /**
     * @param index         position of the loan in the request
     * @param contribution  the loan's part of expected shortfall (contributions over all loans sum to it)
     * @param share         contribution as a fraction of expected shortfall
     * @param expectedLoss  the loan's own PD x LGD x EAD, for comparison
     */
    public record Contribution(String loanId, int index, double contribution, double share, double expectedLoss) {
    }

    public record ExceedancePoint(double loss, double probability) {
    }
}
