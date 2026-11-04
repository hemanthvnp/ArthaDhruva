package com.arthadhruva.riskengine.survival;

import com.arthadhruva.riskengine.cvar.CvarEngine;
import com.arthadhruva.riskengine.score.LoanFeatures;
import com.arthadhruva.riskengine.score.PortfolioLoan;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.arthadhruva.riskengine.survival.SurvivalFixtures.MARKET;
import static com.arthadhruva.riskengine.survival.SurvivalFixtures.MODEL;
import static com.arthadhruva.riskengine.survival.SurvivalFixtures.VALIDATOR;
import static com.arthadhruva.riskengine.survival.SurvivalFixtures.loan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A portfolio in which some loans carry where they stand now (days past due and a payment history): those are projected by the
 * delinquency model, the rest by the survival model, in the same run, with the same guarantees (the same figures on any thread
 * count, resumable from checkpoints) and a result that says how many ran on each.
 */
class PortfolioDelinquencyTest {

    private static final ExecutorService ONE = Executors.newFixedThreadPool(1);
    private static final ExecutorService THREE = Executors.newFixedThreadPool(3);
    private static final List<Scenario> SCENARIOS = List.of(Scenario.BASELINE, Scenario.SEVERELY_ADVERSE);

    private static final DelinquencyHistory ONE_SPELL_30 = new DelinquencyHistory(12, 1, null, "D30");
    private static final DelinquencyHistory ONE_SPELL_60 = new DelinquencyHistory(12, 1, null, "D60");
    private static final DelinquencyHistory CURED_LATELY = new DelinquencyHistory(12, 1, 3, "D30");
    private static final DelinquencyHistory NEVER_LATE = new DelinquencyHistory(12, 0, null, "CURRENT");

    @AfterAll
    static void shutdown() {
        ONE.shutdownNow();
        THREE.shutdownNow();
    }

    private static PortfolioRiskEngine engine(ExecutorService workers) {
        return new PortfolioRiskEngine(SurvivalFixtures.ENGINE, DelinquencyFixtures.ENGINE, DelinquencyFixtures.ROUTER,
                DelinquencyFixtures.MODEL, MODEL, VALIDATOR, new CvarEngine(workers, 2, 400_000_000L, 30), workers, 1000);
    }

    private static LoanFeatures base(int i) {
        String[] states = {"CA", "TX", "FL", "NY", "OH"};
        return loan("L" + (100 + i), 640 + 9 * i, 60 + 2.0 * (i % 18), 3.0 + 0.2 * i, i % 4 == 0 ? 180 : 360, i % 5 == 0 ? 25.0 : 0.0,
                states[i % states.length], MARKET.macroAsOf().minusMonths(6 + 5L * i).toString());
    }

    /** Twenty loans: every fourth is late (30, then 60 days), some cured lately or never late, the rest give nothing. */
    private static List<PortfolioLoan> portfolio() {
        List<PortfolioLoan> loans = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            LoanFeatures l = base(i);
            loans.add(switch (i % 5) {
                case 0 -> new PortfolioLoan(l, 35, ONE_SPELL_30);
                case 1 -> new PortfolioLoan(l, 65, ONE_SPELL_60);
                case 2 -> new PortfolioLoan(l, 0, CURED_LATELY);
                case 3 -> new PortfolioLoan(l, 0, NEVER_LATE);
                default -> PortfolioLoan.of(l);
            });
        }
        return loans;
    }

    private static String exact(PortfolioRiskEngine.PortfolioRisk risk) {
        return new ObjectMapper().writeValueAsString(risk).replaceAll("\"elapsedMillis\":\\d+", "");
    }

    private static PortfolioRiskEngine.PortfolioRisk run(ExecutorService workers, List<PortfolioLoan> loans,
                                                         PortfolioRiskEngine.Checkpoints checkpoints) {
        return engine(workers).runPortfolio(loans, SCENARIOS, 7L, done -> { }, checkpoints);
    }

    @Test
    void theFiguresAreTheSameOnAnyNumberOfThreads() {
        String one = exact(run(ONE, portfolio(), PortfolioRiskEngine.Checkpoints.NONE));
        String three = exact(run(THREE, portfolio(), PortfolioRiskEngine.Checkpoints.NONE));
        assertEquals(one, three, "a portfolio with statuses must reproduce bit for bit on one thread or three");
        List<PortfolioLoan> reversed = new ArrayList<>(portfolio());
        java.util.Collections.reverse(reversed);
        assertEquals(one, exact(run(THREE, reversed, PortfolioRiskEngine.Checkpoints.NONE)), "and in any order of listing");
    }

    @Test
    void theResultSaysHowManyLoansRanOnEachModelAndWhyTheOthersDidNot() {
        PortfolioRiskEngine.PortfolioRisk risk = run(THREE, portfolio(), PortfolioRiskEngine.Checkpoints.NONE);
        for (PortfolioRiskEngine.ScenarioRisk scenario : risk.scenarios()) {
            Map<String, Integer> models = scenario.modelLoans();
            assertEquals(2, models.size(), models.toString());
            assertEquals(16, models.get(DelinquencyFixtures.MODEL.version()), "four in five give status and history");
            assertEquals(4, models.get(MODEL.version()));
            assertEquals(20, models.values().stream().mapToInt(Integer::intValue).sum());
            assertEquals(Map.of(DelinquencyRouter.NO_DATA, 4), scenario.fallbackReasons());
        }
    }

    @Test
    void aLoanWithNoStatusIsCountedAsAlwaysOnTheSurvivalModel() {
        List<PortfolioLoan> plain = PortfolioLoan.all(portfolio().stream().map(PortfolioLoan::loan).toList());
        PortfolioRiskEngine.PortfolioRisk risk = run(THREE, plain, PortfolioRiskEngine.Checkpoints.NONE);
        PortfolioRiskEngine.ScenarioRisk baseline = risk.scenarios().get(0);
        assertEquals(Map.of(MODEL.version(), 20), baseline.modelLoans());
        assertEquals(Map.of(DelinquencyRouter.NO_DATA, 20), baseline.fallbackReasons());
    }

    @Test
    void aLateLoanCarriesMoreRiskThanTheSameLoanGivenNoStatus() {
        LoanFeatures l = base(1);
        Map<String, PortfolioRiskEngine.LoanResult> late = byId(run(ONE, List.of(new PortfolioLoan(l, 65, ONE_SPELL_60)),
                PortfolioRiskEngine.Checkpoints.NONE));
        Map<String, PortfolioRiskEngine.LoanResult> plain = byId(run(ONE, List.of(PortfolioLoan.of(l)), PortfolioRiskEngine.Checkpoints.NONE));
        PortfolioRiskEngine.LoanResult withStatus = late.get(l.loanId());
        PortfolioRiskEngine.LoanResult without = plain.get(l.loanId());
        assertTrue(withStatus.pdLifetime() > without.pdLifetime(),
                "65 days past due: " + withStatus.pdLifetime() + " against " + without.pdLifetime() + " with no status");
        assertTrue(withStatus.pd12m() > without.pd12m());
        assertEquals(2, withStatus.stage(), "30 or more days past due is stage 2");
    }

    @Test
    void thePortfolioGivesWhatTheSingleLoanProjectionGives() {
        Map<String, PortfolioRiskEngine.LoanResult> inRuns = byId(run(THREE, portfolio(), PortfolioRiskEngine.Checkpoints.NONE));
        for (PortfolioLoan p : portfolio()) {
            if (p.history() == null) {
                continue;
            }
            DelinquencyRouter.Route route = DelinquencyFixtures.ROUTER.route(p.daysPastDue(), p.history());
            MultiStateEngine.Outcome single = DelinquencyFixtures.ENGINE.projectAll(List.of(new MultiStateEngine.Request(
                    new TermStructureEngine.Request(p.loan(), Scenario.BASELINE, null, null, p.daysPastDue(), null), route.startState(),
                    route.history()))).get(0);
            PortfolioRiskEngine.LoanResult inRun = inRuns.get(p.loanId());
            assertEquals(single.summary().pd12m(), inRun.pd12m(), 1e-12, p.loanId());
            assertEquals(single.summary().pdLifetime(), inRun.pdLifetime(), 1e-12, p.loanId());
            assertEquals(single.ecl().eclLifetime(), inRun.eclLifetime(), 1e-9 * Math.max(1, single.ecl().eclLifetime()), p.loanId());
            assertEquals(single.ecl().stage(), inRun.stage(), p.loanId());
        }
    }

    @Test
    void aStatusTheEngineCannotUseFallsBackAndIsCountedNotDropped() {
        LoanFeatures l = base(2);
        // 90 or more days past due has defaulted: the delinquency model does not project it, and the router refuses the pair
        PortfolioRiskEngine.PortfolioRisk risk = run(ONE, List.of(new PortfolioLoan(l, 100, ONE_SPELL_60)),
                PortfolioRiskEngine.Checkpoints.NONE);
        PortfolioRiskEngine.ScenarioRisk baseline = risk.scenarios().get(0);
        assertEquals(1, risk.projected(), "the loan is projected, not excluded");
        assertEquals(0, risk.excluded());
        assertEquals(Map.of(MODEL.version(), 1), baseline.modelLoans());
        assertEquals(1, baseline.fallbackReasons().size());
        assertTrue(baseline.fallbackReasons().keySet().iterator().next().startsWith("delinquency data not usable (daysPastDue)"),
                baseline.fallbackReasons().toString());
        assertEquals(3, baseline.stages().get(2).stage(), "and staged as the existing engine stages a loan 90 days past due");
        assertEquals(1, baseline.stages().get(2).loans());
    }

    @Test
    void aStoredChunkIsOnlyUsedForTheStatusItWasProjectedWith() {
        List<PortfolioLoan> thirty = new ArrayList<>(), sixty = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            thirty.add(new PortfolioLoan(base(i), 35, ONE_SPELL_30));
            sixty.add(new PortfolioLoan(base(i), 65, ONE_SPELL_60));
        }
        Kept kept = new Kept();
        String first = exact(run(THREE, thirty, kept));
        assertEquals(1, kept.saved, "eight loans are one chunk");
        // the same eight loans, now 65 days past due: the stored chunk must not be read for them
        String fresh = exact(run(THREE, sixty, PortfolioRiskEngine.Checkpoints.NONE));
        String withStored = exact(run(THREE, sixty, kept));
        assertNotEquals(first, fresh, "the status changes the figures, or this test says nothing");
        assertEquals(fresh, withStored, "a chunk stored for 30 days past due was not reused for 60");
        assertEquals(2, kept.saved, "the 60-day chunk was projected and stored under its own key");
        // and the chunk for the status it was projected with is read as stored: no new chunk, the same figures
        assertEquals(first, exact(run(THREE, thirty, kept)));
        assertEquals(2, kept.saved);
        assertFalse(kept.chunks.isEmpty());
    }

    @Test
    void aMixedRunResumesFromItsCheckpointsWithTheSameFigures() {
        List<PortfolioLoan> loans = portfolio();
        String uninterrupted = exact(run(THREE, loans, PortfolioRiskEngine.Checkpoints.NONE));
        Kept first = new Kept();
        assertEquals(uninterrupted, exact(run(THREE, loans, first)), "keeping checkpoints changes nothing");
        assertEquals(3, first.saved, "20 loans in chunks of 8");
        // the worker died with one chunk still to do
        Kept second = new Kept();
        second.chunks.putAll(first.chunks);
        second.chunks.remove(second.chunks.keySet().iterator().next());
        assertEquals(uninterrupted, exact(run(ONE, loans, second)), "a resumed run gives the figures of an uninterrupted one");
        assertEquals(1, second.saved, "only the missing chunk was projected again");
    }

    private static Map<String, PortfolioRiskEngine.LoanResult> byId(PortfolioRiskEngine.PortfolioRisk risk) {
        Map<String, PortfolioRiskEngine.LoanResult> out = new HashMap<>();
        risk.baselineLoans().forEach(l -> out.put(l.loanId(), l));
        return out;
    }

    private static final class Kept implements PortfolioRiskEngine.Checkpoints {
        final Map<String, byte[]> chunks = new HashMap<>();
        int saved;

        @Override
        public Map<String, byte[]> load() {
            return new HashMap<>(chunks);
        }

        @Override
        public void save(String key, byte[] chunk) {
            chunks.put(key, chunk);
            saved++;
        }
    }
}
