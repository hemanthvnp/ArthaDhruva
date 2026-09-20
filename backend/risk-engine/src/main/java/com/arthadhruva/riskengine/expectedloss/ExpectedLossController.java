package com.arthadhruva.riskengine.expectedloss;

import com.arthadhruva.riskengine.score.LoanFeatures;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ExpectedLossController {

    private final ExpectedLossService expectedLossService;

    public ExpectedLossController(ExpectedLossService expectedLossService) {
        this.expectedLossService = expectedLossService;
    }

    @PostMapping("/expected-loss")
    public ExpectedLossResponse expectedLoss(@Valid @RequestBody LoanFeatures loan) {
        return expectedLossService.compute(loan);
    }
}
