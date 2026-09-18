package com.arthadhruva.riskengine.workflow;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LoanNoteRepository extends JpaRepository<LoanNote, Long> {
    List<LoanNote> findByLoanIdOrderByCreatedAtDesc(String loanId);

    List<LoanNote> findTop50ByOrderByCreatedAtDesc();
}
