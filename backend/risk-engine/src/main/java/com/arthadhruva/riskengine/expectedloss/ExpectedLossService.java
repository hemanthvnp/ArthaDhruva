package com.arthadhruva.riskengine.expectedloss;

import com.arthadhruva.riskengine.score.LoanFeatures;
import com.arthadhruva.riskengine.score.LoanInputValidator;
import com.arthadhruva.riskengine.survival.DelinquencyHistory;
import com.arthadhruva.riskengine.survival.RiskProjectionService;
import com.arthadhruva.riskengine.survival.Scenario;
import com.arthadhruva.riskengine.survival.SurvivalModel;
import com.arthadhruva.riskengine.survival.TermStructureEngine;
import org.springframework.stereotype.Service;

/**
 * Expected credit loss for one loan in one call: the headline numbers of the baseline term structure.
 *
 * <p>This used to be PD x LGD x EAD with the 24-month origination PD and the original balance as
 * exposure. Both were wrong for anything but a brand-new loan: a seasoned loan owes less than it
 * borrowed, and its risk over the next year is not its risk at origination. The numbers now come from
 * the survival engine: exposure is the amortized balance, the 12-month figure sums the discounted loss
 * of each of the next twelve months, and the lifetime figure runs to maturity.
 */
@Service
public class ExpectedLossService {

    private final TermStructureEngine engine;
    private final SurvivalModel model;
    private final LoanInputValidator validator;
    private final RiskProjectionService projections;

    public ExpectedLossService(TermStructureEngine engine, SurvivalModel model, LoanInputValidator validator,
                               RiskProjectionService projections) {
        this.engine = engine;
        this.model = model;
        this.validator = validator;
        this.projections = projections;
    }

    public ExpectedLossResponse compute(LoanFeatures request) {
        LoanFeatures loan = request.normalized();
        LoanInputValidator.Result checked = validator.check(loan);
        TermStructureEngine.TermStructure t = engine.project(
                new TermStructureEngine.Request(loan, Scenario.BASELINE, null, null, null, null), 1);
        return new ExpectedLossResponse(t.summary().pd12m(), t.ecl().lgd(), t.ecl().exposure(), t.ecl().ecl12m(),
                t.summary().pdLifetime(), t.ecl().eclLifetime(), t.ecl().stage(), t.ecl().eclIfrs9(),
                t.summary().expectedLifeMonths(), model.version(), checked.warnings());
    }

    /**
     * As {@link #compute}, for a loan whose current status is known: given its days past due and payment history the
     * loan is projected from where it is by the delinquency model, otherwise by the existing engine, and the response
     * says which ran. The routing is {@link RiskProjectionService}'s, the one the term-structure endpoint and portfolio
     * runs use, so the three agree.
     */
    public ExpectedLossResponse computeFromStatus(LoanFeatures request, Integer daysPastDue, DelinquencyHistory history) {
        LoanFeatures loan = request.normalized();
        LoanInputValidator.Result checked = validator.check(loan);
        RiskProjectionService.Result projected = projections.project(
                new TermStructureEngine.Request(loan, Scenario.BASELINE, null, null, daysPastDue, null), 1, history,
                RiskProjectionService.IntervalOptions.NONE);
        TermStructureEngine.TermStructure t = projected.termStructure();
        return new ExpectedLossResponse(t.summary().pd12m(), t.ecl().lgd(), t.ecl().exposure(), t.ecl().ecl12m(),
                t.summary().pdLifetime(), t.ecl().eclLifetime(), t.ecl().stage(), t.ecl().eclIfrs9(),
                t.summary().expectedLifeMonths(), projected.modelUsed(), checked.warnings(), projected.modelUsed(),
                projected.fallbackReason());
    }

    public String modelVersion() {
        return model.version();
    }
}
