package com.arthadhruva.riskengine.automation;

/** Command pattern: one executable, self-contained effect a rule can have. New actions are new
 * implementations plus a line in {@link RuleActionFactory}; the engine is unchanged. */
public interface RuleAction {
    void execute(RuleContext context, String ruleName);
}
