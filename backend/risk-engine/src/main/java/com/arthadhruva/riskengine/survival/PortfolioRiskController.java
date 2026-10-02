package com.arthadhruva.riskengine.survival;

import com.arthadhruva.riskengine.audit.AuditContext;
import com.arthadhruva.riskengine.audit.NotAudited;
import com.arthadhruva.riskengine.billing.Metered;
import com.arthadhruva.riskengine.cvar.CvarEngine;
import com.arthadhruva.riskengine.cvar.CvarRequest;
import com.arthadhruva.riskengine.export.CsvWriter;
import com.arthadhruva.riskengine.score.LoanCatalogService;
import com.arthadhruva.riskengine.score.LoanFeatures;
import com.arthadhruva.riskengine.tenant.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Portfolio-level lifetime risk. Starting a run answers 202 with the run to poll; what it stored (the
 * latest snapshot of each scenario, each scenario's history, the loan-by-loan listing) is read separately
 * and is cheap, and the loss distribution is simulated from the stored loans without projecting again.
 */
@RestController
@Validated
public class PortfolioRiskController {

    private static final List<String> DEFAULT_SCENARIOS = List.of("BASELINE", "ADVERSE", "SEVERELY_ADVERSE");

    private final PortfolioRiskService service;
    private final LoanCatalogService catalog;
    private final SurvivalModel model;

    public PortfolioRiskController(PortfolioRiskService service, LoanCatalogService catalog, SurvivalModel model) {
        this.service = service;
        this.catalog = catalog;
        this.model = model;
    }

    /** @param scenarios built-in scenario names (default: baseline, adverse and severely adverse) */
    public record RunRequest(@Size(min = 1, max = 5) List<@NotBlank @Size(max = 30) String> scenarios) {
    }

    /**
     * @param run            the latest run, so a client that reloads can pick up polling where it left off
     * @param portfolioLoans loans in the portfolio now, which may no longer be the number the snapshots cover
     */
    public record PortfolioView(List<PortfolioRiskService.Snapshot> snapshots, PortfolioRiskService.Run run, int portfolioLoans) {
    }

    /** Every field optional: the defaults are a one-in-a-thousand year at the Basel mortgage correlation. */
    public record LossRequest(@DecimalMin("0.5") @DecimalMax("0.9999") Double confidenceLevel,
                              @Min(1_000) @Max(200_000) Integer numScenarios,
                              @DecimalMin("0.0") @DecimalMax("0.6") Double assetCorrelation,
                              Long seed, Boolean importanceSampling) {
    }

    /**
     * Starts a run over the organization's portfolio (its own loans, or the demo catalog until it has any).
     * 202 with the run; 409 with the run already in progress; 503 when every worker is taken.
     *
     * <p>The baseline is always part of a run, whatever was asked for: the allowance, the loan-by-loan
     * results and the loss distribution are all stated on it, and a stress result means little without
     * the figure it is a stress of.
     */
    @Metered("PORTFOLIO_RUN")
    @PostMapping("/risk/portfolio/runs")
    public ResponseEntity<?> start(@Valid @RequestBody(required = false) RunRequest request, Authentication authentication) {
        List<String> names = new ArrayList<>(request == null || request.scenarios() == null ? DEFAULT_SCENARIOS : request.scenarios());
        names.remove(Scenario.BASELINE.name());
        names.add(0, Scenario.BASELINE.name());
        List<Scenario> scenarios;
        try {
            scenarios = names.stream().distinct().map(Scenario::named).toList();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).body(Map.of("error", e.getMessage()));
        }
        List<LoanFeatures> loans = catalog.all();
        if (loans.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).body(Map.of("error", "The portfolio has no loans"));
        }
        AuditContext.modelVersion(model.version());
        try {
            PortfolioRiskService.Run run = service.start(TenantContext.get(), authentication.getName(), loans, scenarios);
            return ResponseEntity.accepted().location(URI.create("/v1/risk/portfolio/runs/" + run.id())).body(run);
        } catch (PortfolioRiskService.RunInProgressException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(e.run());
        } catch (PortfolioRiskService.BusyException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header(HttpHeaders.RETRY_AFTER, "30")
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /** Polled while a run is in progress, so kept out of the audit trail; starting a run is recorded. */
    @NotAudited
    @GetMapping("/risk/portfolio/runs/{id}")
    public ResponseEntity<PortfolioRiskService.Run> run(@PathVariable UUID id) {
        return service.find(TenantContext.get(), id)
                .map(run -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(run))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** The latest snapshot of every scenario, with the latest run. */
    @GetMapping("/risk/portfolio")
    public PortfolioView portfolio() {
        Long tenantId = TenantContext.get();
        return new PortfolioView(service.latestSnapshots(tenantId), service.latest(tenantId).orElse(null),
                catalog.page(null, null, 1, 0).total());
    }

    /**
     * The latest run's baseline results loan by loan: the listing behind the allowance.
     *
     * @param stage only loans in this IFRS 9 stage
     * @param sort  eclLifetime (default), eclIfrs9, pd12m, exposure or loanId
     */
    @GetMapping("/risk/portfolio/loans")
    public ResponseEntity<?> loans(@RequestParam(required = false) @Min(1) @Max(3) Integer stage,
                                   @RequestParam(defaultValue = "eclLifetime") @Size(max = 20) String sort,
                                   @RequestParam(defaultValue = "25") @Min(1) @Max(500) int limit,
                                   @RequestParam(defaultValue = "0") @Min(0) int offset) {
        PortfolioRiskService.LoanOrder order = switch (sort) {
            case "eclLifetime" -> PortfolioRiskService.LoanOrder.ECL_LIFETIME;
            case "eclIfrs9" -> PortfolioRiskService.LoanOrder.ECL_IFRS9;
            case "pd12m" -> PortfolioRiskService.LoanOrder.PD_12M;
            case "exposure" -> PortfolioRiskService.LoanOrder.EXPOSURE;
            case "loanId" -> PortfolioRiskService.LoanOrder.LOAN_ID;
            default -> null;
        };
        if (order == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "sort must be one of eclLifetime, eclIfrs9, pd12m, exposure, loanId"));
        }
        return ResponseEntity.ok(service.loans(TenantContext.get(), stage, order, limit, offset));
    }

    /** The same listing as CSV, every loan, streamed in keyset pages (bounded memory at any size). */
    @GetMapping("/risk/portfolio/loans/export")
    public ResponseEntity<StreamingResponseBody> exportLoans() {
        Long tenantId = TenantContext.get();
        StreamingResponseBody body = out -> {
            try (Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8)) {
                var csv = new CsvWriter.Streaming(writer, List.of("loanId", "state", "exposure", "pd12m", "pdLifetime", "lgd",
                        "ecl12m", "eclLifetime", "eclIfrs9", "stage", "stageReason", "weight"));
                TenantContext.set(tenantId);   // the body is written on another thread than the request's
                try {
                    service.forEachLoan(tenantId, loan -> csv.row(List.of(loan.loanId(), loan.state(), amount(loan.exposure()),
                            String.valueOf(loan.pd12m()), String.valueOf(loan.pdLifetime()), String.valueOf(loan.lgd()),
                            amount(loan.ecl12m()), amount(loan.eclLifetime()), amount(loan.eclIfrs9()), String.valueOf(loan.stage()),
                            loan.stageReason(), String.valueOf(loan.weight()))));
                } finally {
                    TenantContext.clear();
                }
            }
        };
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv; charset=utf-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("portfolio-ecl-by-loan.csv").build().toString())
                .body(body);
    }

    private static String amount(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    /**
     * The portfolio's one-year loss distribution: value at risk, expected shortfall and the loans that
     * drive the tail, simulated from the latest run's loans. 422 until a run has stored any.
     */
    @Metered("CVAR_RUN")
    @PostMapping("/risk/portfolio/loss-distribution")
    public ResponseEntity<?> lossDistribution(@Valid @RequestBody(required = false) LossRequest request) {
        LossRequest r = request == null ? new LossRequest(null, null, null, null, null) : request;
        AuditContext.modelVersion(model.version());
        return service.lossDistribution(TenantContext.get(),
                        r.confidenceLevel() == null ? PortfolioRiskEngine.CAPITAL_CONFIDENCE : r.confidenceLevel(),
                        r.numScenarios() == null ? PortfolioRiskEngine.CAPITAL_SCENARIOS : r.numScenarios(),
                        r.assetCorrelation() == null ? CvarRequest.DEFAULT_ASSET_CORRELATION : r.assetCorrelation(),
                        r.seed(), r.importanceSampling() == null || r.importanceSampling())
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT)
                        .body(Map.of("error", "Run a portfolio projection first: the loss distribution is simulated from its loans")));
    }

    @ExceptionHandler(CvarEngine.TooLargeException.class)
    public ResponseEntity<Map<String, String>> onTooLarge(CvarEngine.TooLargeException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(CvarEngine.BusyException.class)
    public ResponseEntity<Map<String, String>> onBusy(CvarEngine.BusyException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header(HttpHeaders.RETRY_AFTER, "5")
                .body(Map.of("error", e.getMessage()));
    }

    /** One scenario's headline numbers over time, newest first. */
    @GetMapping("/risk/portfolio/history")
    public List<PortfolioRiskService.Snapshot> history(@RequestParam(defaultValue = "BASELINE") @Size(max = 30) String scenario,
                                                       @RequestParam(defaultValue = "36") @Min(1) @Max(240) int limit) {
        return service.history(TenantContext.get(), scenario, limit);
    }
}
