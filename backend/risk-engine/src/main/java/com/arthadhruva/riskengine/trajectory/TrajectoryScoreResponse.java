package com.arthadhruva.riskengine.trajectory;

/**
 * @param probability the probability that the loan ever reaches 90+ days past due after month 12. The model is
 *                     Platt-calibrated (folded into its final layer) on held-out loans from the fitting vintages;
 *                     on the 2022-2023 out-of-time vintages the average prediction was 0.97 times the actual
 *                     rate (lstm_card.json). Calibration is good on average, not in every decile, and "ever
 *                     defaulted" is right-censored for recent vintages, so use it to rank and to flag, not to
 *                     price or provision on.
 */
public record TrajectoryScoreResponse(double probability) {
}
