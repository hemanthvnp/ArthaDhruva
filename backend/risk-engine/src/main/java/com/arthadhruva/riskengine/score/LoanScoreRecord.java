package com.arthadhruva.riskengine.score;

import com.arthadhruva.riskengine.tenant.TenantAware;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

    /** Which model produced this score (null for scores recorded before versioning existed). */
    @Column(name = "model_version")
    private String modelVersion;

    /** The band of {@code calibratedProbability}, since when the loan has been in it, and the band it was in
     * before (V30). Null on rows not rescored since that migration; {@code previousBand} is also null while
     * the loan has only ever been in one band. */
    @Enumerated(EnumType.STRING)
    @Column(name = "band")
    private RiskBand band;

    @Column(name = "band_since")
    private Instant bandSince;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_band")
    private RiskBand previousBand;

    protected LoanScoreRecord() {
        // required by JPA
    }

    public LoanScoreRecord(LoanScoreId id, double rawProbability, double calibratedProbability, Instant computedAt,
                           String modelVersion) {
        this.id = id;
        this.rawProbability = rawProbability;
        this.calibratedProbability = calibratedProbability;
        this.computedAt = computedAt;
        this.modelVersion = modelVersion;
        this.band = RiskBand.of(calibratedProbability);
        this.bandSince = computedAt;
    }

    public String getModelVersion() {
        return modelVersion;
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

    public RiskBand getBand() {
        return band;
    }

    public Instant getBandSince() {
        return bandSince;
    }

    public RiskBand getPreviousBand() {
        return previousBand;
    }

    public void update(double rawProbability, double calibratedProbability, Instant computedAt, String modelVersion) {
        RiskBand next = RiskBand.of(calibratedProbability);
        if (band == null) {
            // First score since V30: what came before is unknown, so no previous band is claimed.
            bandSince = computedAt;
        } else if (next != band) {
            previousBand = band;
            bandSince = computedAt;
        }
        this.band = next;
        this.rawProbability = rawProbability;
        this.calibratedProbability = calibratedProbability;
        this.computedAt = computedAt;
        this.modelVersion = modelVersion;
    }
}
