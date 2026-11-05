package com.arthadhruva.riskengine.survival;

import com.arthadhruva.riskengine.score.LoanFeatures;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.arthadhruva.riskengine.survival.DelinquencyFixtures.CLEAN;
import static com.arthadhruva.riskengine.survival.DelinquencyFixtures.DEEP;
import static com.arthadhruva.riskengine.survival.DelinquencyFixtures.ENGINE;
import static com.arthadhruva.riskengine.survival.DelinquencyFixtures.RECENT;
import static com.arthadhruva.riskengine.survival.DelinquencyFixtures.request;
import static com.arthadhruva.riskengine.survival.SurvivalFixtures.loan;
import static com.arthadhruva.riskengine.survival.SurvivalFixtures.prime;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiStateEngineTest {

    private static final int[] DAYS_PAST_DUE = {0, 35, 65};

    private static List<LoanFeatures> loans() {
        return List.of(prime(), loan("weak", 640, 95.0, 7.25, 360, 30.0, "FL", null), loan("seasoned", 720, 80.0, 3.0, 360, 0.0, "TX", "2021-06"),
                loan("fifteen", 780, 60.0, 5.75, 180, 0.0, "OH", null));
    }

    /** Task 5.4: active plus defaulted plus prepaid is 1 in every month, from every starting state, over a full term. */
    @Test
    void probabilityIsConservedEveryMonthFromEveryStartingState() {
        for (Scenario scenario : List.of(Scenario.BASELINE, Scenario.SEVERELY_ADVERSE)) {
            for (int dpd : DAYS_PAST_DUE) {
                TermStructureEngine.TermStructure t = ENGINE.project(request(prime(), scenario, dpd, RECENT), TermStructureEngine.MAX_MONTHS);
                assertEquals(360, t.months().size());
                for (TermStructureEngine.MonthPoint m : t.months()) {
                    assertEquals(1.0, m.survival() + m.cumulativeDefault() + m.cumulativePrepay(), 1e-12,
                            scenario.name() + " from " + dpd + " dpd, " + m.month());
                }
                TermStructureEngine.Summary s = t.summary();
                assertEquals(1.0, s.pdLifetime() + s.prepayLifetime() + s.maturityProbability(), 1e-12);
                assertTrue(s.pd12m() <= s.pd24m() && s.pd24m() <= s.pdLifetime());
            }
        }
    }

    /** Task 5.5: for otherwise identical loans, lifetime PD does not fall as the starting state worsens. */
    @Test
    void lifetimeDefaultIsNonDecreasingInTheStartingState() {
        for (LoanFeatures loan : loans()) {
            for (Scenario scenario : List.of(Scenario.BASELINE, Scenario.SEVERELY_ADVERSE)) {
                for (DelinquencyModel.History history : List.of(CLEAN, RECENT)) {
                    double[] pd = new double[3];
                    for (int i = 0; i < 3; i++) {
                        pd[i] = ENGINE.project(request(loan, scenario, DAYS_PAST_DUE[i], history), TermStructureEngine.MAX_MONTHS)
                                .summary().pdLifetime();
                    }
                    String at = loan.loanId() + " / " + scenario.name() + " / " + history;
                    assertTrue(pd[0] <= pd[1] && pd[1] <= pd[2], at + ": " + pd[0] + ", " + pd[1] + ", " + pd[2]);
                }
            }
        }
    }

    /**
     * A late loan is worse than a current one by more than the ordering: a seasoned loan (a brand-new one at age 1 is out of
     * the model's range when it is late), each with the history its status implies. The figures are the 12-month PD.
     */
    @Test
    void aLateLoanIsMateriallyRiskier() {
        LoanFeatures seasoned = loan("seasoned", 720, 80.0, 6.5, 360, 0.0, "TX", "2022-03");
        double current = ENGINE.project(request(seasoned, Scenario.BASELINE, 0, CLEAN), 1).summary().pd12m();
        double thirty = ENGINE.project(request(seasoned, Scenario.BASELINE, 35, new DelinquencyModel.History(1, 13, 1)), 1).summary().pd12m();
        double sixty = ENGINE.project(request(seasoned, Scenario.BASELINE, 65, new DelinquencyModel.History(1, 13, 2)), 1).summary().pd12m();
        assertTrue(thirty > 3 * current, "30 days past due: " + thirty + " against current " + current);
        assertTrue(sixty > 2 * thirty, "60 days past due: " + sixty + " against 30 days " + thirty);
    }

    /** Task 4.3: 30 days past due is stage 2 whatever the projected PD; a current loan follows the existing rules. */
    @Test
    void stagingUsesTheSuppliedDelinquencyState() {
        assertEquals(2, ENGINE.project(request(prime(), Scenario.BASELINE, 35, RECENT), 1).ecl().stage());
        assertEquals(2, ENGINE.project(request(prime(), Scenario.BASELINE, 65, DEEP), 1).ecl().stage());
        assertEquals(1, ENGINE.project(request(prime(), Scenario.BASELINE, 0, CLEAN), 1).ecl().stage());
    }

    /** Task 4.5 / the spec's out-of-range scenario: a driver pushed past this model's training range is reported. */
    @Test
    void reportsMonthsWhereADriverLeavesTheTrainingRange() {
        double limit = DelinquencyFixtures.MODEL.envelope("unemployment").max();
        Scenario shock = new Scenario("SHOCK", "", 6, 15.0, 14, 0, 0.0, 12, 0.0, 0.0);
        TermStructureEngine.TermStructure t = ENGINE.project(request(prime(), shock, 35, RECENT), 24);
        TermStructureEngine.Extrapolation unemployment = t.extrapolations().stream()
                .filter(e -> e.driver().equals("unemployment") && e.direction().equals("above")).findFirst().orElseThrow();
        assertEquals(limit, unemployment.trainedLimit(), 1e-12);
        assertTrue(unemployment.extreme() > limit);
        // the first month over the limit is the one the report names, and the series is over the limit from there
        int first = t.months().stream().map(TermStructureEngine.MonthPoint::unemployment).toList().indexOf(
                t.months().stream().map(TermStructureEngine.MonthPoint::unemployment).filter(u -> u > limit).findFirst().orElseThrow());
        assertEquals(t.months().get(first).month(), unemployment.firstMonth());
    }

    @Test
    void theResponseNamesTheDelinquencyModel() {
        TermStructureEngine.TermStructure t = ENGINE.project(request(prime(), Scenario.BASELINE, 35, RECENT), 12);
        assertTrue(t.modelVersion().startsWith("delinquency@"), t.modelVersion());
        assertEquals(1.0, t.assumptions().defaultOverlay(), 0);
    }
}
