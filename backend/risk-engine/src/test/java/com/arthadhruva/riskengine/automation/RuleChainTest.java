package com.arthadhruva.riskengine.automation;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleChainTest {

    private static RuleContext scored(double risk) {
        return new RuleContext(1L, "L1", Map.of("calibratedRisk", risk, "status", "NEW"));
    }

    @Test
    void numericConditionOperators() {
        RuleContext ctx = scored(0.25);
        assertTrue(new RuleCondition("calibratedRisk", ConditionOp.GT, "0.20").matches(ctx));
        assertFalse(new RuleCondition("calibratedRisk", ConditionOp.GT, "0.25").matches(ctx));
        assertTrue(new RuleCondition("calibratedRisk", ConditionOp.GTE, "0.25").matches(ctx));
        assertTrue(new RuleCondition("calibratedRisk", ConditionOp.LT, "0.30").matches(ctx));
        assertFalse(new RuleCondition("missingFact", ConditionOp.EQ, "x").matches(ctx));
    }

    @Test
    void stringFactsOnlySupportEquality() {
        RuleContext ctx = scored(0.1);
        assertTrue(new RuleCondition("status", ConditionOp.EQ, "new").matches(ctx));
        assertTrue(new RuleCondition("status", ConditionOp.NE, "CLEARED").matches(ctx));
        assertFalse(new RuleCondition("status", ConditionOp.GT, "A").matches(ctx));
    }

    @Test
    void everyMatchingRuleRunsInOrderAndNonMatchingDoesNot() {
        List<String> ran = new ArrayList<>();
        RuleHandler first = new RuleHandler("first", new RuleCondition("calibratedRisk", ConditionOp.GT, "0.2"),
                List.of((c, r) -> ran.add(r)));
        RuleHandler skipped = new RuleHandler("skipped", new RuleCondition("calibratedRisk", ConditionOp.GT, "0.9"),
                List.of((c, r) -> ran.add(r)));
        RuleHandler last = new RuleHandler("last", new RuleCondition("calibratedRisk", ConditionOp.LT, "0.5"),
                List.of((c, r) -> ran.add(r)));
        first.linkTo(skipped).linkTo(last);

        first.handle(scored(0.3));

        assertEquals(List.of("first", "last"), ran);
    }

    @Test
    void oneRuleFailingDoesNotStopTheRest() {
        List<String> ran = new ArrayList<>();
        RuleHandler boom = new RuleHandler("boom", new RuleCondition("calibratedRisk", ConditionOp.GT, "0.1"),
                List.of((c, r) -> {
                    throw new IllegalStateException("action blew up");
                }));
        RuleHandler after = new RuleHandler("after", new RuleCondition("calibratedRisk", ConditionOp.GT, "0.1"),
                List.of((c, r) -> ran.add(r)));
        boom.linkTo(after);

        boom.handle(scored(0.3));

        assertEquals(List.of("after"), ran);
    }
}
