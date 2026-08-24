package com.arthadhruva.riskengine.cvar;

/**
 * @param valueAtRisk                        the loss threshold at {@code confidenceLevel} (e.g.
 *                                             the 95th percentile of simulated portfolio loss)
 * @param conditionalValueAtRisk              expected shortfall: mean loss among scenarios at or
 *                                             beyond {@code valueAtRisk} -- always >= valueAtRisk
 * @param valueAtRiskConfidenceInterval       [lo, hi] 95% bootstrap CI on valueAtRisk, reflecting
 *                                             Monte Carlo sampling uncertainty, not a point score
 * @param conditionalValueAtRiskConfidenceInterval [lo, hi] 95% bootstrap CI on conditionalValueAtRisk
 */
public record CvarResult(
        double valueAtRisk,
        double conditionalValueAtRisk,
        double[] valueAtRiskConfidenceInterval,
        double[] conditionalValueAtRiskConfidenceInterval,
        double meanLoss,
        int numLoans,
        int numScenarios,
        double confidenceLevel
) {
}
