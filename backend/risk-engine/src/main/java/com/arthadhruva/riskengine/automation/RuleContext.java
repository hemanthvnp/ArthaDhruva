package com.arthadhruva.riskengine.automation;

import java.util.Map;

/** What a rule is evaluated against: the tenant, the loan, and the event's named facts. */
public record RuleContext(Long tenantId, String loanId, Map<String, Object> facts) {
}
