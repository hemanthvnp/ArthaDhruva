package com.arthadhruva.riskengine.score;

import org.springframework.data.jpa.repository.JpaRepository;

public interface LoanScoreRecordRepository extends JpaRepository<LoanScoreRecord, String> {
}
