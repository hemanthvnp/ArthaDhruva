package com.arthadhruva.riskengine.score;

/**
 * @param rawProbability         the LightGBM model's raw output -- NOT a trustworthy probability
 *                                (see default_risk_model.ipynb's calibration check: raw output
 *                                overpredicts by ~17.5x on average). Exposed for transparency, not
 *                                for dollar-valued use.
 * @param calibratedProbability  isotonic-corrected probability -- this is the number to actually
 *                                use for risk-based decisions, pricing, or Expected Loss.
 */
public record ScoreResponse(
        double rawProbability,
        double calibratedProbability
) {
}
