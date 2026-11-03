package com.arthadhruva.riskengine.survival;

import com.arthadhruva.riskengine.score.LoanFeatures;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.arthadhruva.riskengine.survival.DelinquencyFixtures.RECENT;
import static com.arthadhruva.riskengine.survival.SurvivalFixtures.ENGINE;
import static com.arthadhruva.riskengine.survival.SurvivalFixtures.MODEL;
import static com.arthadhruva.riskengine.survival.SurvivalFixtures.loan;
import static com.arthadhruva.riskengine.survival.SurvivalFixtures.prime;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntervalServiceTest {

    private static final DelinquencyHistory HISTORY_30 = new DelinquencyHistory(12, 2, 3, "D30");

    private static RiskProjectionService service(IntervalService intervals) {
        return new RiskProjectionService(ENGINE, DelinquencyFixtures.ENGINE, DelinquencyFixtures.ROUTER, intervals, MODEL);
    }

    private static IntervalService intervals(int defaultDraws, int maxDraws, long budgetMillis) {
        return new IntervalService(MODEL, DelinquencyFixtures.MODEL, defaultDraws, maxDraws, budgetMillis);
    }

    private static RiskProjectionService.Result project(RiskProjectionService s, LoanFeatures loan, Scenario scenario, Integer dpd,
                                                        DelinquencyHistory history, Integer draws) {
        return s.project(new TermStructureEngine.Request(loan, scenario, null, null, dpd, null), 12, history,
                new RiskProjectionService.IntervalOptions(true, draws));
    }

    private static void assertValid(IntervalService.Interval i, boolean probability, String what) {
        assertTrue(i.lower() <= i.point() && i.point() <= i.upper(), what + ": " + i);
        assertTrue(i.lower() >= 0 && i.upper() >= i.lower(), what + ": " + i);
        if (probability) {
            assertTrue(i.upper() <= 1.0, what + ": " + i);
        }
    }

    @Test
    void everyIntervalContainsThePointEstimateAndStaysInRange() {
        RiskProjectionService s = service(intervals(60, 100, 60_000));
        for (Scenario scenario : List.of(Scenario.BASELINE, Scenario.SEVERELY_ADVERSE)) {
            for (RiskProjectionService.Result r : List.of(
                    project(s, prime(), scenario, 35, HISTORY_30, null),
                    project(s, prime(), scenario, null, null, null),
                    project(s, loan("weak", 640, 95.0, 7.25, 360, 30.0, "FL", null), scenario, 65, new DelinquencyHistory(12, 1, null, "D60"), null))) {
                IntervalService.Intervals i = r.intervals();
                assertNotNull(i.pdLifetime(), r.modelUsed());
                assertValid(i.pd12m(), true, "pd12m");
                assertValid(i.pdLifetime(), true, "pdLifetime");
                assertValid(i.ecl12m(), false, "ecl12m");
                assertValid(i.eclLifetime(), false, "eclLifetime");
                assertEquals(r.termStructure().summary().pdLifetime(), i.pdLifetime().point(), 0);
                assertEquals(r.termStructure().ecl().eclLifetime(), i.eclLifetime().point(), 0);
            }
        }
    }

    @Test
    void anIntervalIsLabelledAndRecordsItsProvenance() {
        IntervalService.Intervals i = project(service(intervals(40, 100, 60_000)), prime(), Scenario.BASELINE, 35, HISTORY_30, 40).intervals();
        assertEquals("regime uncertainty plus historical calibration error", i.method());
        assertEquals(40, i.draws());
        assertTrue(i.resampled().contains("regime chain only"), i.resampled());
        assertTrue(i.bandVintages() >= 4 && i.bandSigma() > 0 && i.bandHorizon() > 0, i.toString());
        assertTrue(i.bandSource().contains("survival_backtest.json"));
        assertNull(i.reason());
    }

    @Test
    void theSameRequestGivesBitIdenticalBoundsOnAnyThreadCount() throws Exception {
        RiskProjectionService s = service(intervals(80, 100, 60_000));
        IntervalService.Intervals first = project(s, prime(), Scenario.ADVERSE, 35, HISTORY_30, 80).intervals();
        assertEquals(first, project(s, prime(), Scenario.ADVERSE, 35, HISTORY_30, 80).intervals(), "repeat");
        // a different service instance stands for another replica
        assertEquals(first, project(service(intervals(80, 100, 60_000)), prime(), Scenario.ADVERSE, 35, HISTORY_30, 80).intervals(), "replica");
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<IntervalService.Intervals>> runs = new java.util.ArrayList<>();
            for (int i = 0; i < 8; i++) {
                runs.add(pool.submit(() -> project(s, prime(), Scenario.ADVERSE, 35, HISTORY_30, 80).intervals()));
            }
            for (Future<IntervalService.Intervals> run : runs) {
                assertEquals(first, run.get(), "concurrent");
            }
        } finally {
            pool.shutdownNow();
        }
        // and the request, not the draw count, decides the seed's family: another loan gives other bounds
        assertTrue(!first.equals(project(s, loan("other", 700, 80.0, 6.0, 360, 0.0, "TX", null), Scenario.ADVERSE, 35, HISTORY_30, 80).intervals()));
    }

    @Test
    void aProbabilityNearOneIsClampedAndStillContainsThePoint() {
        LoanFeatures weak = loan("weak", 600, 97.0, 8.0, 360, 35.0, "FL", null);
        RiskProjectionService.Result r = project(service(intervals(60, 100, 60_000)), weak, Scenario.SEVERELY_ADVERSE, 65,
                new DelinquencyHistory(12, 2, null, "D60"), null);
        IntervalService.Interval pd = r.intervals().pdLifetime();
        assertTrue(r.termStructure().summary().pdLifetime() > 0.8, "a loan this weak is a near-certain default: " + pd);
        assertEquals(1.0, Math.min(1.0, pd.upper()), 0);
        assertTrue(pd.lower() <= pd.point() && pd.point() <= pd.upper());
    }

    @Test
    void theNumberOfDrawsIsCappedAndTheResponseSaysSo() {
        IntervalService.Intervals i = project(service(intervals(20, 30, 60_000)), prime(), Scenario.BASELINE, 35, HISTORY_30, 5000).intervals();
        assertEquals(30, i.draws());
        assertTrue(i.capApplied());
        assertTrue(!project(service(intervals(20, 30, 60_000)), prime(), Scenario.BASELINE, 35, HISTORY_30, 30).intervals().capApplied());
    }

    @Test
    void aProjectionOverItsTimeBudgetReturnsThePointEstimateWithTheReason() {
        RiskProjectionService.Result r = project(service(intervals(50, 100, 0)), prime(), Scenario.BASELINE, 35, HISTORY_30, 50);
        assertNotNull(r.termStructure().summary());
        IntervalService.Intervals i = r.intervals();
        assertNull(i.pdLifetime());
        assertNull(i.draws());
        assertTrue(i.reason().contains("time budget of 0 ms"), i.reason());
    }

    @Test
    void noIntervalIsComputedUnlessAskedFor() {
        RiskProjectionService s = service(intervals(20, 30, 60_000));
        RiskProjectionService.Result r = s.project(new TermStructureEngine.Request(prime(), Scenario.BASELINE, null, null, 35, null), 12,
                HISTORY_30, RiskProjectionService.IntervalOptions.NONE);
        assertNull(r.intervals());
        assertTrue(r.modelUsed().startsWith("delinquency@"));
    }

    /** The sampler behind the draws: Marsaglia and Tsang's gamma, with the boost below shape 1, has the right mean and variance. */
    @Test
    void theGammaSamplerHasTheRightMoments() {
        for (double shape : new double[]{0.5, 0.99, 2.5, 40.0}) {
            int n = 40_000;
            double sum = 0, sumSquares = 0;
            for (int i = 0; i < n; i++) {
                double x = new IntervalService.Stream(12345L, i).gamma(shape);
                sum += x;
                sumSquares += x * x;
            }
            double mean = sum / n, variance = sumSquares / n - mean * mean;
            assertEquals(shape, mean, 0.04 * Math.max(1, shape), "mean of Gamma(" + shape + ")");
            assertEquals(shape, variance, 0.08 * Math.max(1, shape), "variance of Gamma(" + shape + ")");
        }
    }

    @Test
    void theDrawnChainCentresOnTheChainOfRecord() {
        double[][] chain = MODEL.transitionMatrix();
        double[] n = {72, 27};
        double[] mean = new double[2];
        int draws = 20_000;
        for (int d = 0; d < draws; d++) {
            IntervalService.Stream stream = new IntervalService.Stream(99L, d);
            for (int r = 0; r < 2; r++) {
                double a = stream.gamma(n[r] * chain[r][0]);
                double b = stream.gamma(n[r] * chain[r][1]);
                mean[r] += a / (a + b);
            }
        }
        assertEquals(chain[0][0], mean[0] / draws, 0.002);
        assertEquals(chain[1][0], mean[1] / draws, 0.005);
    }
}
