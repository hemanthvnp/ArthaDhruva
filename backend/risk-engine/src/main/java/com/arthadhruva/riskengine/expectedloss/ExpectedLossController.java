package com.arthadhruva.riskengine.expectedloss;

import com.arthadhruva.riskengine.score.LoanFeatures;
import com.arthadhruva.riskengine.score.ModelService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * PD x LGD x EAD, the real Expected Loss framework from lgd_ead_expected_loss.ipynb, combining
 * the existing PD model (ModelService) with the newly-exported LGD model (LgdPredictor).
 *
 * EAD is not a fitted model -- the notebook defines it as last_actual_upb, a value that only
 * exists retroactively once a loan has defaulted and been liquidated. For a loan being scored at
 * origination, no such value exists yet, so this uses original_upb as a stated simplification
 * (current exposure ~= original balance), the same kind of honestly-flagged limitation the
 * notebook itself uses throughout rather than a silently-asserted exact figure.
 */
@RestController
public class ExpectedLossController {

    private final ModelService modelService;
    private final LgdPredictor lgdPredictor;

    public ExpectedLossController(ModelService modelService, LgdPredictor lgdPredictor) {
        this.modelService = modelService;
        this.lgdPredictor = lgdPredictor;
    }

    @PostMapping("/expected-loss")
    public ExpectedLossResponse expectedLoss(@Valid @RequestBody LoanFeatures loan) {
        double pd = modelService.score(loan).calibratedProbability();
        double lgd = lgdPredictor.predict(loan);
        double ead = loan.originalUpb();
        return new ExpectedLossResponse(pd, lgd, ead, pd * lgd * ead);
    }
}
