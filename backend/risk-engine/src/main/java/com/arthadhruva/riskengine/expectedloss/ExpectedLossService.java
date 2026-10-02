package com.arthadhruva.riskengine.expectedloss;

import com.arthadhruva.riskengine.score.LoanFeatures;
import com.arthadhruva.riskengine.score.LoanInputValidator;
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

    public ExpectedLossService(TermStructureEngine engine, SurvivalModel model, LoanInputValidator validator) {
        this.engine = engine;
        this.model = model;
        this.validator = validator;
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

    public String modelVersion() {
        return model.version();
    }
}
