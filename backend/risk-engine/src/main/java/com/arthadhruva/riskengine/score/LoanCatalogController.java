package com.arthadhruva.riskengine.score;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Serves the real-loan catalog (see LoanCatalogService) so the frontend can let an analyst pick
 * an actual loan to score instead of typing feature values by hand. Small enough (400 loans) to
 * return in full in one call -- the frontend looks up the chosen entry client-side and hands it
 * straight to {@code POST /score}, no second round-trip needed.
 */
@RestController
@Validated
public class LoanCatalogController {

    private final LoanCatalogService loanCatalogService;

    public LoanCatalogController(LoanCatalogService loanCatalogService) {
        this.loanCatalogService = loanCatalogService;
    }

    /**
     * The portfolio in loan-id order, a page at a time, optionally narrowed by a fragment of the loan id
     * and by state. The total number of matching loans is in the {@code X-Total-Count} header.
     */
    @GetMapping("/loans")
    public ResponseEntity<List<LoanFeatures>> loans(
            @RequestParam(required = false) @Size(max = 80) String q,
            @RequestParam(required = false) @Size(max = 8) String state,
            @RequestParam(defaultValue = "500") @Min(1) @Max(TenantLoanService.MAX_PAGE) int limit,
            @RequestParam(defaultValue = "0") @Min(0) int offset) {
        LoanCatalogService.Page page = loanCatalogService.page(q, state, limit, offset);
        return ResponseEntity.ok().header("X-Total-Count", String.valueOf(page.total())).body(page.loans());
    }

    /** One loan of the portfolio by id. */
    @GetMapping("/loans/{loanId}")
    public ResponseEntity<LoanFeatures> loan(@PathVariable @Size(max = 80) String loanId) {
        LoanFeatures loan = loanCatalogService.find(loanId);
        return loan == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(loan);
    }
}
