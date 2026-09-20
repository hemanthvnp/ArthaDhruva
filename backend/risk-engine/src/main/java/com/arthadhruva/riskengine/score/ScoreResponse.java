package com.arthadhruva.riskengine.score;

import java.time.Instant;

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
        double calibratedProbability,
        java.util.List<Attribution> explanation
) {
    /** One feature's local contribution to this loan's calibrated probability: how much the score
     * moves (positive = raises risk) compared with that feature at its portfolio-typical value. */
    public record Attribution(String feature, double contribution) {
    }

    public ScoreResponse(double rawProbability, double calibratedProbability) {
        this(rawProbability, calibratedProbability, java.util.List.of());
    }

    public ScoreResponse withExplanation(java.util.List<Attribution> explanation) {
        return new ScoreResponse(rawProbability, calibratedProbability, explanation);
    }


    /** A {@link ScoreResponse} as read back from the cache, with the time it was computed. */
    public record CachedScore(ScoreResponse score, Instant computedAt) {
    }
}
