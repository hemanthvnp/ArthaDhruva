package com.arthadhruva.riskengine.workflow;

import java.time.Instant;
import java.util.Collection;

/** Every field is optional (null = no constraint on that dimension); they combine with AND.
 * {@code loanIds} is how the property-state filter arrives: the caller resolves a state to the
 * loans in it (that attribute lives in the loan catalog, not on a case row). */
public record LoanCaseFilter(
        LoanCaseStatus status,
        Boolean flagged,
        String assignedTo,
        Collection<String> loanIds,
        Instant updatedFrom,
        Instant updatedTo
) {
}
