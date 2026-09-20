package com.arthadhruva.riskengine.workflow;

import com.arthadhruva.riskengine.tenant.TenantAware;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Version;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;

import java.time.Instant;

/**
 * Human case-tracking state for a loan -- who owns following up on it, whether it's flagged, and
 * where it sits in an analyst's review workflow. Entirely separate from the model outputs
 * ({@code loan_score} etc.): this is never computed, only ever set by a person. One row per
 * {@code (tenantId, loanId)}, created lazily on first access (see LoanCaseService) rather than
 * requiring a loan to be pre-registered here. See {@code score.LoanScoreRecord}'s class doc for
 * why the filter condition below works the same for an {@link EmbeddedId} as a plain column.
 */
@Entity
@Table(name = "loan_case")
@FilterDef(name = "tenantFilter", parameters = @ParamDef(name = "tenantId", type = Long.class))
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
public class LoanCase implements TenantAware {

    @EmbeddedId
    private LoanCaseId id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LoanCaseStatus status = LoanCaseStatus.NEW;

    @Column(name = "assigned_to")
    private String assignedTo;

    @Column(nullable = false)
    private boolean flagged = false;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected LoanCase() {
        // required by JPA
    }

    public LoanCase(LoanCaseId id) {
        this.id = id;
        this.updatedAt = Instant.now();
    }

    public LoanCaseId getId() {
        return id;
    }

    public String getLoanId() {
        return id.getLoanId();
    }

    @Override
    public Long getTenantId() {
        return id.getTenantId();
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
