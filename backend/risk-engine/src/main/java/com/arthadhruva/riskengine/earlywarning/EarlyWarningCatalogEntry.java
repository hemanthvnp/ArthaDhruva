package com.arthadhruva.riskengine.earlywarning;

/**
 * One entry in the real-loan-snapshot catalog (backend/export_early_warning_catalog.py), sampled
 * from the natural-rate calibration holdout. {@code actuallyWentDelinquent} is the real,
 * later-observed outcome -- included purely so the frontend can show a prediction next to its
 * real answer; the model itself never sees this field.
 */
public record EarlyWarningCatalogEntry(String label, boolean actuallyWentDelinquent, EarlyWarningFeatures features) {
}
