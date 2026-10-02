package com.arthadhruva.riskengine.workflow;

import com.arthadhruva.riskengine.tenant.TenantAware;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;

import java.time.Instant;

/**
 * Human case-tracking state for a loan: who owns the follow-up, whether it is flagged, and where it sits
 * in the review lifecycle. Never computed, only set by a person (or an automation rule acting on a
 * person's configuration). One row per (tenant, loan), created lazily on first change.
 *
 * <p>{@code version} is exposed to clients: an update carries the version it was based on and is refused
 * if the case changed in between, so two analysts cannot silently overwrite each other.
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

    /** Who moved the case to ESCALATED; cleared when it leaves that status. */
    @Column(name = "escalated_by")
    private String escalatedBy;

    @Version
    private long version;

    protected LoanCase() {
        // required by JPA
    }

    public LoanCase(LoanCaseId id) {
        this.id = id;
        this.updatedAt = Instant.now();
    }

    /** The mutable part of a case, as one value. */
    public record State(LoanCaseStatus status, String assignedTo, boolean flagged) {
    }

    public State state() {
        return new State(status, assignedTo, flagged);
    }

    void apply(State next, String actor) {
        if (next.status() == LoanCaseStatus.ESCALATED && status != LoanCaseStatus.ESCALATED) {
            this.escalatedBy = actor;
        } else if (next.status() != LoanCaseStatus.ESCALATED) {
            this.escalatedBy = null;
        }
        this.status = next.status();
        this.assignedTo = next.assignedTo();
        this.flagged = next.flagged();
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

    public String getEscalatedBy() {
        return escalatedBy;
    }

    public long getVersion() {
        return version;
    }
}
