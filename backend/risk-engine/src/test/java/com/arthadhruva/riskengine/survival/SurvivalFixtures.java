package com.arthadhruva.riskengine.survival;

import com.arthadhruva.riskengine.ml.MarketData;
import com.arthadhruva.riskengine.score.LoanFeatures;
import com.arthadhruva.riskengine.score.LoanInputValidator;

/** The real model and market data, loaded once for every test in this package (the ONNX graph is 16 MB). */
final class SurvivalFixtures {

    static final double HAIRCUT = 0.15;
    static final SurvivalModel MODEL;
    static final MarketData MARKET;
    static final LoanInputValidator VALIDATOR;
    static final TermStructureEngine ENGINE;

    static {
        try {
            MODEL = new SurvivalModel(true);
            MARKET = new MarketData();
            VALIDATOR = new LoanInputValidator(MARKET);
            ENGINE = new TermStructureEngine(MODEL, MARKET, new LgdPredictor(), HAIRCUT, 2.0, 0.005);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private SurvivalFixtures() {
    }

    /** A new 30-year prime purchase loan. */
    static LoanFeatures prime() {
        return loan("prime", 760, 75.0, 6.5, 360, 0.0, "CA", null);
    }

    static LoanFeatures loan(String id, int credit, double ltv, double rate, int term, double mi, String state, String origination) {
        return new LoanFeatures(id, credit, 36.0, 300000.0, ltv, ltv, rate, term, 2, 1, mi, "P", "SF", "P", "R", "N", state, origination);
    }

    static TermStructureEngine.Request request(LoanFeatures loan, Scenario scenario) {
        return new TermStructureEngine.Request(loan, scenario, null, null, null, null);
    }
}
