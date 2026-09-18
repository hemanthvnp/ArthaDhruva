package com.arthadhruva.riskengine.earlywarning;

/**
 * @param rawRisk         the LightGBM model's raw output, trained on a 10:1 downsampled target
 *                          rate -- systematically overpredicts real-world risk (see
 *                          early_warning_delinquency.ipynb's calibration check), so not a
 *                          trustworthy probability on its own. Kept at full resolution though:
 *                          when ranking multiple loans (the model's actual analyst-facing use
 *                          case), sort by this field, not {@code calibratedRisk} -- isotonic
 *                          calibration is a monotonic step function that coarsens resolution at
 *                          the extreme tail, so distinct loans the model still ranks differently
 *                          can land on the same calibrated value.
 * @param calibratedRisk  isotonic-corrected probability, meaningful against the true population
 *                          rate -- this is the number to actually display to an analyst as "risk
 *                          of going 30+ days late in the next 3 months."
 */
public record EarlyWarningResponse(
        double rawRisk,
        double calibratedRisk
) {
}
