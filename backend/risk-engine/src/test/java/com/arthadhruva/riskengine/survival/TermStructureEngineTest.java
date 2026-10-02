package com.arthadhruva.riskengine.survival;

import com.arthadhruva.riskengine.score.LoanFeatures;
import org.junit.jupiter.api.Test;

import java.time.YearMonth;
import java.util.List;

import static com.arthadhruva.riskengine.survival.SurvivalFixtures.ENGINE;
import static com.arthadhruva.riskengine.survival.SurvivalFixtures.MARKET;
import static com.arthadhruva.riskengine.survival.SurvivalFixtures.MODEL;
import static com.arthadhruva.riskengine.survival.SurvivalFixtures.loan;
import static com.arthadhruva.riskengine.survival.SurvivalFixtures.prime;
import static com.arthadhruva.riskengine.survival.SurvivalFixtures.request;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TermStructureEngineTest {

    private static TermStructureEngine.TermStructure project(LoanFeatures loan, Scenario scenario) {
        return ENGINE.project(request(loan, scenario), TermStructureEngine.MAX_MONTHS);
    }

    /** Every unit of probability is accounted for: a loan is active, defaulted or prepaid, in every month. */
    @Test
    void probabilityIsConservedEveryMonth() {
        for (Scenario scenario : Scenario.BUILT_IN) {
            TermStructureEngine.TermStructure t = project(prime(), scenario);
            assertEquals(360, t.months().size());
            double previousSurvival = 1.0, previousDefault = 0.0;
            for (TermStructureEngine.MonthPoint m : t.months()) {
                assertEquals(1.0, m.survival() + m.cumulativeDefault() + m.cumulativePrepay(), 1e-12, scenario.name() + " " + m.month());
                assertTrue(m.survival() <= previousSurvival && m.cumulativeDefault() >= previousDefault);
                assertTrue(m.marginalDefault() >= 0 && m.marginalPrepay() >= 0 && m.stressedProbability() >= 0 && m.stressedProbability() <= 1);
                previousSurvival = m.survival();
                previousDefault = m.cumulativeDefault();
            }
            TermStructureEngine.Summary s = t.summary();
            assertTrue(s.pd12m() <= s.pd24m() && s.pd24m() <= s.pdLifetime());
            assertEquals(1.0, s.pdLifetime() + s.prepayLifetime() + s.maturityProbability(), 1e-12);
            assertEquals(t.months().get(11).cumulativeDefault(), s.pd12m(), 1e-15);
        }
    }

    /**
     * The forward recursion is the exact expectation over regime paths: enumerating all 2^13 paths of the
     * first twelve months, weighting each by its Markov probability, gives the same numbers.
     */
    @Test
    void recursionEqualsBruteForceOverEveryRegimePath() {
        for (Scenario scenario : List.of(Scenario.BASELINE, new Scenario("FORCED_3", "", 3, 1.0, 6, 0, 5.0, 6, 2.0, 0.0))) {
            TermStructureEngine.Projection projection = ENGINE.prepare(request(prime(), scenario));
            float[] flat = new float[projection.rows() * ENGINE.features()];
            projection.writeRows(flat, 0);
            double[] hazards = ENGINE.hazards(flat, projection.rows());
            TermStructureEngine.Outcome outcome = ENGINE.finish(projection, hazards, 0);

            int months = 12;
            double[][] transition = MODEL.transitionMatrix();
            double[] start = MODEL.regimeDistribution(projection.origin, projection.origin);
            double expectedDefault = 0, expectedSurvival = 0;
            for (int path = 0; path < (1 << (months + 1)); path++) {
                int regime = path & 1;
                double probability = start[regime], survival = 1.0, defaulted = 0.0;
                for (int k = 0; k < months && probability > 0; k++) {
                    int next = (path >> (k + 1)) & 1;
                    boolean forced = projection.main.forced[k];
                    probability *= forced ? (next == SurvivalModel.STRESSED ? 1.0 : 0.0) : transition[regime][next];
                    int row = 2 * (projection.main.rowOffset[k] + (forced ? 0 : next));
                    defaulted += survival * hazards[row];
                    survival *= 1 - hazards[row] - hazards[row + 1];
                    regime = next;
                }
                expectedDefault += probability * defaulted;
                expectedSurvival += probability * survival;
            }
            assertEquals(expectedDefault, outcome.cumulativeDefault(months)[months - 1], 1e-13, scenario.name());
            assertEquals(expectedSurvival, outcome.survival(months)[months - 1], 1e-13, scenario.name());
        }
    }

    @Test
    void stressRaisesDefaultsAndLosses() {
        TermStructureEngine.TermStructure baseline = project(prime(), Scenario.BASELINE);
        TermStructureEngine.TermStructure adverse = project(prime(), Scenario.ADVERSE);
        TermStructureEngine.TermStructure severe = project(prime(), Scenario.SEVERELY_ADVERSE);
        assertTrue(baseline.summary().pd12m() < adverse.summary().pd12m());
        assertTrue(adverse.summary().pd12m() < severe.summary().pd12m());
        assertTrue(baseline.summary().pdLifetime() < adverse.summary().pdLifetime());
        assertTrue(adverse.summary().pdLifetime() < severe.summary().pdLifetime());
        assertTrue(baseline.ecl().eclLifetime() < adverse.ecl().eclLifetime());
        assertTrue(adverse.ecl().eclLifetime() < severe.ecl().eclLifetime());
        // a forced stress month has no doubt about the regime
        assertEquals(1.0, severe.months().get(0).stressedProbability(), 0.0);
        assertEquals(1.0, severe.months().get(23).stressedProbability(), 0.0);
        assertTrue(severe.months().get(24).stressedProbability() < 1.0);
    }

    /** Default and prepayment compete: when rates rise nobody refinances, so the loan stays exposed longer. */
    @Test
    void ratesMoveExposureThroughPrepayment() {
        TermStructureEngine.Summary baseline = project(prime(), Scenario.BASELINE).summary();
        TermStructureEngine.Summary up = project(prime(), Scenario.RATES_UP_200).summary();
        TermStructureEngine.Summary down = project(prime(), Scenario.RATES_DOWN_200).summary();
        assertTrue(up.expectedLifeMonths() > baseline.expectedLifeMonths());
        assertTrue(down.expectedLifeMonths() < baseline.expectedLifeMonths());
        assertTrue(up.prepayLifetime() < baseline.prepayLifetime());
        assertTrue(down.prepayLifetime() > baseline.prepayLifetime());
        assertTrue(down.pdLifetime() < baseline.pdLifetime(), "a refinancing wave leaves less time to default");
    }

    @Test
    void housePriceShockRaisesSeverityThroughCollateral() {
        LoanFeatures highLtvNoInsurance = loan("high-ltv", 700, 95.0, 6.875, 360, 0.0, "NV", "2025-03");
        TermStructureEngine.TermStructure baseline = project(highLtvNoInsurance, Scenario.BASELINE);
        TermStructureEngine.TermStructure severe = project(highLtvNoInsurance, Scenario.SEVERELY_ADVERSE);
        assertTrue(severe.ecl().lgdPeak() > baseline.ecl().lgdPeak() + 0.05, "a 25% price fall leaves a 95 LTV loan under water");
        assertTrue(severe.months().get(23).mtmLtv() > 100);
        assertEquals(75.0, severe.months().get(23).housePriceIndex(), 1e-9);

        // Mortgage insurance absorbs the top of the claim.
        LoanFeatures insured = loan("insured", 700, 95.0, 6.875, 360, 30.0, "NV", "2025-03");
        assertTrue(project(insured, Scenario.SEVERELY_ADVERSE).ecl().lgdPeak() < severe.ecl().lgdPeak());
    }

    @Test
    void collateralLgdNeverFallsBelowTheFittedLgdAndGrowsWithLtv() {
        assertEquals(0.01, ENGINE.collateralLgd(0.01, 60, 0), 0.0);           // ample equity: fitted LGD
        assertEquals(0.01, ENGINE.collateralLgd(0.01, 85, 0), 1e-12);         // break-even at 1 - haircut
        assertEquals(0.15, ENGINE.collateralLgd(0.01, 100, 0), 1e-12);        // at 100 LTV the haircut is the loss
        assertEquals(1 - 0.85 / 1.25, ENGINE.collateralLgd(0.01, 125, 0), 1e-12);
        assertEquals(1 - 0.85 / 1.25 - 0.25, ENGINE.collateralLgd(0.01, 125, 25), 1e-12);
        assertEquals(0.01, ENGINE.collateralLgd(0.01, 0, 0), 0.0);            // fully repaid
        assertTrue(ENGINE.collateralLgd(0.01, 1e9, 0) <= 1.0);
    }

    @Test
    void stagingFollowsDelinquencyAndTheSicrTest() {
        LoanFeatures seasoned = loan("seasoned", 720, 80.0, 3.0, 360, 0.0, "TX", "2021-06");
        TermStructureEngine.TermStructure current = project(seasoned, Scenario.BASELINE);
        assertEquals(1, current.ecl().stage());
        assertNotNull(current.ecl().referencePd12m(), "a seasoned loan is compared with its origination-time PD");
        assertEquals(current.ecl().ecl12m(), current.ecl().eclIfrs9(), 0.0);
        assertEquals(current.ecl().eclLifetime(), current.ecl().eclCecl(), 0.0);

        TermStructureEngine.TermStructure late = ENGINE.project(new TermStructureEngine.Request(seasoned, Scenario.BASELINE, null, null, 45, null), 12);
        assertEquals(2, late.ecl().stage());
        assertEquals(late.ecl().eclLifetime(), late.ecl().eclIfrs9(), 0.0);
        assertNull(late.ecl().referencePd12m());

        TermStructureEngine.TermStructure impaired = ENGINE.project(new TermStructureEngine.Request(seasoned, Scenario.BASELINE, null, null, 95, null), 12);
        assertEquals(3, impaired.ecl().stage());
        assertEquals(impaired.ecl().lgd() * impaired.ecl().exposure(), impaired.ecl().eclIfrs9(), 1e-9);

        // A recorded origination PD far below today's 12-month PD is a significant increase in credit risk.
        TermStructureEngine.TermStructure deteriorated = ENGINE.project(
                new TermStructureEngine.Request(loan("weak", 620, 95.0, 7.5, 360, 30.0, "FL", "2023-09"), Scenario.BASELINE, null, null, null, 0.001), 12);
        assertEquals(2, deteriorated.ecl().stage());
        assertEquals(0.001, deteriorated.ecl().referencePd12m(), 0.0);

        assertEquals(1, project(prime(), Scenario.BASELINE).ecl().stage());
        assertNull(project(prime(), Scenario.BASELINE).ecl().referencePd12m(), "a new loan has nothing to compare with");
    }

    @Test
    void timelineFollowsTheOriginationMonth() {
        YearMonth origin = MARKET.macroAsOf();
        LoanFeatures seasoned = loan("seasoned", 720, 80.0, 3.0, 360, 0.0, "TX", origin.minusMonths(50).toString());
        TermStructureEngine.TermStructure t = project(seasoned, Scenario.BASELINE);
        assertEquals(50, t.monthsOnBook());
        assertEquals(310, t.remainingMonths());
        assertEquals(51, t.months().get(0).loanAge());
        assertEquals(origin.plusMonths(1).toString(), t.months().get(0).month());
        assertEquals(360, t.months().get(309).loanAge());

        // The same loan described by its age instead of its origination month projects identically.
        LoanFeatures byAge = loan("seasoned", 720, 80.0, 3.0, 360, 0.0, "TX", null);
        TermStructureEngine.TermStructure same = ENGINE.project(new TermStructureEngine.Request(byAge, Scenario.BASELINE, 50, null, null, null), 480);
        assertEquals(t.summary(), same.summary());

        // A reported balance replaces the scheduled one.
        TermStructureEngine.TermStructure reported = ENGINE.project(
                new TermStructureEngine.Request(seasoned, Scenario.BASELINE, null, 250000.0, null, null), 480);
        assertEquals(250000.0, reported.ecl().exposure(), 1e-6);
        assertEquals(t.summary(), reported.summary(), "the balance scales the loss, not the probabilities");
    }

    @Test
    void displayWindowDoesNotChangeTheNumbers() {
        TermStructureEngine.TermStructure full = project(prime(), Scenario.ADVERSE);
        TermStructureEngine.TermStructure shortView = ENGINE.project(request(prime(), Scenario.ADVERSE), 24);
        assertEquals(24, shortView.months().size());
        assertEquals(full.summary(), shortView.summary());
        assertEquals(full.ecl(), shortView.ecl());
        assertEquals(full.months().subList(0, 24), shortView.months());
    }

    @Test
    void rejectsLoansThatCannotBeProjected() {
        LoanFeatures matured = loan("old", 720, 80.0, 6.0, 180, 0.0, "TX", MARKET.macroAsOf().minusMonths(200).toString());
        assertThrows(TermStructureEngine.InvalidProjectionException.class, () -> project(matured, Scenario.BASELINE));
        LoanFeatures seasoned = loan("s", 720, 80.0, 6.0, 360, 0.0, "TX", "2021-06");
        assertThrows(TermStructureEngine.InvalidProjectionException.class,
                () -> ENGINE.project(new TermStructureEngine.Request(seasoned, Scenario.BASELINE, 12, null, null, null), 12));
    }

    /** The engine says where a scenario leaves the data the model learned from. */
    @Test
    void reportsDriversOutsideTheTrainingRange() {
        List<TermStructureEngine.Extrapolation> severe = project(prime(), Scenario.SEVERELY_ADVERSE).extrapolations();
        assertTrue(severe.stream().anyMatch(e -> e.driver().equals("hpi_change_12m") && e.direction().equals("below")),
                "a 25% house-price fall was never observed in training");
        assertTrue(severe.stream().anyMatch(e -> e.driver().equals("loan_age") && e.direction().equals("above")),
                "no vintage in training has been observed for 30 years");
        severe.forEach(e -> assertTrue(e.months() > 0 && e.firstMonth() != null));
    }

    @Test
    void amortizationFactorMatchesALevelPaymentSchedule() {
        assertEquals(1.0, TermStructureEngine.amortizationFactor(6.0, 360, 0), 0.0);
        assertEquals(0.0, TermStructureEngine.amortizationFactor(6.0, 360, 360), 1e-15);
        assertEquals(0.5, TermStructureEngine.amortizationFactor(0.0, 360, 180), 0.0);
        // Balance after one payment: B(1 + r) - payment, payment = B r / (1 - (1 + r)^-n)
        double r = 0.06 / 12, payment = r / (1 - Math.pow(1 + r, -360));
        assertEquals(1 + r - payment, TermStructureEngine.amortizationFactor(6.0, 360, 1), 1e-12);
        assertEquals(1.0, TermStructureEngine.amortizationFactor(6.0, 360, -5), 0.0);
    }

    @Test
    void batchedProjectionsEqualSingleOnes() {
        List<TermStructureEngine.Request> requests = Scenario.BUILT_IN.stream().map(s -> request(prime(), s)).toList();
        List<TermStructureEngine.Outcome> batched = ENGINE.projectAll(requests);
        for (int i = 0; i < requests.size(); i++) {
            TermStructureEngine.TermStructure single = ENGINE.project(requests.get(i), 480);
            assertEquals(single.summary(), batched.get(i).summary());
            assertEquals(single.ecl(), batched.get(i).ecl());
        }
    }
}
