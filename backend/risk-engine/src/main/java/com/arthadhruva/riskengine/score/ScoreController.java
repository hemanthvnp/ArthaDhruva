package com.arthadhruva.riskengine.score;

import com.arthadhruva.riskengine.cache.CacheService;
import com.arthadhruva.riskengine.export.CsvWriter;
import com.arthadhruva.riskengine.tenant.TenantContext;
import jakarta.validation.Valid;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@RestController
public class ScoreController {

    private static final Duration SCORE_CACHE_TTL = Duration.ofHours(24);

    private final ModelService modelService;
    private final CacheService cacheService;
    private final LoanScoreService loanScoreService;
    private final ExplanationService explanationService;

    public ScoreController(ModelService modelService, CacheService cacheService, LoanScoreService loanScoreService,
                           ExplanationService explanationService) {
        this.explanationService = explanationService;
        this.modelService = modelService;
        this.cacheService = cacheService;
        this.loanScoreService = loanScoreService;
    }

    @PostMapping("/score")
    public ScoreResponse score(@Valid @RequestBody LoanFeatures loan) {
        ScoreResponse response = modelService.score(loan).withExplanation(explanationService.explain(loan));
        if (loan.loanId() != null) {
            Instant now = Instant.now();
            Long tenantId = TenantContext.get();
            cacheService.put(cacheKey(tenantId, loan.loanId()),
                    new ScoreResponse.CachedScore(response, now), SCORE_CACHE_TTL);
            loanScoreService.upsert(tenantId, loan.loanId(), response.rawProbability(), response.calibratedProbability(), now);
        }
        return response;
    }

    /**
     * Reads a previously computed score straight from the cache -- the "Online Feature Store"
     * read path. Only returns what {@code POST /score} already cached; never recomputes.
     */
    @GetMapping("/score/{loanId}")
    public ResponseEntity<ScoreResponse.CachedScore> getCachedScore(@PathVariable String loanId) {
        return cacheService.get(cacheKey(TenantContext.get(), loanId), ScoreResponse.CachedScore.class)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Tenant-prefixed: a completely separate scoping surface from Postgres/Hibernate -- without
     * this, two tenants scoring the same loanId would read each other's cached scores. */
    private String cacheKey(Long tenantId, String loanId) {
        return "score:" + tenantId + ":" + loanId;
    }

    /**
     * Every loan anyone has scored so far (durable {@code loan_score} rows), most recent first --
     * the analyst-facing portfolio view: browse what's already been scored instead of re-typing
     * feature values to see it again. Same data {@code GET /my/loans} draws from per-client, just
     * unfiltered and role-open to ANALYST/ADMIN (the default access rule for this endpoint).
     */
    @GetMapping("/loan-scores")
    public List<LoanScoreSummary> loanScores(@RequestParam(defaultValue = "50") int limit) {
        return loanScoreService.recentForTenant(TenantContext.get(), limit).stream()
                .map(r -> new LoanScoreSummary(r.getLoanId(), r.getRawProbability(), r.getCalibratedProbability(), r.getComputedAt()))
                .toList();
    }

    /** Same data as {@code GET /loan-scores}, at the service's max page size, as a downloadable
     * CSV -- an analyst wanting the whole scored portfolio in a spreadsheet rather than the
     * paginated in-app view. */
    @GetMapping("/loan-scores/export")
    public ResponseEntity<String> exportLoanScores() {
        List<List<String>> rows = loanScoreService.recentForTenant(TenantContext.get(), Integer.MAX_VALUE).stream()
                .map(r -> List.of(r.getLoanId(), String.valueOf(r.getRawProbability()),
                        String.valueOf(r.getCalibratedProbability()), r.getComputedAt().toString()))
                .toList();
        String csv = CsvWriter.write(List.of("loanId", "rawProbability", "calibratedProbability", "computedAt"), rows);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("loan-scores.csv").build().toString())
                .body(csv);
    }

    public record LoanScoreSummary(String loanId, double rawProbability, double calibratedProbability, Instant computedAt) {
    }
}
