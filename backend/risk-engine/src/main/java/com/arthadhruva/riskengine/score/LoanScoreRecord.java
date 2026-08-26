package com.arthadhruva.riskengine.score;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * The durable, current record of a loan's most recently computed score -- unlike the Redis
 * cache in {@link ScoreController} (fast, but expires after 24h), this is what a client's
 * own-loan view is backed by: a borrower checking their loan status weeks after it was scored
 * needs a record that doesn't expire. One row per loanId, upserted on every scoring call that
 * supplies one.
 */
@Entity
@Table(name = "loan_score")
public class LoanScoreRecord {

    @Id
    @Column(name = "loan_id")
    private String loanId;

    @Column(name = "raw_probability", nullable = false)
    private double rawProbability;

    @Column(name = "calibrated_probability", nullable = false)
    private double calibratedProbability;

    @Column(name = "computed_at", nullable = false)
    private Instant computedAt;

    protected LoanScoreRecord() {
        // required by JPA
    }

    public LoanScoreRecord(String loanId, double rawProbability, double calibratedProbability, Instant computedAt) {
        this.loanId = loanId;
        this.rawProbability = rawProbability;
        this.calibratedProbability = calibratedProbability;
        this.computedAt = computedAt;
    }

    public String getLoanId() {
        return loanId;
    }

    public double getRawProbability() {
        return rawProbability;
    }

    public double getCalibratedProbability() {
        return calibratedProbability;
    }

    public Instant getComputedAt() {
        return computedAt;
    }

    public void update(double rawProbability, double calibratedProbability, Instant computedAt) {
        this.rawProbability = rawProbability;
        this.calibratedProbability = calibratedProbability;
        this.computedAt = computedAt;
    }
}
