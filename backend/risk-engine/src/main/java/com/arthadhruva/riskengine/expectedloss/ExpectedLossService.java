package com.arthadhruva.riskengine.expectedloss;

import com.arthadhruva.riskengine.score.LoanFeatures;
import com.arthadhruva.riskengine.score.ModelService;
import org.springframework.stereotype.Service;

/**
 * PD x LGD x EAD, the real Expected Loss framework from lgd_ead_expected_loss.ipynb, combining
 * the existing PD model ({@code score.ModelService}, a legitimate cross-module service-to-service
 * dependency -- expected loss inherently needs a default probability) with the locally-owned
 * {@link LgdPredictor}.
 *
 * EAD is not a fitted model -- the notebook defines it as last_actual_upb, a value that only
 * exists retroactively once a loan has defaulted and been liquidated. For a loan being scored at
 * origination, no such value exists yet, so this uses original_upb as a stated simplification
 * (current exposure ~= original balance), the same kind of honestly-flagged limitation the
 * notebook itself uses throughout rather than a silently-asserted exact figure.
 */
@Service
public class ExpectedLossService {

    private final ModelService modelService;
    private final LgdPredictor lgdPredictor;

    public ExpectedLossService(ModelService modelService, LgdPredictor lgdPredictor) {
        this.modelService = modelService;
        this.lgdPredictor = lgdPredictor;
    }

    public ExpectedLossResponse compute(LoanFeatures loan) {
        double pd = modelService.score(loan).calibratedProbability();
        double lgd = lgdPredictor.predict(loan);
        double ead = loan.originalUpb();
        return new ExpectedLossResponse(pd, lgd, ead, pd * lgd * ead);
    }
}
