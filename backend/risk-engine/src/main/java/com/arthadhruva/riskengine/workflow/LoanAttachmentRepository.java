package com.arthadhruva.riskengine.workflow;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface LoanAttachmentRepository extends JpaRepository<LoanAttachment, Long> {
    List<LoanAttachment> findByTenantIdAndLoanIdOrderByUploadedAtDesc(Long tenantId, String loanId);

    Optional<LoanAttachment> findByIdAndTenantIdAndLoanId(Long id, Long tenantId, String loanId);

    /** Bytes stored by one tenant, for the storage quota. */
    @Query("select coalesce(sum(a.sizeBytes), 0) from LoanAttachment a where a.tenantId = :tenantId")
    long totalBytes(@Param("tenantId") Long tenantId);
}
