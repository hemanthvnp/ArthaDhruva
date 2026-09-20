package com.arthadhruva.riskengine.workflow;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LoanNoteRepository extends JpaRepository<LoanNote, Long>,
        org.springframework.data.jpa.repository.JpaSpecificationExecutor<LoanNote> {
    List<LoanNote> findByTenantIdAndLoanIdOrderByCreatedAtDesc(Long tenantId, String loanId);

    List<LoanNote> findTop50ByTenantIdOrderByCreatedAtDesc(Long tenantId);
}
