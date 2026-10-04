package com.arthadhruva.riskengine.ml;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The expected values are numpy.interp's, which is what the training scripts use to apply the calibration. */
class IsotonicCalibratorTest {

    private final IsotonicCalibrator calibrator = new IsotonicCalibrator(
            new double[]{0.1, 0.2, 0.4}, new double[]{0.05, 0.10, 0.50});

    @Test
    void clampsOutsideTheBreakpoints() {
        assertEquals(0.05, calibrator.calibrate(0.0), 0.0);
        assertEquals(0.05, calibrator.calibrate(0.1), 0.0);
        assertEquals(0.50, calibrator.calibrate(0.4), 0.0);
        assertEquals(0.50, calibrator.calibrate(0.99), 0.0);
    }

    @Test
    void hitsBreakpointsExactlyAndInterpolatesBetweenThem() {
        assertEquals(0.10, calibrator.calibrate(0.2), 1e-15);
        assertEquals(0.075, calibrator.calibrate(0.15), 1e-15);
        assertEquals(0.30, calibrator.calibrate(0.3), 1e-15);
    }

    /** At a repeated x the curve is right-continuous (numpy.interp(0.5, [0,.5,.5,1], [0,.2,.4,1]) == 0.4). */
    @Test
    void duplicateBreakpointsResolveToTheLastOne() {
        IsotonicCalibrator stepped = new IsotonicCalibrator(new double[]{0.0, 0.5, 0.5, 1.0}, new double[]{0.0, 0.2, 0.4, 1.0});
        assertEquals(0.4, stepped.calibrate(0.5), 1e-15);
        assertEquals(0.1, stepped.calibrate(0.25), 1e-15);
        assertEquals(0.7, stepped.calibrate(0.75), 1e-15);
    }

    @Test
    void isMonotoneOverAFineGrid() {
        double previous = -1;
        for (double raw = 0.0; raw <= 0.5; raw += 0.0007) {
            double calibrated = calibrator.calibrate(raw);
            assertTrue(calibrated >= previous, "raw = " + raw);
            previous = calibrated;
        }
    }

    @Test
    void rejectsMalformedBreakpoints() {
        assertThrows(IllegalArgumentException.class, () -> new IsotonicCalibrator(new double[0], new double[0]));
        assertThrows(IllegalArgumentException.class, () -> new IsotonicCalibrator(new double[]{0.1, 0.2}, new double[]{0.1}));
    }
}
