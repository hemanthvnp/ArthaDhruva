package com.arthadhruva.riskengine.workflow;

import org.springframework.data.jpa.repository.JpaRepository;

public interface LoanCaseRepository extends JpaRepository<LoanCase, String> {
}
