package com.arthadhruva.riskengine.survival;

import com.arthadhruva.riskengine.audit.AuditContext;
import com.arthadhruva.riskengine.billing.Metered;
import com.arthadhruva.riskengine.score.LoanFeatures;
import com.arthadhruva.riskengine.score.LoanInputValidator;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Lifetime credit risk for one loan: the month-by-month default / prepayment / survival curve under a
 * macro scenario, and the expected credit loss that follows from it (IFRS 9 staging and CECL).
 */
@RestController
public class SurvivalController {

    private static final int COMPARE_DEFAULT_MONTHS = 120;
    /** Above this multiple of the original balance, a reported balance is an input error, not capitalized arrears. */
    private static final double MAX_BALANCE_MULTIPLE = 1.5;

    private final TermStructureEngine engine;
    private final SurvivalModel model;
    private final LoanInputValidator validator;

    public SurvivalController(TermStructureEngine engine, SurvivalModel model, LoanInputValidator validator) {
        this.engine = engine;
        this.model = model;
        this.validator = validator;
    }

    /**
     * @param scenario       a built-in scenario name (default BASELINE); see {@code GET /risk/scenarios}
     * @param customScenario a caller-defined macro path, instead of {@code scenario}
     * @param monthsOnBook   the loan's age when {@code loan.originationMonth} is not given
     * @param months         how many months of the series to return (default: to maturity). Summary and ECL
     *                       always cover the loan's whole remaining life.
     */
    public record TermStructureRequest(
            @NotNull @Valid LoanFeatures loan,
            @Size(max = 30) String scenario,
            @Valid Scenario customScenario,
            @Min(0) @Max(480) Integer monthsOnBook,
            @Positive Double currentBalance,
            @Min(0) @Max(3650) Integer daysPastDue,
            @DecimalMin("0") @DecimalMax("1") Double originationPd12m,
            @Min(1) @Max(480) Integer months
    ) {
    }

    public record TermStructureResponse(TermStructureEngine.TermStructure termStructure, List<String> warnings) {
    }

    /** @param scenarios built-in scenario names to compare (default: all of them) */
    public record CompareRequest(
            @NotNull @Valid LoanFeatures loan,
            @Size(min = 1, max = 5) List<@Size(max = 30) String> scenarios,
            @Min(0) @Max(480) Integer monthsOnBook,
            @Positive Double currentBalance,
            @Min(0) @Max(3650) Integer daysPastDue,
            @DecimalMin("0") @DecimalMax("1") Double originationPd12m,
            @Min(1) @Max(480) Integer months
    ) {
    }

    public record ScenarioCurve(String scenario, String description, TermStructureEngine.Summary summary,
                                TermStructureEngine.Ecl ecl, double[] cumulativeDefault, double[] cumulativePrepay,
                                double[] survival, double[] discountedExpectedLoss,
                                List<TermStructureEngine.Extrapolation> extrapolations) {
    }

    public record CompareResponse(String modelVersion, int monthsOnBook, int remainingMonths, List<String> months,
                                  List<ScenarioCurve> scenarios, List<String> warnings) {
    }

    @Metered("TERM_STRUCTURE")
    @PostMapping("/risk/term-structure")
    public TermStructureResponse termStructure(@Valid @RequestBody TermStructureRequest request) {
        if (request.scenario() != null && request.customScenario() != null) {
            throw new TermStructureEngine.InvalidProjectionException("Give a scenario name or a customScenario, not both");
        }
        LoanFeatures loan = request.loan().normalized();
        List<String> warnings = check(loan, request.currentBalance(), request.daysPastDue());
        Scenario scenario = request.customScenario() != null ? request.customScenario() : scenario(request.scenario());
        AuditContext.modelVersion(model.version());
        TermStructureEngine.TermStructure result = engine.project(new TermStructureEngine.Request(loan, scenario,
                        request.monthsOnBook(), request.currentBalance(), request.daysPastDue(), request.originationPd12m()),
                request.months() == null ? TermStructureEngine.MAX_MONTHS : request.months());
        return new TermStructureResponse(result, warnings);
    }

    /** The same loan under several scenarios, evaluated in one batched model call. */
    @Metered("TERM_STRUCTURE")
    @PostMapping("/risk/term-structure/compare")
    public CompareResponse compare(@Valid @RequestBody CompareRequest request) {
        LoanFeatures loan = request.loan().normalized();
        List<String> warnings = check(loan, request.currentBalance(), request.daysPastDue());
        List<Scenario> scenarios = request.scenarios() == null ? Scenario.BUILT_IN
                : request.scenarios().stream().distinct().map(SurvivalController::scenario).toList();
        AuditContext.modelVersion(model.version());
        List<TermStructureEngine.Request> requests = new ArrayList<>(scenarios.size());
        for (Scenario scenario : scenarios) {
            requests.add(new TermStructureEngine.Request(loan, scenario, request.monthsOnBook(), request.currentBalance(),
                    request.daysPastDue(), request.originationPd12m()));
        }
        List<TermStructureEngine.Outcome> outcomes = engine.projectAll(requests);
        int months = Math.min(request.months() == null ? COMPARE_DEFAULT_MONTHS : request.months(), outcomes.get(0).months());
        List<ScenarioCurve> curves = new ArrayList<>(outcomes.size());
        for (int i = 0; i < outcomes.size(); i++) {
            TermStructureEngine.Outcome o = outcomes.get(i);
            curves.add(new ScenarioCurve(scenarios.get(i).name(), scenarios.get(i).description(), o.summary(), o.ecl(),
                    o.cumulativeDefault(months), o.cumulativePrepay(months), o.survival(months), o.discountedLoss(months),
                    engine.extrapolations(o)));
        }
        TermStructureEngine.Outcome first = outcomes.get(0);
        return new CompareResponse(model.version(), first.monthsOnBook(), first.months(), first.monthLabels(months), curves, warnings);
    }

    @GetMapping("/risk/scenarios")
    public List<Scenario> scenarios() {
        return Scenario.BUILT_IN;
    }

    private List<String> check(LoanFeatures loan, Double currentBalance, Integer daysPastDue) {
        List<String> warnings = new ArrayList<>(validator.check(loan).warnings());
        if (currentBalance != null && currentBalance > MAX_BALANCE_MULTIPLE * loan.originalUpb()) {
            throw new TermStructureEngine.InvalidProjectionException(
                    "currentBalance is more than " + MAX_BALANCE_MULTIPLE + " times the original balance");
        }
        if (daysPastDue != null && daysPastDue >= 30) {
            warnings.add("The loan is " + daysPastDue + " days past due. The survival model conditions on origination "
                    + "attributes and macro drivers, not on delinquency status, so its curve understates the risk of a "
                    + "loan that is already delinquent; the staging reflects the delinquency.");
        }
        return warnings;
    }

    private static Scenario scenario(String name) {
        try {
            return Scenario.named(name);
        } catch (IllegalArgumentException e) {
            throw new TermStructureEngine.InvalidProjectionException(e.getMessage());
        }
    }

    @ExceptionHandler(TermStructureEngine.InvalidProjectionException.class)
    public ResponseEntity<Map<String, String>> onInvalidProjection(TermStructureEngine.InvalidProjectionException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).body(Map.of("error", e.getMessage()));
    }
}
