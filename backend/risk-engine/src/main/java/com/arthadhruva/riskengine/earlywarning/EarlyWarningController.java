package com.arthadhruva.riskengine.earlywarning;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Early-warning delinquency scoring: "for this currently-current loan, what's the probability it
 * goes 30+ days late in the next 3 months?" (notebooks/early_warning_delinquency.ipynb). Not
 * cached/persisted like {@code /score} -- this is meant to be called in bulk by an upstream job
 * scanning a live portfolio snapshot to build the analyst-facing ranked warning list, rather than
 * looked up per-loan on demand.
 */
@RestController
public class EarlyWarningController {

    private final EarlyWarningModelService earlyWarningModelService;

    public EarlyWarningController(EarlyWarningModelService earlyWarningModelService) {
        this.earlyWarningModelService = earlyWarningModelService;
    }

    @PostMapping("/early-warning-score")
    public EarlyWarningResponse score(@Valid @RequestBody EarlyWarningFeatures loan) {
        return earlyWarningModelService.score(loan);
    }
}
