package com.arthadhruva.riskengine.expectedloss;

import com.arthadhruva.riskengine.audit.AuditContext;
import com.arthadhruva.riskengine.billing.Metered;
import com.arthadhruva.riskengine.score.LoanFeatures;
import com.arthadhruva.riskengine.survival.DelinquencyHistory;
import com.arthadhruva.riskengine.survival.DelinquencyRouter;
import com.arthadhruva.riskengine.survival.RiskProjectionService;
import com.arthadhruva.riskengine.survival.TermStructureEngine;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
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
    private final RiskProjectionService projections;

    public ExpectedLossController(ExpectedLossService expectedLossService, RiskProjectionService projections) {
        this.expectedLossService = expectedLossService;
        this.projections = projections;
    }

    /**
     * @param loan        the loan's origination features, as the bare {@code /expected-loss} body
     * @param daysPastDue how many days past due the loan is now
     * @param delinquency its recent payment history; with {@code daysPastDue} it selects the delinquency model (see
     *                    {@link DelinquencyRouter}); omitted or incomplete, the existing engine runs and the response says why
     */
    public record StatusRequest(@NotNull @Valid LoanFeatures loan, @Min(0) @Max(3650) Integer daysPastDue,
                                @Valid DelinquencyHistory delinquency) {
    }

    @Metered("EXPECTED_LOSS")
    @PostMapping("/expected-loss")
    public ExpectedLossResponse expectedLoss(@Valid @RequestBody LoanFeatures loan) {
        AuditContext.modelVersion(expectedLossService.modelVersion());
        return expectedLossService.compute(loan);
    }

    /**
     * The same figures for a loan whose status is known. A separate route, so the bare-loan body of
     * {@code /expected-loss} and its error messages stay exactly as they were.
     */
    @Metered("EXPECTED_LOSS")
    @PostMapping("/expected-loss/from-status")
    public ExpectedLossResponse expectedLossFromStatus(@Valid @RequestBody StatusRequest request) {
        AuditContext.modelVersion(projections.modelUsed(projections.route(request.daysPastDue(), request.delinquency())));
        return expectedLossService.computeFromStatus(request.loan(), request.daysPastDue(), request.delinquency());
    }

    @ExceptionHandler(DelinquencyRouter.InvalidDelinquencyInputException.class)
    public ResponseEntity<Map<String, String>> onInvalidDelinquencyInput(DelinquencyRouter.InvalidDelinquencyInputException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage(), "field", e.field()));
    }

    @ExceptionHandler(TermStructureEngine.InvalidProjectionException.class)
    public ResponseEntity<Map<String, String>> onInvalidProjection(TermStructureEngine.InvalidProjectionException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).body(Map.of("error", e.getMessage()));
    }
}
