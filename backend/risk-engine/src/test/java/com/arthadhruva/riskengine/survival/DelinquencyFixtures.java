package com.arthadhruva.riskengine.survival;

/** The delinquency model and the multi-state engine over the shared survival fixtures, loaded once per test JVM. */
final class DelinquencyFixtures {

    static final DelinquencyModel MODEL;
    static final MultiStateEngine ENGINE;
    static final DelinquencyRouter ROUTER;

    static {
        try {
            MODEL = new DelinquencyModel(SurvivalFixtures.MODEL);
            ENGINE = new MultiStateEngine(SurvivalFixtures.ENGINE, SurvivalFixtures.MODEL, MODEL, SurvivalFixtures.MARKET,
                    SurvivalFixtures.HAIRCUT, 2.0, 0.005);
            ROUTER = new DelinquencyRouter(SurvivalFixtures.MODEL, MODEL);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private DelinquencyFixtures() {
    }

    static final DelinquencyModel.History CLEAN = new DelinquencyModel.History(0, 13, 0);
    static final DelinquencyModel.History RECENT = new DelinquencyModel.History(2, 3, 1);
    static final DelinquencyModel.History DEEP = new DelinquencyModel.History(1, 13, 2);

    static MultiStateEngine.Request request(com.arthadhruva.riskengine.score.LoanFeatures loan, Scenario scenario, int daysPastDue,
                                            DelinquencyModel.History history) {
        int start = daysPastDue < 30 ? 0 : daysPastDue < 60 ? 1 : 2;
        return new MultiStateEngine.Request(new TermStructureEngine.Request(loan, scenario, null, null, daysPastDue, null), start, history);
    }
}
