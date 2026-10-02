package com.arthadhruva.riskengine.ml;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Reference values from Python: 0.5 * math.erfc(-x / sqrt(2)) and statistics.NormalDist().inv_cdf(p). */
class NormalTest {

    private static final double[][] CDF = {
            {-8.0, 6.220960574271819e-16},
            {-6.0, 9.865876450377016e-10},
            {-4.5, 3.397673124730062e-06},
            {-3.0, 0.0013498980316300959},
            {-1.96, 0.024997895148220435},
            {-1.0, 0.15865525393145707},
            {-0.1, 0.460172162722971},
            {0.0, 0.5},
            {0.3, 0.6179114221889526},
            {1.0, 0.8413447460685428},
            {2.5, 0.9937903346742238},
            {5.0, 0.9999997133484282},
            {7.5, 0.999999999999968},
    };

    private static final double[][] INVERSE = {
            {1e-12, -7.034483825301132},
            {1e-09, -5.9978070150076865},
            {1e-06, -4.753424308822899},
            {0.0001, -3.71901648545568},
            {0.001, -3.090232306167813},
            {0.01, -2.3263478740408408},
            {0.02425, -1.9729610513118845},
            {0.1, -1.2815515655446008},
            {0.3, -0.5244005127080407},
            {0.5, 0.0},
            {0.7, 0.5244005127080407},
            {0.975, 1.9599639845400536},
            {0.999, 3.090232306167813},
            {0.9999, 3.7190164854557084},
            {0.999999999, 5.997807019601638},
    };

    @Test
    void cdfIsAccurateInBothTails() {
        for (double[] row : CDF) {
            assertEquals(row[1], Normal.cdf(row[0]), Math.abs(row[1]) * 1e-12, "cdf(" + row[0] + ")");
        }
        assertEquals(0.0, Normal.cdf(-40), 0.0);
        assertEquals(1.0, Normal.cdf(40), 0.0);
        assertEquals(1.0, Normal.cdf(1.3) + Normal.cdf(-1.3), 1e-15);
    }

    @Test
    void inverseIsAccurateInBothTails() {
        for (double[] row : INVERSE) {
            assertEquals(row[1], Normal.inverseCdf(row[0]), 1e-11, "inverseCdf(" + row[0] + ")");
        }
        assertEquals(Double.NEGATIVE_INFINITY, Normal.inverseCdf(0), 0.0);
        assertEquals(Double.POSITIVE_INFINITY, Normal.inverseCdf(1), 0.0);
        assertTrue(Double.isNaN(Normal.inverseCdf(Double.NaN)));
    }

    @Test
    void inverseUndoesTheCdf() {
        for (double x = -7.5; x <= 7.5; x += 0.137) {
            // Above zero the cdf is 1 minus something tiny, so its own last digit limits what can be recovered.
            double tolerance = 1e-11 + (x > 0 ? 4e-16 / Normal.pdf(x) : 0);
            assertEquals(x, Normal.inverseCdf(Normal.cdf(x)), tolerance, "x = " + x);
        }
        for (double p = 0.0005; p < 1; p += 0.0131) {
            assertEquals(p, Normal.cdf(Normal.inverseCdf(p)), 1e-14, "p = " + p);
        }
    }

    @Test
    void densityIntegratesToTheCdf() {
        double sum = 0, h = 1e-3;
        for (double x = -8; x < 1.0; x += h) {
            sum += h * (Normal.pdf(x) + Normal.pdf(x + h)) / 2;
        }
        assertEquals(Normal.cdf(1.0), sum, 1e-6);
    }
}
