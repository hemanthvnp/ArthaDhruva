package com.arthadhruva.riskengine.workflow;

import com.arthadhruva.riskengine.tenant.TenantAware;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;

import java.time.Instant;

/** One immutable entry in a case's history: who changed what, from what, to what, and why. The
 * application role has no UPDATE or DELETE on this table (V26). */
@Entity
@Table(name = "loan_case_event")
@FilterDef(name = "tenantFilter", parameters = @ParamDef(name = "tenantId", type = Long.class))
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
public class LoanCaseEvent implements TenantAware {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "loan_id", nullable = false)
    private String loanId;

    @Column(nullable = false)
    private String actor;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status")
    private LoanCaseStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false)
    private LoanCaseStatus toStatus;

    @Column(name = "from_assignee")
    private String fromAssignee;

    @Column(name = "to_assignee")
    private String toAssignee;

    @Column(name = "from_flagged")
    private Boolean fromFlagged;

    @Column(name = "to_flagged", nullable = false)
    private boolean toFlagged;

    @Column
    private String reason;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected LoanCaseEvent() {
        // required by JPA
    }

    LoanCaseEvent(Long tenantId, String loanId, String actor, LoanCase.State before, LoanCase.State after, String reason,
                  boolean existedBefore) {
        this.tenantId = tenantId;
        this.loanId = loanId;
        this.actor = actor;
        this.fromStatus = existedBefore ? before.status() : null;
        this.fromAssignee = existedBefore ? before.assignedTo() : null;
        this.fromFlagged = existedBefore ? before.flagged() : null;
        this.toStatus = after.status();
        this.toAssignee = after.assignedTo();
        this.toFlagged = after.flagged();
        this.reason = reason;
        this.occurredAt = Instant.now();
    }

    public Long getId() { return id; }
    @Override public Long getTenantId() { return tenantId; }
    public String getLoanId() { return loanId; }
    public String getActor() { return actor; }
    public LoanCaseStatus getFromStatus() { return fromStatus; }
    public LoanCaseStatus getToStatus() { return toStatus; }
    public String getFromAssignee() { return fromAssignee; }
    public String getToAssignee() { return toAssignee; }
    public Boolean getFromFlagged() { return fromFlagged; }
    public boolean isToFlagged() { return toFlagged; }
    public String getReason() { return reason; }
    public Instant getOccurredAt() { return occurredAt; }
}
