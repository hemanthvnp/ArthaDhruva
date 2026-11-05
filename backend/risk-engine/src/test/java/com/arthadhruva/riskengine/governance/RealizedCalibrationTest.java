package com.arthadhruva.riskengine.governance;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Random;

import static com.arthadhruva.riskengine.governance.RealizedCalibration.Label.BEFORE_SCORE;
import static com.arthadhruva.riskengine.governance.RealizedCalibration.Label.DEFAULT;
import static com.arthadhruva.riskengine.governance.RealizedCalibration.Label.NO_DEFAULT;
import static com.arthadhruva.riskengine.governance.RealizedCalibration.Label.STILL_OPEN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RealizedCalibrationTest {

    private static final LocalDate SCORED = LocalDate.of(2023, 1, 15);       // horizon of 24 months ends 2025-01-15

    private static RealizedCalibration.Label label(String outcome, LocalDate on) {
        return RealizedCalibration.label(outcome, on, SCORED, 24);
    }

    @Test
    void aDefaultWithinTheHorizonIsADefaultAndOneAfterItIsNot() {
        assertEquals(DEFAULT, label("DEFAULTED", SCORED.plusDays(1)));
        assertEquals(DEFAULT, label("DEFAULTED", LocalDate.of(2025, 1, 15)), "the last day of the horizon is inside it");
        assertEquals(NO_DEFAULT, label("DEFAULTED", LocalDate.of(2025, 1, 16)), "a default after the horizon is not one within it");
    }

    @Test
    void aLoanThatLeftTheBookWithoutDefaultingIsNotADefault() {
        assertEquals(NO_DEFAULT, label("PREPAID", SCORED.plusMonths(3)));
        assertEquals(NO_DEFAULT, label("PREPAID", SCORED.plusMonths(40)));
    }

    @Test
    void aLoanSeenPerformingThroughTheHorizonIsNotADefaultAndOneSeenEarlierIsUnresolved() {
        assertEquals(NO_DEFAULT, label("ACTIVE", LocalDate.of(2025, 1, 15)));
        assertEquals(NO_DEFAULT, label("ACTIVE", LocalDate.of(2026, 6, 1)));
        assertEquals(STILL_OPEN, label("ACTIVE", LocalDate.of(2025, 1, 14)), "not yet observed through the horizon");
        assertEquals(STILL_OPEN, label("ACTIVE", SCORED.plusMonths(6)));
    }

    @Test
    void anOutcomeOnOrBeforeTheScoreCannotResolveIt() {
        assertEquals(BEFORE_SCORE, label("DEFAULTED", SCORED), "defaulted before it could be predicted");
        assertEquals(BEFORE_SCORE, label("DEFAULTED", SCORED.minusDays(40)));
        assertEquals(BEFORE_SCORE, label("PREPAID", SCORED.minusDays(1)));
    }

    @Test
    void anUnknownOutcomeIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> label("FORECLOSED", SCORED));
    }

    @Test
    void aPerfectRankingHasAucOneAndTheDecilesLineUp() {
        int n = 200;
        double[] p = new double[n];
        int[] y = new int[n];
        for (int i = 0; i < n; i++) {
            y[i] = i < 20 ? 1 : 0;
            p[i] = y[i] == 1 ? 0.5 : 0.02;
        }
        RealizedCalibration.Result r = RealizedCalibration.of(p, y);
        assertEquals(200, r.resolved());
        assertEquals(20, r.defaults());
        assertEquals(0.068, r.meanPredicted(), 1e-12);
        assertEquals(0.1, r.realizedRate(), 1e-12);
        assertEquals(0.68, r.predictedOverRealized(), 1e-12);
        assertEquals(1.0, r.auc(), 1e-12);
        assertEquals(10, r.deciles().size());
        for (RealizedCalibration.Decile d : r.deciles()) {
            assertEquals(20, d.loans());
        }
        assertEquals(0.0, r.deciles().get(0).realized(), 0);
        assertEquals(1.0, r.deciles().get(9).realized(), 0);
        assertEquals(0.5, r.deciles().get(9).predicted(), 1e-12);
        assertNull(r.note());
    }

    @Test
    void aScoreThatKnowsNothingHasAucAboutAHalf() {
        Random random = new Random(1);
        int n = 20_000;
        double[] p = new double[n];
        int[] y = new int[n];
        for (int i = 0; i < n; i++) {
            p[i] = random.nextDouble();
            y[i] = random.nextDouble() < 0.1 ? 1 : 0;
        }
        assertEquals(0.5, RealizedCalibration.of(p, y).auc(), 0.02);
    }

    @Test
    void tiedScoresCountHalfInTheAuc() {
        double[] p = {0.1, 0.1, 0.1, 0.1, 0.1, 0.1, 0.1, 0.1, 0.1, 0.1};
        int[] y = {1, 1, 1, 1, 1, 0, 0, 0, 0, 0};
        assertEquals(0.5, RealizedCalibration.of(p, y).auc(), 1e-12);
    }

    @Test
    void aSmallSampleSaysWhatItCannotSay() {
        RealizedCalibration.Result few = RealizedCalibration.of(new double[]{0.1, 0.2, 0.3}, new int[]{0, 0, 1});
        assertTrue(few.deciles().isEmpty());
        assertNull(few.auc());
        assertNotNull(few.note());
        assertTrue(few.note().contains("no AUC") && few.note().contains("no deciles"), few.note());
        RealizedCalibration.Result none = RealizedCalibration.of(new double[0], new int[0]);
        assertEquals(0, none.resolved());
        assertNotNull(none.note());
    }

    @Test
    void noDefaultsMeansNoRatio() {
        double[] p = new double[150];
        java.util.Arrays.fill(p, 0.01);
        RealizedCalibration.Result r = RealizedCalibration.of(p, new int[150]);
        assertNull(r.predictedOverRealized());
        assertEquals(0.0, r.realizedRate(), 0);
        assertEquals(10, r.deciles().size());
    }
}
