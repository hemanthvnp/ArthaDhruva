package com.arthadhruva.riskengine.event;

/** Published whenever a loan's durable score record is written (see
 * {@code score.LoanScoreService#upsert}) -- the trigger event for future risk-threshold
 * automation rules ("flag when calibrated risk > X") and usage metering. */
public class LoanScoredEvent extends DomainEvent {

    private final String loanId;
    private final double rawProbability;
    private final double calibratedProbability;

    public LoanScoredEvent(Long tenantId, String loanId, double rawProbability, double calibratedProbability) {
        super(tenantId);
        this.loanId = loanId;
        this.rawProbability = rawProbability;
        this.calibratedProbability = calibratedProbability;
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
}
