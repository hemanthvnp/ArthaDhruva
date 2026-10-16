package com.arthadhruva.riskengine.roughset;

import com.arthadhruva.riskengine.ml.MarketData;
import com.arthadhruva.riskengine.score.LoanFeatures;
import com.arthadhruva.riskengine.survival.LgdPredictor;
import com.arthadhruva.riskengine.survival.Scenario;
import com.arthadhruva.riskengine.survival.SurvivalModel;
import com.arthadhruva.riskengine.survival.TermStructureEngine;

/** The real model and market data, loaded once for every test in this package (the ONNX graph is 16 MB). */
final class RoughSetFixtures {

    static final TermStructureEngine ENGINE;
    static final DecisionService SERVICE;

    static {
        try {
            ENGINE = new TermStructureEngine(new SurvivalModel(true), new MarketData(), new LgdPredictor(), 0.15, 2.0, 0.005);
            SERVICE = new DecisionService(ENGINE);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private RoughSetFixtures() {
    }

    /** A new 30-year prime purchase loan. */
    static LoanFeatures prime() {
        return new LoanFeatures("prime", 760, 36.0, 300000.0, 75.0, 75.0, 6.5, 360, 2, 1, 0.0, "P", "SF", "P", "R", "N", "CA", null);
    }

    /** A weaker, more leveraged loan whose stressed risk sits well above the prime loan's. */
    static LoanFeatures weak() {
        return new LoanFeatures("weak", 640, 46.0, 300000.0, 95.0, 95.0, 7.5, 360, 1, 1, 25.0, "P", "SF", "P", "R", "N", "FL", null);
    }

    static TermStructureEngine.Request request(LoanFeatures loan, Scenario scenario) {
        return new TermStructureEngine.Request(loan, scenario, null, null, null, null);
    }

    static DecisionOptions.Resolved perturbation(RoughSetMetric metric, double threshold, double tolerance, int members, Long seed) {
        return new DecisionOptions(metric.name(), threshold, tolerance,
                new DecisionOptions.Granule("PERTURBATION", members, null, null, null, null), seed)
                .resolve(DecisionOptions.DEFAULT_MEMBERS, DecisionOptions.MAX_MEMBERS);
    }
}
