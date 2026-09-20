package com.arthadhruva.riskengine.workflow;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LoanCaseRepository extends JpaRepository<LoanCase, LoanCaseId>,
        org.springframework.data.jpa.repository.JpaSpecificationExecutor<LoanCase> {
    /** Every case in a tenant that's ever had status/assignment/flag set explicitly, most
     * recently updated first. */
    Page<LoanCase> findAllByIdTenantIdOrderByUpdatedAtDesc(Long tenantId, Pageable pageable);
}
