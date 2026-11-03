package com.arthadhruva.riskengine.survival;

import org.junit.jupiter.api.Test;

import static com.arthadhruva.riskengine.survival.DelinquencyFixtures.ROUTER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DelinquencyRouterTest {

    private static DelinquencyHistory history(int observed, int episodes, Integer cure, String worst) {
        return new DelinquencyHistory(observed, episodes, cure, worst);
    }

    private static String fieldOf(Integer dpd, DelinquencyHistory h) {
        return assertThrows(DelinquencyRouter.InvalidDelinquencyInputException.class, () -> ROUTER.route(dpd, h)).field();
    }

    @Test
    void noDelinquencyDataRunsTheExistingEngine() {
        DelinquencyRouter.Route r = ROUTER.route(null, null);
        assertFalse(r.useDelinquencyModel());
        assertEquals(DelinquencyRouter.NO_DATA, r.reason());
        assertTrue(ROUTER.modelUsed(r).startsWith("survival@"));
    }

    @Test
    void aCurrentLoanWithNoHistoryRunsTheExistingEngine() {
        assertEquals(DelinquencyRouter.CURRENT_NO_HISTORY, ROUTER.route(0, null).reason());
        assertEquals(DelinquencyRouter.CURRENT_NO_HISTORY, ROUTER.route(10, null).reason());
    }

    @Test
    void aStatusWithoutHistoryOrWithIncompleteHistoryFallsBackAndSaysWhy() {
        assertEquals("history incomplete, delinquency model not used", ROUTER.route(45, null).reason());
        assertEquals("history incomplete, delinquency model not used", ROUTER.route(45, history(6, 1, null, null)).reason());
        assertEquals("history incomplete, delinquency model not used",
                ROUTER.route(45, new DelinquencyHistory(null, 1, null, "D30")).reason());
        assertEquals(DelinquencyRouter.NO_STATUS, ROUTER.route(null, history(6, 1, 2, "D30")).reason());
    }

    @Test
    void aSuppliedStatusAndCompleteHistoryRunTheDelinquencyModel() {
        DelinquencyRouter.Route r = ROUTER.route(45, history(12, 2, 4, "D30"));
        assertTrue(r.useDelinquencyModel());
        assertNull(r.reason());
        assertEquals(1, r.startState());
        assertEquals(new DelinquencyModel.History(2, 4, 1), r.history());
        assertTrue(ROUTER.modelUsed(r).startsWith("delinquency@"));
        assertEquals(2, ROUTER.route(65, history(12, 1, null, "D60")).startState());
        // "no cure in 12 months" is the model's sentinel, whether it is given as null or as 12
        assertEquals(DelinquencyFixtures.MODEL.noCureMonths(), ROUTER.route(45, history(12, 1, null, "D30")).history().monthsSinceCure());
        assertEquals(DelinquencyFixtures.MODEL.noCureMonths(), ROUTER.route(45, history(12, 1, 12, "D30")).history().monthsSinceCure());
    }

    @Test
    void impossibleHistoryIsRejectedNamingTheField() {
        // 5 episodes in the last 3 months
        assertEquals("history.episodes30Plus", fieldOf(40, history(3, 5, 1, "D30")));
        assertEquals("history.monthsSinceCure", fieldOf(0, history(3, 1, 5, "D30")));
        assertEquals("history.episodes30Plus", fieldOf(45, history(6, 0, null, "D30")), "30 days late is itself a spell");
        assertEquals("history.maxStatus12m", fieldOf(65, history(6, 1, null, "D30")), "the worst status cannot be better than the current one");
        assertEquals("history.maxStatus12m", fieldOf(0, history(6, 0, null, "D30")));
        assertEquals("history.maxStatus12m", fieldOf(0, history(6, 2, 1, "CURRENT")));
        assertEquals("history.monthsSinceCure", fieldOf(0, history(6, 0, 2, "CURRENT")), "nothing to cure");
        assertEquals("history.monthsSinceCure", fieldOf(0, history(6, 1, null, "D30")), "a current loan that had a spell has cured");
    }

    @Test
    void aLoanAlreadyNinetyDaysLateIsRejectedWhenHistoryIsGiven() {
        assertEquals("daysPastDue", fieldOf(95, history(12, 1, null, "D60")));
        // without the history the request is the existing one: the existing engine stages it
        assertFalse(ROUTER.route(95, null).useDelinquencyModel());
    }
}
