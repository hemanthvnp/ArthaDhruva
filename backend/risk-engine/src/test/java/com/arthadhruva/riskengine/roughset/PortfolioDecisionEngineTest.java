package com.arthadhruva.riskengine.roughset;

import com.arthadhruva.riskengine.ml.MarketData;
import com.arthadhruva.riskengine.score.LoanFeatures;
import com.arthadhruva.riskengine.score.LoanInputValidator;
import com.arthadhruva.riskengine.survival.PortfolioRiskEngine;
import com.arthadhruva.riskengine.survival.Scenario;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.arthadhruva.riskengine.roughset.RoughSetFixtures.SERVICE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortfolioDecisionEngineTest {

    private static final LoanInputValidator VALIDATOR;

    static {
        try {
            VALIDATOR = new LoanInputValidator(new MarketData());
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static List<LoanFeatures> portfolio() {
        List<LoanFeatures> loans = new ArrayList<>();
        int[] scores = {620, 660, 700, 740, 780};
        double[] ltv = {95, 90, 80, 70, 60};
        String[] states = {"FL", "CA", "TX", "FL", "NY"};
        for (int i = 0; i < scores.length; i++) {
            loans.add(new LoanFeatures("P-" + i, scores[i], 38.0, 250000.0 + 10000 * i, ltv[i], ltv[i], 6.0 + 0.25 * i, 360, 2, 1,
                    ltv[i] > 80 ? 25.0 : 0.0, "P", "SF", "P", "R", "N", states[i], "2022-0" + (i + 1)));
        }
        return loans;
    }

    private static DecisionOptions.Resolved options(double threshold, double tolerance, int members) {
        return new DecisionOptions("PD_LIFETIME", threshold, tolerance,
                new DecisionOptions.Granule("PERTURBATION", members, null, null, null, null), null).resolve(8, 32);
    }

    private static PortfolioDecisionEngine.Result run(int threads, List<LoanFeatures> loans, DecisionOptions.Resolved options) {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            return new PortfolioDecisionEngine(SERVICE, VALIDATOR, pool, 4000).run(loans, Scenario.ADVERSE, options, done -> { });
        } finally {
            pool.shutdown();
        }
    }

    /** A threshold inside the range of the book's own figures, so that some loans fall on each side of it. */
    private static double middleThreshold() {
        List<Double> base = new ArrayList<>();
        for (LoanFeatures loan : portfolio()) {
            base.add(SERVICE.decide(RoughSetFixtures.request(loan.normalized(), Scenario.ADVERSE), options(0.5, 0.0, 1)).baseMetric());
        }
        Collections.sort(base);
        return base.get(base.size() / 2);
    }

    @Test
    void theThreeRegionsPartitionTheBook() {
        PortfolioDecisionEngine.Result r = run(2, portfolio(), options(middleThreshold(), 0.0, 4));
        assertEquals(5, r.loans());
        assertEquals(0, r.excluded());
        assertEquals(r.loans(), r.regions().stream().mapToInt(PortfolioDecisionEngine.RegionTotal::loans).sum());
        assertEquals(r.exposure(), r.regions().stream().mapToDouble(PortfolioDecisionEngine.RegionTotal::exposure).sum(), 1e-6);
        assertEquals(1.0, r.regions().stream().mapToDouble(PortfolioDecisionEngine.RegionTotal::loanShare).sum(), 1e-12);
        assertEquals(r.loans(), r.loanRegions().size());
        assertEquals("ADVERSE", r.scenario());
        assertTrue(r.method().contains("not a probability"));
    }

    @Test
    void theLossRangeBracketsTheUnperturbedLossOfTheHighLoans() {
        PortfolioDecisionEngine.Result r = run(2, portfolio(), options(middleThreshold(), 0.0, 8));
        assertTrue(r.loanRegions().stream().anyMatch(l -> l.baseMetric() >= r.parameters().threshold()), "some loans are high");
        assertTrue(r.eclLower() <= r.eclHighBase() + 1e-6, r.eclLower() + " <= " + r.eclHighBase());
        assertTrue(r.eclHighBase() <= r.eclUpper() + 1e-6, r.eclHighBase() + " <= " + r.eclUpper());
        for (PortfolioDecisionEngine.LoanRegion l : r.loanRegions()) {
            assertTrue(l.eclMin() <= l.eclBase() + 1e-9 && l.eclBase() <= l.eclMax() + 1e-9, l.loanId());
            assertTrue(l.metricMin() <= l.baseMetric() + 1e-12 && l.baseMetric() <= l.metricMax() + 1e-12, l.loanId());
        }
    }

    @Test
    void oneThreadAndManyThreadsGiveTheSameResultToTheBit() {
        DecisionOptions.Resolved options = options(middleThreshold(), 0.0, 6);
        PortfolioDecisionEngine.Result one = run(1, portfolio(), options);
        PortfolioDecisionEngine.Result many = run(8, portfolio(), options);
        assertEquals(one, many);
    }

    @Test
    void theOrderTheCallerListsLoansInDoesNotMatter() {
        DecisionOptions.Resolved options = options(middleThreshold(), 0.0, 4);
        List<LoanFeatures> shuffled = new ArrayList<>(portfolio());
        Collections.shuffle(shuffled, new Random(3));
        assertEquals(run(2, portfolio(), options), run(2, shuffled, options));
    }

    @Test
    void anImpossibleThresholdPutsEveryLoanOutsideTheUpperApproximation() {
        PortfolioDecisionEngine.Result r = run(2, portfolio(), options(1.0, 0.0, 4));
        assertTrue(r.loanRegions().stream().allMatch(l -> l.region() == Region.CERTAIN_LOW));
        assertEquals(0.0, r.eclLower());
        assertEquals(0.0, r.eclUpper());
        assertTrue(r.boundaryByState().isEmpty());
    }

    @Test
    void aZeroThresholdMakesEveryLoanCertainlyHigh() {
        PortfolioDecisionEngine.Result r = run(2, portfolio(), options(0.0, 0.0, 4));
        assertTrue(r.loanRegions().stream().allMatch(l -> l.region() == Region.CERTAIN_HIGH));
        assertTrue(r.eclLower() > 0 && r.eclLower() <= r.eclUpper() + 1e-6);
    }

    @Test
    void boundaryExposureIsReportedByState() {
        PortfolioDecisionEngine.Result r = run(2, portfolio(), options(middleThreshold(), 0.0, 8));
        double boundaryExposure = r.loanRegions().stream().filter(l -> l.region() == Region.BOUNDARY)
                .mapToDouble(PortfolioDecisionEngine.LoanRegion::exposure).sum();
        assertEquals(boundaryExposure, r.boundaryByState().stream().mapToDouble(PortfolioDecisionEngine.StateBoundary::exposure).sum(), 1e-6);
        assertEquals(r.loanRegions().stream().filter(l -> l.region() == Region.BOUNDARY).count(),
                r.boundaryByState().stream().mapToInt(PortfolioDecisionEngine.StateBoundary::loans).sum());
    }

    @Test
    void anOversizedRunIsRefusedWithTheProductAndTheLimit() {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            PortfolioDecisionEngine engine = new PortfolioDecisionEngine(SERVICE, VALIDATOR, pool, 100);
            engine.checkSize(10, 10);
            PortfolioDecisionEngine.TooLargeException e = assertThrows(PortfolioDecisionEngine.TooLargeException.class,
                    () -> engine.checkSize(11, 10));
            assertTrue(e.getMessage().contains("11 loans x 10 variants = 110"), e.getMessage());
            assertTrue(e.getMessage().contains("100"), e.getMessage());
        } finally {
            pool.shutdown();
        }
    }

    /** A judged chunk that was kept is not judged again, and the result is the same to the bit. */
    @Test
    void aResumedAnalysisJudgesOnlyTheMissingChunks() {
        DecisionOptions.Resolved options = options(middleThreshold(), 0.0, 4);
        PortfolioDecisionEngine.Result uninterrupted = run(2, portfolio(), options);
        Map<String, byte[]> kept = new HashMap<>();
        int[] saved = new int[1];
        PortfolioRiskEngine.Checkpoints checkpoints = new PortfolioRiskEngine.Checkpoints() {
            @Override
            public Map<String, byte[]> load() {
                return new HashMap<>(kept);
            }

            @Override
            public void save(String key, byte[] chunk) {
                kept.put(key, chunk);
                saved[0]++;
            }
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            PortfolioDecisionEngine engine = new PortfolioDecisionEngine(SERVICE, VALIDATOR, pool, 4000);
            assertEquals(uninterrupted, engine.run(portfolio(), Scenario.ADVERSE, options, done -> { }, checkpoints, "model-a"));
            assertEquals(2, saved[0], "five loans in chunks of four");

            kept.remove(kept.keySet().iterator().next());
            assertEquals(uninterrupted, engine.run(portfolio(), Scenario.ADVERSE, options, done -> { }, checkpoints, "model-a"));
            assertEquals(3, saved[0], "only the missing chunk was judged");

            // Another model, another threshold or another target: nothing kept applies.
            engine.run(portfolio(), Scenario.ADVERSE, options, done -> { }, checkpoints, "model-b");
            assertEquals(5, saved[0]);
            engine.run(portfolio(), Scenario.ADVERSE, options(middleThreshold() / 2, 0.0, 4), done -> { }, checkpoints, "model-a");
            assertEquals(7, saved[0]);
            engine.run(portfolio(), Scenario.SEVERELY_ADVERSE, options, done -> { }, checkpoints, "model-a");
            assertEquals(9, saved[0]);
        } finally {
            pool.shutdown();
        }
    }
}
