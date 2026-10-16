package com.arthadhruva.riskengine.survival;

import com.arthadhruva.riskengine.audit.AuditContext;
import com.arthadhruva.riskengine.billing.Metered;
import com.arthadhruva.riskengine.billing.UsageService;
import com.arthadhruva.riskengine.roughset.DecisionOptions;
import com.arthadhruva.riskengine.roughset.DecisionService;
import com.arthadhruva.riskengine.roughset.GranuleKind;
import com.arthadhruva.riskengine.score.LoanFeatures;
import com.arthadhruva.riskengine.score.LoanInputValidator;
import com.arthadhruva.riskengine.tenant.TenantContext;
import com.fasterxml.jackson.annotation.JsonInclude;
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
    private final DecisionService decisions;
    private final UsageService usage;

    public SurvivalController(TermStructureEngine engine, SurvivalModel model, LoanInputValidator validator,
                              DecisionService decisions, UsageService usage) {
        this.engine = engine;
        this.model = model;
        this.validator = validator;
        this.decisions = decisions;
        this.usage = usage;
    }

    /**
     * @param scenario       a built-in scenario name (default BASELINE); see {@code GET /risk/scenarios}
     * @param customScenario a caller-defined macro path, instead of {@code scenario}
     * @param monthsOnBook   the loan's age when {@code loan.originationMonth} is not given
     * @param months         how many months of the series to return (default: to maturity). Summary and ECL
     *                       always cover the loan's whole remaining life.
     * @param decision       asks for a rough-set three-way decision (certain high, certain low, boundary) of a stressed
     *                       metric against a threshold, over variants of the scenario; omitted, nothing changes
     */
    public record TermStructureRequest(
            @NotNull @Valid LoanFeatures loan,
            @Size(max = 30) String scenario,
            @Valid Scenario customScenario,
            @Min(0) @Max(480) Integer monthsOnBook,
            @Positive Double currentBalance,
            @Min(0) @Max(3650) Integer daysPastDue,
            @DecimalMin("0") @DecimalMax("1") Double originationPd12m,
            @Min(1) @Max(480) Integer months,
            @Valid DecisionOptions decision
    ) {
    }

    public record TermStructureResponse(TermStructureEngine.TermStructure termStructure, List<String> warnings,
                                        @JsonInclude(JsonInclude.Include.NON_NULL) DecisionService.Decision decision) {
    }

    /**
     * @param scenarios built-in scenario names to compare (default: all of them)
     * @param weights   optional probability of each compared scenario (non-negative, summing to 1; a compared
     *                  scenario left out carries 0); when given, the response adds the probability-weighted figures
     */
    public record CompareRequest(
            @NotNull @Valid LoanFeatures loan,
            @Size(min = 1, max = 5) List<@Size(max = 30) String> scenarios,
            @Min(0) @Max(480) Integer monthsOnBook,
            @Positive Double currentBalance,
            @Min(0) @Max(3650) Integer daysPastDue,
            @DecimalMin("0") @DecimalMax("1") Double originationPd12m,
            @Min(1) @Max(480) Integer months,
            @Valid DecisionOptions decision,
            @Size(max = 5) Map<@Size(max = 30) String, Double> weights
    ) {
    }

    /** @param decision the three-way decision about this scenario, over variants of it (perturbation granule only) */
    public record ScenarioCurve(String scenario, String description, TermStructureEngine.Summary summary,
                                TermStructureEngine.Ecl ecl, double[] cumulativeDefault, double[] cumulativePrepay,
                                double[] survival, double[] discountedExpectedLoss,
                                List<TermStructureEngine.Extrapolation> extrapolations,
                                @JsonInclude(JsonInclude.Include.NON_NULL) DecisionService.Decision decision) {
    }

    /**
     * @param decision the three-way decision across the listed scenarios (scenario-set granule only)
     * @param weighted the probability-weighted figures, present only when the request gave weights
     */
    public record CompareResponse(String modelVersion, int monthsOnBook, int remainingMonths, List<String> months,
                                  List<ScenarioCurve> scenarios, List<String> warnings,
                                  @JsonInclude(JsonInclude.Include.NON_NULL) DecisionService.Decision decision,
                                  @JsonInclude(JsonInclude.Include.NON_NULL) Weighted weighted) {
    }

    /**
     * Each figure is {@code sum_s w_s x figure_s} over the compared scenarios, each scenario's figure exactly as
     * reported for it, so a scenario's allowance is the one staged on its own path ({@link ScenarioWeights}).
     *
     * @param method           {@code SCENARIO_WEIGHTED}: how the figures were combined
     * @param weights          the weights used, by scenario, in compared order (0 for a scenario not named)
     * @param stageProbability the total weight of the scenarios in which the loan is in stage 1, 2 and 3
     */
    public record Weighted(String method, Map<String, Double> weights, double pd12m, double pdLifetime, double ecl12m,
                           double eclLifetime, double eclIfrs9, double eclCecl, double[] stageProbability) {
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
        DecisionOptions.Resolved options = resolve(request.decision());
        AuditContext.modelVersion(model.version());
        TermStructureEngine.Request base = new TermStructureEngine.Request(loan, scenario, request.monthsOnBook(),
                request.currentBalance(), request.daysPastDue(), request.originationPd12m());
        TermStructureEngine.TermStructure result = engine.project(base,
                request.months() == null ? TermStructureEngine.MAX_MONTHS : request.months());
        DecisionService.Decision decision = null;
        if (options != null) {
            decision = decisions.decide(base, options);
            meterDecision(decision.members());
        }
        return new TermStructureResponse(result, warnings, decision);
    }

    /** The same loan under several scenarios, evaluated in one batched model call. */
    @Metered("TERM_STRUCTURE")
    @PostMapping("/risk/term-structure/compare")
    public CompareResponse compare(@Valid @RequestBody CompareRequest request) {
        LoanFeatures loan = request.loan().normalized();
        List<String> warnings = check(loan, request.currentBalance(), request.daysPastDue());
        List<Scenario> scenarios = request.scenarios() == null ? Scenario.BUILT_IN
                : request.scenarios().stream().distinct().map(SurvivalController::scenario).toList();
        DecisionOptions.Resolved options = resolve(request.decision());
        List<String> names = scenarios.stream().map(Scenario::name).toList();
        double[] weights = request.weights() == null ? null : ScenarioWeights.resolve(request.weights(), names);
        AuditContext.modelVersion(model.version());
        List<TermStructureEngine.Request> requests = new ArrayList<>(scenarios.size());
        for (Scenario scenario : scenarios) {
            requests.add(new TermStructureEngine.Request(loan, scenario, request.monthsOnBook(), request.currentBalance(),
                    request.daysPastDue(), request.originationPd12m()));
        }
        List<TermStructureEngine.Outcome> outcomes = engine.projectAll(requests);
        int months = Math.min(request.months() == null ? COMPARE_DEFAULT_MONTHS : request.months(), outcomes.get(0).months());
        boolean perScenario = options != null && options.kind() == GranuleKind.PERTURBATION;
        int decisionMembers = 0;
        List<ScenarioCurve> curves = new ArrayList<>(outcomes.size());
        for (int i = 0; i < outcomes.size(); i++) {
            TermStructureEngine.Outcome o = outcomes.get(i);
            DecisionService.Decision decision = null;
            if (perScenario) {
                decision = decisions.decide(requests.get(i), options);
                decisionMembers += decision.members();
            }
            curves.add(new ScenarioCurve(scenarios.get(i).name(), scenarios.get(i).description(), o.summary(), o.ecl(),
                    o.cumulativeDefault(months), o.cumulativePrepay(months), o.survival(months), o.discountedLoss(months),
                    engine.extrapolations(o), decision));
        }
        DecisionService.Decision setDecision = null;
        if (options != null && !perScenario) {
            setDecision = decisions.decide(requests.get(0), options);
            decisionMembers = setDecision.members();
        }
        if (options != null) {
            meterDecision(decisionMembers);
        }
        TermStructureEngine.Outcome first = outcomes.get(0);
        return new CompareResponse(model.version(), first.monthsOnBook(), first.months(), first.monthLabels(months), curves, warnings,
                setDecision, weights == null ? null : weighted(weights, names, outcomes));
    }

    static Weighted weighted(double[] weights, List<String> names, List<TermStructureEngine.Outcome> outcomes) {
        int n = outcomes.size();
        double[] pd12m = new double[n], pdLifetime = new double[n], ecl12m = new double[n], eclLifetime = new double[n],
                eclIfrs9 = new double[n], eclCecl = new double[n];
        double[] stageProbability = new double[3];
        for (int i = 0; i < n; i++) {
            TermStructureEngine.Summary s = outcomes.get(i).summary();
            TermStructureEngine.Ecl e = outcomes.get(i).ecl();
            pd12m[i] = s.pd12m();
            pdLifetime[i] = s.pdLifetime();
            ecl12m[i] = e.ecl12m();
            eclLifetime[i] = e.eclLifetime();
            eclIfrs9[i] = e.eclIfrs9();
            eclCecl[i] = e.eclCecl();
            stageProbability[e.stage() - 1] += weights[i];
        }
        return new Weighted("SCENARIO_WEIGHTED", ScenarioWeights.named(weights, names), ScenarioWeights.combine(weights, pd12m),
                ScenarioWeights.combine(weights, pdLifetime), ScenarioWeights.combine(weights, ecl12m),
                ScenarioWeights.combine(weights, eclLifetime), ScenarioWeights.combine(weights, eclIfrs9),
                ScenarioWeights.combine(weights, eclCecl), stageProbability);
    }

    @GetMapping("/risk/scenarios")
    public List<Scenario> scenarios() {
        return Scenario.BUILT_IN;
    }

    /** Validates the decision options before any projection runs; null when none were asked for. */
    private static DecisionOptions.Resolved resolve(DecisionOptions options) {
        return options == null ? null : options.resolve(DecisionOptions.DEFAULT_MEMBERS, DecisionOptions.MAX_MEMBERS);
    }

    /** One unit per variant projected, on top of the call's own metering, so the cost of a wide granule is visible. */
    private void meterDecision(int members) {
        TenantContext.getOptional().ifPresent(tenantId -> usage.record(tenantId, "DECISION_MEMBER", members));
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
