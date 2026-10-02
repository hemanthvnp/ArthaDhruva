package com.arthadhruva.riskengine.automation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.Consumer;

/**
 * Chain of Responsibility: each handler owns one rule, applies it if its condition matches, then always
 * hands the context to the next handler. A rule's actions run inside {@code boundary} -- in production a
 * new transaction per rule (see AutomationEngine) -- and a failure is logged and skipped, so one broken
 * rule never affects the rules after it or the change that triggered them.
 */
final class RuleHandler {

    private static final Logger log = LoggerFactory.getLogger(RuleHandler.class);

    private final String ruleName;
    private final RuleCondition condition;
    private final List<RuleAction> actions;
    private final Consumer<Runnable> boundary;
    private RuleHandler next;

    RuleHandler(String ruleName, RuleCondition condition, List<RuleAction> actions) {
        this(ruleName, condition, actions, Runnable::run);
    }

    RuleHandler(String ruleName, RuleCondition condition, List<RuleAction> actions, Consumer<Runnable> boundary) {
        this.ruleName = ruleName;
        this.condition = condition;
        this.actions = actions;
        this.boundary = boundary;
    }

    RuleHandler linkTo(RuleHandler next) {
        this.next = next;
        return next;
    }

    void handle(RuleContext context) {
        try {
            if (condition.matches(context)) {
                boundary.accept(() -> {
                    for (RuleAction action : actions) {
                        action.execute(context, ruleName);
                    }
                });
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
