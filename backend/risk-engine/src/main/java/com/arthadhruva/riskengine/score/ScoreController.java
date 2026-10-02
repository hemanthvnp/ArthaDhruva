package com.arthadhruva.riskengine.score;

import com.arthadhruva.riskengine.audit.AuditContext;
import com.arthadhruva.riskengine.billing.Metered;
import com.arthadhruva.riskengine.cache.CacheService;
import com.arthadhruva.riskengine.export.CsvWriter;
import com.arthadhruva.riskengine.tenant.TenantContext;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
public class ScoreController {

    private static final Logger log = LoggerFactory.getLogger(ScoreController.class);
    private static final Duration SCORE_CACHE_TTL = Duration.ofHours(24);

    private final ModelService modelService;
    private final CacheService cacheService;
    private final LoanScoreService loanScoreService;
    private final ExplanationService explanationService;
    private final LoanInputValidator validator;

    public ScoreController(ModelService modelService, CacheService cacheService, LoanScoreService loanScoreService,
                           ExplanationService explanationService, LoanInputValidator validator) {
        this.modelService = modelService;
        this.cacheService = cacheService;
        this.loanScoreService = loanScoreService;
        this.explanationService = explanationService;
        this.validator = validator;
    }

    /**
     * Scores a loan: 24-month PD, Shapley explanation, adverse-action reason codes and domain warnings.
     * With a {@code loanId} the score becomes the durable record of that loan; if it cannot be recorded
     * the request fails (503) rather than returning a score nobody can later trace.
     */
    @Metered("SCORE_CALL")
    @PostMapping("/score")
    public ResponseEntity<?> score(@Valid @RequestBody LoanFeatures request) {
        LoanFeatures loan = request.normalized();
        LoanInputValidator.Result checked = validator.check(loan);
        ScoreResponse response = modelService.score(loan)
                .with(explanationService.explain(loan), checked.warnings(), modelService.version(), modelService.horizonMonths());
        AuditContext.modelVersion(modelService.version());
        if (loan.loanId() != null && !loan.loanId().isBlank()) {
            Long tenantId = TenantContext.get();
            Instant now = Instant.now();
            try {
                loanScoreService.upsert(tenantId, loan.loanId(), response.rawProbability(), response.calibratedProbability(),
                        now, modelService.version());
            } catch (RuntimeException e) {
                log.error("Could not record the score for loan {}", loan.loanId(), e);
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header(HttpHeaders.RETRY_AFTER, "5")
                        .body(Map.of("error", "The score could not be recorded; please retry."));
            }
            // Cache only what is durably recorded, and only after it is.
            cacheService.put(cacheKey(tenantId, loan.loanId()), new ScoreResponse.CachedScore(response, now), SCORE_CACHE_TTL);
        }
        return ResponseEntity.ok(response);
    }

    /** A previously computed score: from the cache when warm, otherwise from the durable record. */
    @GetMapping("/score/{loanId}")
    public ResponseEntity<?> getScore(@PathVariable String loanId) {
        Long tenantId = TenantContext.get();
        var cached = cacheService.get(cacheKey(tenantId, loanId), ScoreResponse.CachedScore.class);
        if (cached.isPresent()) {
            return ResponseEntity.ok(cached.get());
        }
        return loanScoreService.findByTenantAndLoanId(tenantId, loanId)
                .<ResponseEntity<?>>map(r -> ResponseEntity.ok(new ScoreResponse.CachedScore(
                        new ScoreResponse(r.getRawProbability(), r.getCalibratedProbability(), List.of(), List.of(), List.of(),
                                r.getModelVersion(), modelService.horizonMonths(), null), r.getComputedAt())))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "This loan has not been scored.")));
    }

    /** Tenant-prefixed: a separate scoping surface from Postgres, so two tenants scoring the same loan id
     * never read each other's cached scores. */
    private static String cacheKey(Long tenantId, String loanId) {
        return "score:" + tenantId + ":" + loanId;
    }

    @GetMapping("/loan-scores")
    public List<LoanScoreSummary> loanScores(@RequestParam(defaultValue = "50") int limit) {
        return loanScoreService.recentForTenant(TenantContext.get(), limit).stream()
                .map(r -> new LoanScoreSummary(r.getLoanId(), r.getRawProbability(), r.getCalibratedProbability(),
                        r.getComputedAt(), r.getModelVersion()))
                .toList();
    }

    /** Every recorded score as CSV, streamed in keyset pages (bounded memory at any portfolio size). */
    @GetMapping("/loan-scores/export")
    public ResponseEntity<StreamingResponseBody> exportLoanScores() {
        Long tenantId = TenantContext.get();
        StreamingResponseBody body = out -> {
            try (Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8)) {
                var csv = new CsvWriter.Streaming(writer, List.of("loanId", "rawProbability", "calibratedProbability",
                        "computedAt", "modelVersion"));
                TenantContext.set(tenantId);
                try {
                    loanScoreService.forEachScore(tenantId, r -> csv.row(List.of(r.getLoanId(), String.valueOf(r.getRawProbability()),
                            String.valueOf(r.getCalibratedProbability()), r.getComputedAt().toString(),
                            r.getModelVersion() == null ? "" : r.getModelVersion())));
                } finally {
                    TenantContext.clear();
                }
            }
        };
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv; charset=utf-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("loan-scores.csv").build().toString())
                .body(body);
    }

    public record LoanScoreSummary(String loanId, double rawProbability, double calibratedProbability, Instant computedAt,
                                   String modelVersion) {
    }
}
