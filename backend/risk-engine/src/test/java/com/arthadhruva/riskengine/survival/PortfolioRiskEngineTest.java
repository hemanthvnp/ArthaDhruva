package com.arthadhruva.riskengine.survival;

import com.arthadhruva.riskengine.cvar.CvarEngine;
import com.arthadhruva.riskengine.cvar.CvarResult;
import com.arthadhruva.riskengine.score.LoanFeatures;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static com.arthadhruva.riskengine.survival.SurvivalFixtures.ENGINE;
import static com.arthadhruva.riskengine.survival.SurvivalFixtures.MARKET;
import static com.arthadhruva.riskengine.survival.SurvivalFixtures.MODEL;
import static com.arthadhruva.riskengine.survival.SurvivalFixtures.VALIDATOR;
import static com.arthadhruva.riskengine.survival.SurvivalFixtures.loan;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortfolioRiskEngineTest {

    private static final ExecutorService ONE = Executors.newFixedThreadPool(1);
    private static final ExecutorService THREE = Executors.newFixedThreadPool(3);
    private static final List<Scenario> SCENARIOS = List.of(Scenario.BASELINE, Scenario.SEVERELY_ADVERSE);

    @AfterAll
    static void shutdown() {
        ONE.shutdownNow();
        THREE.shutdownNow();
    }

    private static List<LoanFeatures> portfolio() {
        String[] states = {"CA", "TX", "FL", "NY", "OH"};
        List<LoanFeatures> loans = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            loans.add(loan("L" + (100 + i), 640 + 9 * i, 60 + 2.0 * (i % 18), 3.0 + 0.2 * i, i % 4 == 0 ? 180 : 360, i % 5 == 0 ? 25.0 : 0.0,
                    states[i % states.length], i % 3 == 0 ? null : MARKET.macroAsOf().minusMonths(6 + 5L * i).toString()));
        }
        return loans;
    }

    private static PortfolioRiskEngine engine(ExecutorService workers, int maxLoans) {
        return new PortfolioRiskEngine(ENGINE, MODEL, VALIDATOR, new CvarEngine(workers, 2, 400_000_000L, 30), workers, maxLoans);
    }

    @Test
    void totalsAreTheSumOfTheLoans() {
        List<LoanFeatures> loans = portfolio();
        AtomicInteger lastProgress = new AtomicInteger();
        PortfolioRiskEngine.PortfolioRisk risk = engine(THREE, 1000).run(loans, SCENARIOS, 1L, lastProgress::set);
        assertEquals(20, risk.loans());
        assertEquals(0, risk.excluded());
        assertEquals(20, risk.projected());
        assertEquals(20, lastProgress.get(), "progress ends at the number of loans");
        assertEquals(MODEL.version(), risk.modelVersion());

        for (int s = 0; s < SCENARIOS.size(); s++) {
            PortfolioRiskEngine.ScenarioRisk r = risk.scenarios().get(s);
            double exposure = 0, ecl12 = 0, lifetime = 0, ifrs9 = 0;
            for (LoanFeatures loan : loans) {
                TermStructureEngine.TermStructure t = ENGINE.project(SurvivalFixtures.request(loan, SCENARIOS.get(s)), 1);
                exposure += t.ecl().exposure();
                ecl12 += t.ecl().ecl12m();
                lifetime += t.ecl().eclLifetime();
                ifrs9 += t.ecl().eclIfrs9();
            }
            assertEquals(exposure, r.exposure(), exposure * 1e-12);
            assertEquals(ecl12, r.ecl12m(), ecl12 * 1e-12);
            assertEquals(lifetime, r.eclLifetime(), lifetime * 1e-12);
            assertEquals(ifrs9, r.eclIfrs9(), ifrs9 * 1e-12);
            assertEquals(ifrs9 / exposure, r.coverage(), 1e-12);
            assertNull(r.eclRelativeStandardError(), "nothing was sampled");

            assertEquals(20, r.stages().stream().mapToInt(PortfolioRiskEngine.StageBreakdown::loans).sum());
            assertEquals(r.eclIfrs9(), r.stages().stream().mapToDouble(PortfolioRiskEngine.StageBreakdown::ecl).sum(), r.eclIfrs9() * 1e-12);
            assertEquals(r.exposure(), r.states().stream().mapToDouble(PortfolioRiskEngine.StateRisk::exposure).sum(), r.exposure() * 1e-12);
            assertEquals(10, r.topLoans().size());
            for (int i = 1; i < r.topLoans().size(); i++) {
                assertTrue(r.topLoans().get(i - 1).eclLifetime() >= r.topLoans().get(i).eclLifetime());
            }

            // The run-off: all of today's balance is at risk in month one, it only shrinks, and the first
            // twelve months of emerging loss are the 12-month ECL.
            PortfolioRiskEngine.Series series = r.series();
            assertEquals(PortfolioRiskEngine.SERIES_MONTHS, series.months().size());
            assertEquals(r.exposure(), series.balanceAtRisk()[0], r.exposure() * 1e-12);
            for (int k = 1; k < series.balanceAtRisk().length; k++) {
                assertTrue(series.balanceAtRisk()[k] < series.balanceAtRisk()[k - 1]);
            }
            assertEquals(r.ecl12m(), Arrays.stream(series.expectedLoss(), 0, 12).sum(), r.ecl12m() * 1e-12);
        }
        assertTrue(risk.scenarios().get(1).eclLifetime() > risk.scenarios().get(0).eclLifetime());
        assertTrue(risk.scenarios().get(1).pd12m() > risk.scenarios().get(0).pd12m());
    }

    /** Chunks are merged in a fixed order, so a rerun is identical to the bit, on any number of threads. */
    @Test
    void resultsDoNotDependOnThreadCountOrInputOrder() {
        List<LoanFeatures> loans = portfolio();
        List<LoanFeatures> reversed = new ArrayList<>(loans);
        java.util.Collections.reverse(reversed);
        PortfolioRiskEngine.PortfolioRisk a = engine(ONE, 1000).run(loans, SCENARIOS, 1L, done -> { });
        PortfolioRiskEngine.PortfolioRisk b = engine(THREE, 1000).run(reversed, SCENARIOS, 1L, done -> { });
        for (int s = 0; s < SCENARIOS.size(); s++) {
            PortfolioRiskEngine.ScenarioRisk x = a.scenarios().get(s), y = b.scenarios().get(s);
            assertEquals(x.eclLifetime(), y.eclLifetime(), 0.0);
            assertEquals(x.eclIfrs9(), y.eclIfrs9(), 0.0);
            assertEquals(x.pd12m(), y.pd12m(), 0.0);
            assertArrayEquals(x.series().expectedLoss(), y.series().expectedLoss(), 0.0);
            assertArrayEquals(x.series().balanceAtRisk(), y.series().balanceAtRisk(), 0.0);
            assertEquals(x.topLoans(), y.topLoans());
            assertEquals(x.states(), y.states());
        }
    }

    @Test
    void aLargePortfolioIsSampledAndScaledWithItsSamplingError() {
        List<LoanFeatures> loans = portfolio();
        PortfolioRiskEngine.PortfolioRisk exact = engine(THREE, 1000).run(loans, List.of(Scenario.BASELINE), 7L, done -> { });
        PortfolioRiskEngine.PortfolioRisk sampled = engine(THREE, 12).run(loans, List.of(Scenario.BASELINE), 7L, done -> { });
        assertEquals(20, sampled.loans());
        assertEquals(12, sampled.projected());
        PortfolioRiskEngine.ScenarioRisk estimate = sampled.scenarios().get(0), truth = exact.scenarios().get(0);
        assertEquals(20, estimate.loans(), "counts are scaled to the portfolio");
        assertNotNull(estimate.eclRelativeStandardError());
        assertTrue(estimate.eclRelativeStandardError() > 0);
        // the estimate is of the right order, and the same seed draws the same sample
        assertTrue(estimate.exposure() > 0.5 * truth.exposure() && estimate.exposure() < 1.5 * truth.exposure());
        assertEquals(estimate.eclLifetime(), engine(ONE, 12).run(loans, List.of(Scenario.BASELINE), 7L, done -> { }).scenarios().get(0).eclLifetime(), 0.0);
    }

    /** The loan listing is the totals taken apart: it must add back up to them, to the cent and beyond. */
    @Test
    void theBaselineListingAddsUpToTheTotals() {
        PortfolioRiskEngine.PortfolioRisk risk = engine(THREE, 1000).run(portfolio(), SCENARIOS, 1L, done -> { });
        PortfolioRiskEngine.ScenarioRisk baseline = risk.scenarios().get(0);
        List<PortfolioRiskEngine.LoanResult> listing = risk.baselineLoans();
        assertEquals(20, listing.size());
        for (int i = 1; i < listing.size(); i++) {
            assertTrue(listing.get(i - 1).loanId().compareTo(listing.get(i).loanId()) < 0, "ordered by loan id");
        }
        assertEquals(baseline.exposure(), listing.stream().mapToDouble(PortfolioRiskEngine.LoanResult::exposure).sum(), baseline.exposure() * 1e-12);
        assertEquals(baseline.ecl12m(), listing.stream().mapToDouble(PortfolioRiskEngine.LoanResult::ecl12m).sum(), baseline.ecl12m() * 1e-12);
        assertEquals(baseline.eclLifetime(), listing.stream().mapToDouble(PortfolioRiskEngine.LoanResult::eclLifetime).sum(), baseline.eclLifetime() * 1e-12);
        assertEquals(baseline.eclIfrs9(), listing.stream().mapToDouble(PortfolioRiskEngine.LoanResult::eclIfrs9).sum(), baseline.eclIfrs9() * 1e-12);
        for (PortfolioRiskEngine.StageBreakdown stage : baseline.stages()) {
            assertEquals(stage.loans(), listing.stream().filter(loan -> loan.stage() == stage.stage()).count());
        }
        for (PortfolioRiskEngine.LoanResult loan : listing) {
            assertEquals(1.0, loan.weight(), 0.0, "nothing was sampled");
            assertTrue(loan.stage() == 1 || loan.stage() == 2, "no loan here is past due, so none is credit-impaired");
            assertEquals(loan.stage() == 1 ? loan.ecl12m() : loan.eclLifetime(), loan.eclIfrs9(), 0.0, "stage decides the allowance");
            assertTrue(loan.lgd() > 0 && loan.lgd() <= 1 && loan.pd12m() > 0 && loan.pd12m() < loan.pdLifetime());
        }

        // A run that leaves the baseline out has no listing and no loss distribution to offer.
        PortfolioRiskEngine.PortfolioRisk stressOnly = engine(THREE, 1000).run(portfolio(), List.of(Scenario.ADVERSE), 1L, done -> { });
        assertTrue(stressOnly.baselineLoans().isEmpty());
        assertNull(stressOnly.scenarios().get(0).lossDistribution());
    }

    /**
     * Expected and unexpected loss are statements about the same loss: the simulation's mean is the
     * 12-month ECL exactly, by construction, and its tail lies beyond it.
     */
    @Test
    void theLossDistributionIsCentredOnTheTwelveMonthEcl() {
        PortfolioRiskEngine.PortfolioRisk risk = engine(THREE, 1000).run(portfolio(), SCENARIOS, 42L, done -> { });
        PortfolioRiskEngine.ScenarioRisk baseline = risk.scenarios().get(0);
        CvarResult loss = baseline.lossDistribution();
        assertNotNull(loss);
        assertNull(risk.scenarios().get(1).lossDistribution(), "only the baseline carries one");
        assertEquals(baseline.ecl12m(), loss.expectedLoss(), baseline.ecl12m() * 1e-12);
        assertEquals(baseline.exposure(), loss.totalExposure(), baseline.exposure() * 1e-12);
        assertEquals(20, loss.numLoans());
        assertEquals(PortfolioRiskEngine.CAPITAL_CONFIDENCE, loss.confidenceLevel(), 0.0);
        assertEquals(42L, loss.seed());
        assertTrue(loss.valueAtRisk() > loss.expectedLoss(), "a one-in-a-thousand year is worse than an average one");
        assertTrue(loss.conditionalValueAtRisk() >= loss.valueAtRisk());
        assertTrue(loss.conditionalValueAtRisk() <= loss.totalExposure());
        // The tail is attributed to named loans of the portfolio, largest first, and never beyond its size.
        double attributed = 0;
        for (int i = 0; i < loss.topContributions().size(); i++) {
            CvarResult.Contribution c = loss.topContributions().get(i);
            assertTrue(c.loanId().startsWith("L1"), c.loanId());
            assertTrue(c.contribution() > 0);
            assertEquals(c.contribution() / loss.conditionalValueAtRisk(), c.share(), 1e-12);
            assertTrue(i == 0 || c.contribution() <= loss.topContributions().get(i - 1).contribution());
            attributed += c.contribution();
        }
        assertTrue(attributed <= loss.conditionalValueAtRisk() * (1 + 1e-9));

        // Seeded from the run: the same run reproduces the same distribution on any number of threads.
        CvarResult again = engine(ONE, 1000).run(portfolio(), SCENARIOS, 42L, done -> { }).scenarios().get(0).lossDistribution();
        assertEquals(loss.valueAtRisk(), again.valueAtRisk(), 0.0);
        assertEquals(loss.conditionalValueAtRisk(), again.conditionalValueAtRisk(), 0.0);
        assertEquals(loss.topContributions(), again.topContributions());
    }

    /** A sampled loan stands for N/n loans in the listing and in the simulation, so both still describe the portfolio. */
    @Test
    void aSampledPortfolioCarriesItsWeightIntoTheListingAndTheLossDistribution() {
        PortfolioRiskEngine.PortfolioRisk sampled = engine(THREE, 12).run(portfolio(), List.of(Scenario.BASELINE), 7L, done -> { });
        PortfolioRiskEngine.ScenarioRisk estimate = sampled.scenarios().get(0);
        assertEquals(12, sampled.baselineLoans().size());
        for (PortfolioRiskEngine.LoanResult loan : sampled.baselineLoans()) {
            assertEquals(20.0 / 12.0, loan.weight(), 1e-15);
        }
        assertEquals(estimate.ecl12m(), sampled.baselineLoans().stream().mapToDouble(loan -> loan.ecl12m() * loan.weight()).sum(), estimate.ecl12m() * 1e-12);
        assertEquals(estimate.ecl12m(), estimate.lossDistribution().expectedLoss(), estimate.ecl12m() * 1e-12);
        assertEquals(estimate.exposure(), estimate.lossDistribution().totalExposure(), estimate.exposure() * 1e-12);
    }

    @Test
    void loansThatCannotBeProjectedAreCountedNotFatal() {
        List<LoanFeatures> loans = new ArrayList<>(portfolio().subList(0, 4));
        loans.add(loan("matured", 720, 80.0, 6.0, 180, 0.0, "TX", MARKET.macroAsOf().minusMonths(200).toString()));
        loans.add(loan("unknown-state", 720, 80.0, 6.0, 360, 0.0, "ZZ", null));
        PortfolioRiskEngine.PortfolioRisk risk = engine(THREE, 1000).run(loans, List.of(Scenario.BASELINE), 1L, done -> { });
        assertEquals(6, risk.loans());
        assertEquals(2, risk.excluded());
        assertEquals(4, risk.projected());
        assertEquals(4, risk.scenarios().get(0).loans());
        assertEquals(4, risk.baselineLoans().size(), "an excluded loan is not in the listing either");
    }
}
