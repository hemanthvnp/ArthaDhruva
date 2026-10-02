package com.arthadhruva.riskengine.governance;

import com.arthadhruva.riskengine.cache.CacheService;
import com.arthadhruva.riskengine.score.LoanCatalogService;
import com.arthadhruva.riskengine.score.LoanFeatures;
import com.arthadhruva.riskengine.score.LoanScoreService;
import com.arthadhruva.riskengine.tenant.TenantContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.List;

/**
 * Model governance, read-only: the inventory, each validated model's card and backtest, and how far the
 * organization's own portfolio has drifted from the population the PD model was trained on.
 */
@RestController
public class ModelGovernanceController {

    private static final Duration DRIFT_CACHE_TTL = Duration.ofMinutes(10);
    private static final int MAX_SCORES = 20_000;

    private final ModelRegistry registry;
    private final DriftService drift;
    private final LoanCatalogService catalog;
    private final LoanScoreService scores;
    private final CacheService cache;

    public ModelGovernanceController(ModelRegistry registry, DriftService drift, LoanCatalogService catalog,
                                     LoanScoreService scores, CacheService cache) {
        this.registry = registry;
        this.drift = drift;
        this.catalog = catalog;
        this.scores = scores;
        this.cache = cache;
    }

    @GetMapping("/models")
    public List<ModelRegistry.Entry> inventory() {
        return registry.entries();
    }

    @GetMapping("/models/{id}")
    public ResponseEntity<JsonNode> card(@PathVariable String id) {
        return registry.card(id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Monthly predicted vs realized hazards, calibration by decile and vintage curves of the survival model. */
    @GetMapping("/models/survival/backtest")
    public JsonNode survivalBacktest() {
        return registry.survivalBacktest();
    }

    /**
     * Population stability of this organization's portfolio against the PD model's training population.
     * Cached for ten minutes per organization: it reads the whole portfolio.
     */
    @GetMapping("/models/pd_24m/drift")
    public DriftService.DriftReport drift() {
        Long tenantId = TenantContext.get();
        String key = "drift:" + tenantId;
        return cache.get(key, DriftService.DriftReport.class).orElseGet(() -> {
            List<LoanFeatures> loans = catalog.all().stream().map(LoanFeatures::normalized).toList();
            DriftService.DriftReport report = drift.report(loans, scores.recentProbabilities(tenantId, MAX_SCORES));
            cache.put(key, report, DRIFT_CACHE_TTL);
            return report;
        });
    }
}
