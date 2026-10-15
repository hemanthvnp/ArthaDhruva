package com.arthadhruva.riskengine.score;

/**
 * The coarse band a calibrated 24-month default probability falls in. The thresholds are the console's
 * ({@code frontend/src/components/risk.ts}, {@code riskBand}): under 2% is low, under 10% medium, beyond that
 * high. A borrower is shown only the band and its {@link #borrowerLabel()}, never the probability.
 */
public enum RiskBand {
    LOW("Good standing"), MEDIUM("Needs attention"), HIGH("Elevated risk");

    static final double MEDIUM_FROM = 0.02;
    static final double HIGH_FROM = 0.10;

    private final String borrowerLabel;

    RiskBand(String borrowerLabel) {
        this.borrowerLabel = borrowerLabel;
    }

    public static RiskBand of(double calibratedProbability) {
        if (calibratedProbability < MEDIUM_FROM) {
            return LOW;
        }
        return calibratedProbability < HIGH_FROM ? MEDIUM : HIGH;
    }

    public String borrowerLabel() {
        return borrowerLabel;
    }
}
