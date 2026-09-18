package com.arthadhruva.riskengine.score;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Serves the real-loan catalog (see LoanCatalogService) so the frontend can let an analyst pick
 * an actual loan to score instead of typing feature values by hand. Small enough (400 loans) to
 * return in full in one call -- the frontend looks up the chosen entry client-side and hands it
 * straight to {@code POST /score}, no second round-trip needed.
 */
@RestController
public class LoanCatalogController {

    private final LoanCatalogService loanCatalogService;

    public LoanCatalogController(LoanCatalogService loanCatalogService) {
        this.loanCatalogService = loanCatalogService;
    }

    @GetMapping("/loans")
    public List<LoanFeatures> loans() {
        return loanCatalogService.all();
    }
}
