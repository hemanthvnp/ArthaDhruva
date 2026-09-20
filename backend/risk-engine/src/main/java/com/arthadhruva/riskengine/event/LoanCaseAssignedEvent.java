package com.arthadhruva.riskengine.event;

/** Published only when a case's {@code assignedTo} changes to a new, non-null user (a subset of
 * {@link LoanCaseUpdatedEvent} occurrences) -- the notification listener and any future
 * "auto-assign" automation rule both care specifically about this transition, not every field
 * change on the case. */
public class LoanCaseAssignedEvent extends DomainEvent {

    private final String loanId;
    private final String previousAssignee;
    private final String newAssignee;

    public LoanCaseAssignedEvent(Long tenantId, String loanId, String previousAssignee, String newAssignee) {
        super(tenantId);
        this.loanId = loanId;
        this.previousAssignee = previousAssignee;
        this.newAssignee = newAssignee;
    }

    public String getLoanId() {
        return loanId;
    }

    public String getPreviousAssignee() {
        return previousAssignee;
    }

    public String getNewAssignee() {
        return newAssignee;
    }
}
