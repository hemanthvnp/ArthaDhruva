package com.arthadhruva.riskengine.workflow;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

interface LoanCaseEventRepository extends JpaRepository<LoanCaseEvent, Long> {

    List<LoanCaseEvent> findByTenantIdAndLoanIdOrderByOccurredAtDesc(Long tenantId, String loanId, Pageable pageable);
}
