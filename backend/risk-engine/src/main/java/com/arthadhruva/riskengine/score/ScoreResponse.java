package com.arthadhruva.riskengine.score;

import java.time.Instant;
import java.util.List;

/**
 * A PD score with everything needed to use and defend it.
 *
 * @param rawProbability        the model's uncalibrated output -- a ranking score, not a probability
 * @param calibratedProbability probability of default within {@code horizonMonths} of origination; the
 *                              number to use for decisions, pricing and expected loss
 * @param explanation           Shapley attribution of the calibrated PD to each feature, relative to a typical
 *                              loan of the training population (sums to PD minus {@code baselineProbability})
 * @param reasonCodes           up to four principal factors that RAISE this loan's risk, in the form of
 *                              adverse-action reasons
 * @param warnings              inputs outside the training distribution (the score is an extrapolation)
 * @param modelVersion          the exact model that produced this score
 * @param baselineProbability   the PD of the reference loan the explanation is measured from; null when the
 *                              score is read back without its explanation
 */
public record ScoreResponse(
        double rawProbability,
        double calibratedProbability,
        List<Attribution> explanation,
        List<ReasonCode> reasonCodes,
        List<String> warnings,
        String modelVersion,
        int horizonMonths,
        Double baselineProbability
) {
    /** One feature's Shapley contribution to the calibrated PD (positive = raises risk). */
    public record Attribution(String feature, double contribution) {
    }

    /** A principal reason the model rates this loan riskier than a typical one. */
    public record ReasonCode(String code, String feature, String description, double contribution) {
    }

    public ScoreResponse(double rawProbability, double calibratedProbability) {
        this(rawProbability, calibratedProbability, List.of(), List.of(), List.of(), null, 0, null);
    }

    public ScoreResponse with(ExplanationService.Explanation explanation, List<String> warnings, String modelVersion,
                              int horizonMonths) {
        return new ScoreResponse(rawProbability, calibratedProbability, explanation.contributions(),
                explanation.reasonCodes(), warnings, modelVersion, horizonMonths, explanation.baselineProbability());
    }

    /** A {@link ScoreResponse} as read back from the cache or the durable record, with when it was computed. */
    public record CachedScore(ScoreResponse score, Instant computedAt) {
    }
}
