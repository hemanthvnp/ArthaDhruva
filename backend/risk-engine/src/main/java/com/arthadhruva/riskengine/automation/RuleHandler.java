package com.arthadhruva.riskengine.automation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Chain of Responsibility: each handler owns one rule, applies it if its condition matches, then
 * always hands the context to the next handler. A failing rule is caught, logged, and skipped, so
 * it can never stop the rules after it or the request that triggered the event.
 */
final class RuleHandler {

    private static final Logger log = LoggerFactory.getLogger(RuleHandler.class);

    private final String ruleName;
    private final RuleCondition condition;
    private final List<RuleAction> actions;
    private RuleHandler next;

    RuleHandler(String ruleName, RuleCondition condition, List<RuleAction> actions) {
        this.ruleName = ruleName;
        this.condition = condition;
        this.actions = actions;
    }

    RuleHandler linkTo(RuleHandler next) {
        this.next = next;
        return next;
    }

    void handle(RuleContext context) {
        try {
            if (condition.matches(context)) {
                for (RuleAction action : actions) {
                    action.execute(context, ruleName);
                }
            }
        } catch (Exception e) {
            log.error("Automation rule '{}' failed for loan {}; continuing with the remaining rules",
                    ruleName, context.loanId(), e);
        }
        if (next != null) {
            next.handle(context);
        }
    }
}
