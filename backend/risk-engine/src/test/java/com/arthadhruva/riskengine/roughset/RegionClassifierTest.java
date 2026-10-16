package com.arthadhruva.riskengine.roughset;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegionClassifierTest {

    private static double[] filled(int n, int high, double highValue, double lowValue) {
        double[] values = new double[n];
        for (int i = 0; i < n; i++) {
            values[i] = i < high ? highValue : lowValue;
        }
        return values;
    }

    @Test
    void everyMemberHighIsCertainHigh() {
        for (double beta : new double[]{0.0, 0.05, 0.49}) {
            assertEquals(Region.CERTAIN_HIGH, RegionClassifier.classify(filled(8, 8, 0.3, 0.1), 0.2, beta).region());
        }
    }

    @Test
    void noMemberHighIsCertainLow() {
        for (double beta : new double[]{0.0, 0.05, 0.49}) {
            assertEquals(Region.CERTAIN_LOW, RegionClassifier.classify(filled(8, 0, 0.3, 0.1), 0.2, beta).region());
        }
    }

    @Test
    void mixedMembersUnderStrictToleranceAreBoundary() {
        RegionClassifier.Classification c = RegionClassifier.classify(filled(8, 1, 0.3, 0.1), 0.2, 0.0);
        assertEquals(Region.BOUNDARY, c.region());
        assertEquals(1, c.high());
        assertEquals(8, c.members());
        assertEquals(0.2, c.width(), 1e-15);
    }

    @Test
    void aSingleOutlierIsAbsorbedByAVariablePrecisionTolerance() {
        double[] values = filled(32, 31, 0.3, 0.1);
        assertEquals(Region.BOUNDARY, RegionClassifier.classify(values, 0.2, 0.0).region());
        RegionClassifier.Classification c = RegionClassifier.classify(values, 0.2, 0.05);
        assertEquals(Region.CERTAIN_HIGH, c.region());
        assertEquals(31, c.high());
        assertEquals(32, c.members());
    }

    @Test
    void toleranceBoundaryIsExactDespiteFloatingPoint() {
        // 9 of 10 is exactly 1 - 0.1; 1 of 10 is exactly 0.1
        assertEquals(Region.CERTAIN_HIGH, RegionClassifier.classify(filled(10, 9, 0.3, 0.1), 0.2, 0.1).region());
        assertEquals(Region.CERTAIN_LOW, RegionClassifier.classify(filled(10, 1, 0.3, 0.1), 0.2, 0.1).region());
    }

    @Test
    void aMemberExactlyAtTheThresholdIsHigh() {
        assertEquals(Region.CERTAIN_HIGH, RegionClassifier.classify(new double[]{0.2, 0.2}, 0.2, 0.0).region());
    }

    @Test
    void spreadStatisticsAreConsistent() {
        RegionClassifier.Classification odd = RegionClassifier.classify(new double[]{0.3, 0.1, 0.2}, 0.25, 0.0);
        assertEquals(0.1, odd.min(), 0);
        assertEquals(0.2, odd.median(), 0);
        assertEquals(0.3, odd.max(), 0);
        RegionClassifier.Classification even = RegionClassifier.classify(new double[]{0.4, 0.1, 0.2, 0.3}, 0.25, 0.0);
        assertEquals(0.25, even.median(), 1e-15);
        assertEquals(even.max() - even.min(), even.width(), 0);
        assertTrue(even.min() <= even.median() && even.median() <= even.max() && even.width() >= 0);
    }

    @Test
    void aSingleMemberIsNeverBoundary() {
        assertEquals(Region.CERTAIN_HIGH, RegionClassifier.classify(new double[]{0.5}, 0.2, 0.0).region());
        assertEquals(Region.CERTAIN_LOW, RegionClassifier.classify(new double[]{0.1}, 0.2, 0.0).region());
    }

    /** Lower approximation inside the upper, disjoint decided sets, monotone in the threshold, wider tolerance keeps decisions. */
    @Test
    void roughSetInvariantsHoldOnRandomGranules() {
        Random random = new Random(20260110L);
        for (int trial = 0; trial < 2000; trial++) {
            int n = 1 + random.nextInt(40);
            double[] values = new double[n];
            for (int i = 0; i < n; i++) {
                values[i] = random.nextDouble();
            }
            double t1 = random.nextDouble();
            double t2 = Math.min(1.0, t1 + random.nextDouble() * (1.0 - t1));
            double beta1 = random.nextDouble() * 0.25;
            double beta2 = beta1 + random.nextDouble() * (0.49 - beta1);

            RegionClassifier.Classification strict = RegionClassifier.classify(values, t1, 0.0);
            // strict lower approximation: high in every member; outside the upper approximation: high in none
            assertEquals(strict.high() == n, strict.region() == Region.CERTAIN_HIGH);
            assertEquals(strict.high() == 0, strict.region() == Region.CERTAIN_LOW);
            if (strict.region() == Region.CERTAIN_HIGH) {
                assertTrue(strict.high() >= 1, "lower approximation must lie inside the upper approximation");
            }

            // raising the threshold never makes a loan more certainly high
            Region atT1 = strict.region();
            Region atT2 = RegionClassifier.classify(values, t2, 0.0).region();
            assertTrue(!(atT1 == Region.CERTAIN_LOW && atT2 != Region.CERTAIN_LOW), "low at t1 stays low at higher t2");
            assertTrue(!(atT2 == Region.CERTAIN_HIGH && atT1 != Region.CERTAIN_HIGH), "high at t2 was high at lower t1");

            // a larger tolerance never undoes a decision
            Region narrow = RegionClassifier.classify(values, t1, beta1).region();
            Region wide = RegionClassifier.classify(values, t1, beta2).region();
            if (narrow != Region.BOUNDARY) {
                assertEquals(narrow, wide);
            }
        }
    }
}
