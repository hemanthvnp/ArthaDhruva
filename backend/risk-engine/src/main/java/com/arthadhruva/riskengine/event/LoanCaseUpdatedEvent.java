package com.arthadhruva.riskengine.event;

import com.arthadhruva.riskengine.workflow.LoanCaseStatus;

/** Published whenever a loan case's status, assignment, or flag is set (see
 * {@code workflow.LoanCaseService#update}) -- the general-purpose case-changed event that
 * automation rules and webhooks listen for; {@link LoanCaseAssignedEvent} is the narrower
 * event for the specific "assignment changed" sub-case. */
public class LoanCaseUpdatedEvent extends DomainEvent {

    private final String loanId;
    private final LoanCaseStatus status;
    private final String assignedTo;
    private final boolean flagged;
    private final boolean wasFlagged;

    public LoanCaseUpdatedEvent(Long tenantId, String loanId, LoanCaseStatus status, String assignedTo, boolean flagged, boolean wasFlagged) {
        super(tenantId);
        this.loanId = loanId;
        this.status = status;
        this.assignedTo = assignedTo;
        this.flagged = flagged;
        this.wasFlagged = wasFlagged;
    }

    public String getLoanId() {
        return loanId;
    }

    public LoanCaseStatus getStatus() {
        return status;
    }

    public String getAssignedTo() {
        return assignedTo;
    }

    public boolean isFlagged() {
        return flagged;
    }

    /** Whether the case was already flagged before this update (lets listeners react to the transition only). */
    public boolean wasFlagged() {
        return wasFlagged;
    }
}
