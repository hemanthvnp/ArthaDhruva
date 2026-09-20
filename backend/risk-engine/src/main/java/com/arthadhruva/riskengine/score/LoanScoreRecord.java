package com.arthadhruva.riskengine.score;

import com.arthadhruva.riskengine.tenant.TenantAware;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;

import java.time.Instant;

/**
 * The durable, current record of a loan's most recently computed score -- unlike the Redis
 * cache in {@link ScoreController} (fast, but expires after 24h), this is what a client's
 * own-loan view is backed by: a borrower checking their loan status weeks after it was scored
 * needs a record that doesn't expire. One row per {@code (tenantId, loanId)}, upserted on every
 * scoring call that supplies a loanId.
 *
 * <p>{@code tenantFilter}'s condition references the {@code tenant_id} column directly -- the
 * same physical column whether it's a top-level field (as on {@code User}) or, as here, part of
 * an {@link EmbeddedId}; the filter operates at the SQL level, not the Java property path. See
 * {@code security.User}'s class doc for why this filter is a backstop, not the primary guard.
 */
@Entity
@Table(name = "loan_score")
@FilterDef(name = "tenantFilter", parameters = @ParamDef(name = "tenantId", type = Long.class))
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
public class LoanScoreRecord implements TenantAware {

    @EmbeddedId
    private LoanScoreId id;

    @Column(name = "raw_probability", nullable = false)
    private double rawProbability;

    @Column(name = "calibrated_probability", nullable = false)
    private double calibratedProbability;

    @Column(name = "computed_at", nullable = false)
    private Instant computedAt;

    protected LoanScoreRecord() {
        // required by JPA
    }

    public LoanScoreRecord(LoanScoreId id, double rawProbability, double calibratedProbability, Instant computedAt) {
        this.id = id;
        this.rawProbability = rawProbability;
        this.calibratedProbability = calibratedProbability;
        this.computedAt = computedAt;
    }

    public LoanScoreId getId() {
        return id;
    }

    public String getLoanId() {
        return id.getLoanId();
    }

    @Override
    public Long getTenantId() {
        return id.getTenantId();
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
