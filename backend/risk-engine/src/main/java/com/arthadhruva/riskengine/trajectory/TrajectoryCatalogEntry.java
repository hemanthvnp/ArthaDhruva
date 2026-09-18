package com.arthadhruva.riskengine.trajectory;

/**
 * One entry in the real-loan-trajectory catalog (backend/export_trajectory_catalog.py) -- a real
 * loan's actual first-up-to-12-months performance, ready to hand straight to
 * TrajectoryModelService.score() instead of an analyst hand-typing a month-by-month history.
 */
public record TrajectoryCatalogEntry(String label, TrajectoryRequest request) {
}
