package com.arthadhruva.riskengine.score;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;

/**
 * Composite primary key for {@link LoanScoreRecord}: {@code loanId} alone used to be the whole
 * key, back when only one tenant existed -- two tenants scoring their own "loan L123" would
 * otherwise collide on a shared row. Making tenant part of the identity itself (rather than just
 * a filtered-on column) means even a raw, filter-bypassing lookup by loan id alone is a compile
 * error, not just a scoping bug waiting to happen.
 */
@Embeddable
public class LoanScoreId implements Serializable {

    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(name = "loan_id")
    private String loanId;

    protected LoanScoreId() {
        // required by JPA
    }

    public LoanScoreId(Long tenantId, String loanId) {
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
        if (!(o instanceof LoanScoreId that)) return false;
        return Objects.equals(tenantId, that.tenantId) && Objects.equals(loanId, that.loanId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tenantId, loanId);
    }
}
