package com.arthadhruva.riskengine.automation;

import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/** Builds the handler chain for a tenant's enabled rules (in {@code position} order) and runs it. */
@Component
public class AutomationEngine {

    private static final ThreadLocal<Boolean> RUNNING = ThreadLocal.withInitial(() -> false);
    private final ObjectMapper mapper = new ObjectMapper();
    private final RuleActionFactory actionFactory;

    public AutomationEngine(RuleActionFactory actionFactory) {
        this.actionFactory = actionFactory;
    }

    /** True while a rule's own actions are executing: the events those actions publish must not
     * re-trigger rules (a "flag on update" rule would otherwise recurse forever). */
    public static boolean isRunning() {
        return RUNNING.get();
    }

    public void run(List<AutomationRule> rules, RuleContext context) {
        RuleHandler head = null;
        RuleHandler tail = null;
        for (AutomationRule rule : rules) {
            RuleHandler handler;
            try {
                handler = new RuleHandler(rule.getName(),
                        new RuleCondition(rule.getConditionField(), rule.getConditionOp(), rule.getConditionValue()),
                        parseActions(rule.getActions()));
            } catch (RuntimeException e) {
                continue; // a rule with unparseable stored actions is skipped, not fatal to the chain
            }
            if (head == null) {
                head = handler;
            } else {
                tail.linkTo(handler);
            }
            tail = handler;
        }
        if (head == null) {
            return;
        }
        RUNNING.set(true);
        try {
            head.handle(context);
        } finally {
            RUNNING.remove();
        }
    }

    private List<RuleAction> parseActions(String json) {
        List<ActionSpec> specs = mapper.readValue(json, new TypeReference<List<ActionSpec>>() {});
        List<RuleAction> actions = new ArrayList<>();
        for (ActionSpec spec : specs) {
            actions.add(actionFactory.create(spec));
        }
        return actions;
    }
}
