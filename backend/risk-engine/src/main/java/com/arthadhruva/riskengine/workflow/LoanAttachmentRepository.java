package com.arthadhruva.riskengine.workflow;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LoanAttachmentRepository extends JpaRepository<LoanAttachment, Long> {
    List<LoanAttachment> findByTenantIdAndLoanIdOrderByUploadedAtDesc(Long tenantId, String loanId);

    Optional<LoanAttachment> findByIdAndTenantIdAndLoanId(Long id, Long tenantId, String loanId);
}
