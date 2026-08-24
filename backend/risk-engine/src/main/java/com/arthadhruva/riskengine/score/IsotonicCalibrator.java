package com.arthadhruva.riskengine.score;

/**
 * Applies the isotonic regression fit in export_model.py (piecewise-linear interpolation over
 * the fitted breakpoints) to convert the LightGBM model's raw, badly-overpredicted output into a
 * calibrated probability -- see default_risk_model.ipynb's calibration check: raw output
 * overpredicts by ~17.5x on average; the isotonic fix reduces that to ~1.0x with no change to
 * ranking (AUC), since it's a monotonic transform.
 */
public final class IsotonicCalibrator {

    private final double[] xBreakpoints;
    private final double[] yBreakpoints;

    public IsotonicCalibrator(double[] xBreakpoints, double[] yBreakpoints) {
        if (xBreakpoints.length != yBreakpoints.length || xBreakpoints.length == 0) {
            throw new IllegalArgumentException("Breakpoint arrays must be non-empty and equal length");
        }
        this.xBreakpoints = xBreakpoints;
        this.yBreakpoints = yBreakpoints;
    }

    public double calibrate(double rawProbability) {
        int n = xBreakpoints.length;
        if (rawProbability <= xBreakpoints[0]) {
            return yBreakpoints[0];
        }
        if (rawProbability >= xBreakpoints[n - 1]) {
            return yBreakpoints[n - 1];
        }
        // Binary search for the surrounding breakpoint pair, then linearly interpolate.
        int lo = 0, hi = n - 1;
        while (hi - lo > 1) {
            int mid = (lo + hi) / 2;
            if (xBreakpoints[mid] <= rawProbability) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        double x0 = xBreakpoints[lo], x1 = xBreakpoints[hi];
        double y0 = yBreakpoints[lo], y1 = yBreakpoints[hi];
        if (x1 == x0) {
            return y0;
        }
        double t = (rawProbability - x0) / (x1 - x0);
        return y0 + t * (y1 - y0);
    }
}
