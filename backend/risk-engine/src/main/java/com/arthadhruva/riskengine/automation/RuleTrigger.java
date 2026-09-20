package com.arthadhruva.riskengine.automation;

/** The domain events a rule can react to, each with the facts a condition may test. */
public enum RuleTrigger {
    LOAN_SCORED(java.util.Set.of("calibratedRisk", "rawRisk")),
    LOAN_CASE_UPDATED(java.util.Set.of("status", "flagged", "assignedTo"));

    private final java.util.Set<String> fields;

    RuleTrigger(java.util.Set<String> fields) {
        this.fields = fields;
    }

    public boolean supportsField(String field) {
        return fields.contains(field);
    }
}
