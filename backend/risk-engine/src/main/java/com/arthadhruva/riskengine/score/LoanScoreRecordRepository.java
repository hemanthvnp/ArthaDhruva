package com.arthadhruva.riskengine.score;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LoanScoreRecordRepository extends JpaRepository<LoanScoreRecord, LoanScoreId> {
    /** Every loan a tenant has scored so far, most recent first -- the portfolio view. Spring
     * Data reaches into the embedded id's own fields via the {@code Id<Field>} property-path
     * convention ({@code id.tenantId} -> {@code IdTenantId}). */
    Page<LoanScoreRecord> findAllByIdTenantIdOrderByComputedAtDesc(Long tenantId, Pageable pageable);
}
