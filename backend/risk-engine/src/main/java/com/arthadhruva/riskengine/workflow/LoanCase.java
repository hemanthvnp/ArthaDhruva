package com.arthadhruva.riskengine.workflow;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Human case-tracking state for a loan -- who owns following up on it, whether it's flagged, and
 * where it sits in an analyst's review workflow. Entirely separate from the model outputs
 * ({@code loan_score} etc.): this is never computed, only ever set by a person. One row per
 * loanId, created lazily on first access (see LoanCaseController) rather than requiring a loan to
 * be pre-registered here.
 */
@Entity
@Table(name = "loan_case")
public class LoanCase {

    @Id
    @Column(name = "loan_id")
    private String loanId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LoanCaseStatus status = LoanCaseStatus.NEW;

    @Column(name = "assigned_to")
    private String assignedTo;

    @Column(nullable = false)
    private boolean flagged = false;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected LoanCase() {
        // required by JPA
    }

    public LoanCase(String loanId) {
        this.loanId = loanId;
        this.updatedAt = Instant.now();
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

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void update(LoanCaseStatus status, String assignedTo, boolean flagged) {
        this.status = status;
        this.assignedTo = assignedTo;
        this.flagged = flagged;
        this.updatedAt = Instant.now();
    }
}
