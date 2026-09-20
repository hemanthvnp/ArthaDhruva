package com.arthadhruva.riskengine.workflow;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;

/** Composite primary key for {@link LoanCase} -- same reasoning as {@code score.LoanScoreId}:
 * {@code loanId} alone stopped being a safe global key the moment a second tenant could exist. */
@Embeddable
public class LoanCaseId implements Serializable {

    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(name = "loan_id")
    private String loanId;

    protected LoanCaseId() {
        // required by JPA
    }

    public LoanCaseId(Long tenantId, String loanId) {
        this.tenantId = tenantId;
        this.loanId = loanId;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public String getLoanId() {
        return loanId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof LoanCaseId that)) return false;
        return Objects.equals(tenantId, that.tenantId) && Objects.equals(loanId, that.loanId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tenantId, loanId);
    }
}
