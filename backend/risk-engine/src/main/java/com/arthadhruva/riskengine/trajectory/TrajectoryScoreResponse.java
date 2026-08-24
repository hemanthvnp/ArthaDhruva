package com.arthadhruva.riskengine.trajectory;

/**
 * @param probability the model's raw sigmoid output -- useful for ranking/comparing trajectories
 *                     (a worsening trajectory scores meaningfully higher than a healthy one), but
 *                     NOT a calibrated probability. lstm_trajectory_model.ipynb trains with
 *                     pos_weight-weighted BCEWithLogitsLoss (to correct for class imbalance) and,
 *                     unlike the PD model (see ScoreResponse/IsotonicCalibrator), never fits a
 *                     calibration step afterward -- only ROC-AUC/PR-AUC (rank-based, calibration-
 *                     insensitive metrics) are validated in that notebook. Expect values run high
 *                     relative to true default likelihood; do not use directly for dollar-valued
 *                     decisions.
 */
public record TrajectoryScoreResponse(double probability) {
}
