package com.arthadhruva.riskengine.survival;

import org.junit.jupiter.api.Test;

import static com.arthadhruva.riskengine.survival.MultiStateEngine.agedHistory;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rule that ages a loan's history over a projection, case by case. The same cases are in
 * backend/tests/test_delinquency_history.py, derived by hand from the rule in backend/delinquency_history.py; the golden
 * test then holds whole projections of the two implementations to each other.
 */
class AgedHistoryTest {

    private static final int NO_CURE = 13;

    private static DelinquencyModel.History h(int episodes, int since, int worst) {
        return new DelinquencyModel.History(episodes, since, worst);
    }

    @Test
    void aCurrentLoanWithARecentCureAgesAndIsCleanOnceTheCureIsAWindowOld() {
        DelinquencyModel.History recent = h(2, 3, 1);
        assertEquals(h(2, 3, 1), agedHistory(0, 0, recent, NO_CURE));
        assertEquals(h(2, 11, 1), agedHistory(0, 8, recent, NO_CURE));
        assertEquals(h(0, NO_CURE, 0), agedHistory(0, 9, recent, NO_CURE), "a window old: nothing left in the window");
        assertEquals(h(0, NO_CURE, 0), agedHistory(0, 300, recent, NO_CURE));
    }

    @Test
    void aCleanLoanStaysCleanFromCurrentAndShowsOneFreshSpellFromALateState() {
        DelinquencyModel.History clean = h(0, NO_CURE, 0);
        for (int elapsed : new int[]{0, 1, 40}) {
            assertEquals(h(0, NO_CURE, 0), agedHistory(0, elapsed, clean, NO_CURE));
            assertEquals(h(1, NO_CURE, 1), agedHistory(1, elapsed, clean, NO_CURE));
            assertEquals(h(1, NO_CURE, 2), agedHistory(2, elapsed, clean, NO_CURE));
        }
    }

    @Test
    void aLoanThatStartsLateIsTakenToHaveCuredAtTheStartWhenItIsCurrentAgain() {
        DelinquencyModel.History late = h(1, NO_CURE, 1);
        assertEquals(h(1, 0, 1), agedHistory(0, 0, late, NO_CURE));
        assertEquals(h(1, 5, 1), agedHistory(0, 5, late, NO_CURE));
        assertEquals(h(0, NO_CURE, 0), agedHistory(0, 12, late, NO_CURE));
        assertEquals(h(1, NO_CURE, 1), agedHistory(1, 3, late, NO_CURE));
        assertEquals(h(1, NO_CURE, 2), agedHistory(2, 3, late, NO_CURE));
    }

    @Test
    void aDelinquentRowAlwaysCarriesItsOwnSpell() {
        for (int episodes = 0; episodes < 3; episodes++) {
            for (int since = 0; since <= NO_CURE; since++) {
                for (int worst = 0; worst < 3; worst++) {
                    for (int source = 1; source < 3; source++) {
                        for (int elapsed = 0; elapsed < 20; elapsed++) {
                            DelinquencyModel.History out = agedHistory(source, elapsed, h(episodes, since, worst), NO_CURE);
                            assertTrue(out.episodes() >= 1 && out.maxState() >= source && out.monthsSinceCure() >= 0
                                    && out.monthsSinceCure() <= NO_CURE);
                        }
                    }
                }
            }
        }
    }
}
