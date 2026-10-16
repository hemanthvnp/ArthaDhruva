package com.arthadhruva.riskengine.governance;

import com.arthadhruva.riskengine.cache.RefreshAheadCache;
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
 * Model governance, read-only: the inventory, each validated model's card and backtest, how far the
 * organization's own portfolio has drifted from the population the PD model was trained on, and how far
 * today's macro conditions have moved from the survival model's training window.
 */
@RestController
public class ModelGovernanceController {

    private static final Duration DRIFT_CACHE_TTL = Duration.ofMinutes(10);
    private static final int MAX_SCORES = 20_000;

    private final ModelRegistry registry;
    private final DriftService drift;
    private final MacroDriftService macroDrift;
    private final LoanCatalogService catalog;
    private final LoanScoreService scores;
    private final RefreshAheadCache cache;

    public ModelGovernanceController(ModelRegistry registry, DriftService drift, MacroDriftService macroDrift,
                                     LoanCatalogService catalog, LoanScoreService scores, RefreshAheadCache cache) {
        this.registry = registry;
        this.drift = drift;
        this.macroDrift = macroDrift;
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
     * Cached for ten minutes per organization, and recomputed in the background while it is still being
     * read: it reads the whole portfolio, which no caller should wait for twice.
     */
    @GetMapping("/models/pd_24m/drift")
    public DriftService.DriftReport drift() {
        Long tenantId = TenantContext.get();
        return cache.get("drift:" + tenantId, DriftService.DriftReport.class, DRIFT_CACHE_TTL, () -> {
            List<LoanFeatures> loans = catalog.all().stream().map(LoanFeatures::normalized).toList();
            return drift.report(loans, scores.recentProbabilities(tenantId, MAX_SCORES));
        });
    }

    /**
     * The survival model's macro drivers today against its training window, and this organization's loans in
     * states outside the training envelope. Cached like the PD drift report: the macro data changes monthly.
     */
    @GetMapping("/models/survival/drift")
    public MacroDriftService.MacroDriftReport survivalDrift() {
        Long tenantId = TenantContext.get();
        return cache.get("macro-drift:" + tenantId, MacroDriftService.MacroDriftReport.class, DRIFT_CACHE_TTL,
                () -> macroDrift.report(catalog.all().stream().map(LoanFeatures::propertyState).toList()));
    }
}
