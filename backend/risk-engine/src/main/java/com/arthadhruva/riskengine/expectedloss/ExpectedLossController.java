package com.arthadhruva.riskengine.expectedloss;

import com.arthadhruva.riskengine.audit.AuditContext;
import com.arthadhruva.riskengine.billing.Metered;
import com.arthadhruva.riskengine.score.LoanFeatures;
import com.arthadhruva.riskengine.survival.TermStructureEngine;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class ExpectedLossController {

    private final ExpectedLossService expectedLossService;

    public ExpectedLossController(ExpectedLossService expectedLossService) {
        this.expectedLossService = expectedLossService;
    }

    @Metered("EXPECTED_LOSS")
    @PostMapping("/expected-loss")
    public ExpectedLossResponse expectedLoss(@Valid @RequestBody LoanFeatures loan) {
        AuditContext.modelVersion(expectedLossService.modelVersion());
        return expectedLossService.compute(loan);
    }

    @ExceptionHandler(TermStructureEngine.InvalidProjectionException.class)
    public ResponseEntity<Map<String, String>> onInvalidProjection(TermStructureEngine.InvalidProjectionException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).body(Map.of("error", e.getMessage()));
    }
}
