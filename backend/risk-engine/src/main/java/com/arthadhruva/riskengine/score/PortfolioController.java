package com.arthadhruva.riskengine.score;

import com.arthadhruva.riskengine.tenant.TenantContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Loading a tenant's own loan portfolio, two ways to the same service: an admin uploads it (under
 * {@code /admin/**}, ADMIN role), or a core system pushes it with an API key (under
 * {@code /ingest/**}, which only API keys may reach). Once a tenant has any loans of its own its
 * catalog views and scoring-by-id use them instead of the shared demo catalog.
 */
@RestController
public class PortfolioController {

    private final TenantLoanService service;

    public PortfolioController(TenantLoanService service) {
        this.service = service;
    }

    public record BatchRequest(List<LoanFeatures> loans) {
    }

    public record BatchResponse(int accepted, int rejected, List<TenantLoanService.ItemResult> results) {
    }

    @PostMapping("/ingest/loans")
    public ResponseEntity<?> ingest(@RequestBody BatchRequest request) {
        return upload(request);
    }

    @PostMapping("/admin/portfolio")
    public ResponseEntity<?> adminUpload(@RequestBody BatchRequest request) {
        return upload(request);
    }

    private ResponseEntity<?> upload(BatchRequest request) {
        if (request == null || request.loans() == null || request.loans().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "loans must be a non-empty array"));
        }
        try {
            List<TenantLoanService.ItemResult> results = service.upsertAll(TenantContext.get(), request.loans());
            int ok = (int) results.stream().filter(TenantLoanService.ItemResult::ok).count();
            return ResponseEntity.ok(new BatchResponse(ok, results.size() - ok, results));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/admin/portfolio")
    public Map<String, Object> status() {
        boolean own = service.hasPortfolio(TenantContext.get());
        return Map.of("usingOwnPortfolio", own, "loanCount", own ? service.all(TenantContext.get()).size() : 0);
    }

    /** Revert to the shared demo catalog. */
    @DeleteMapping("/admin/portfolio")
    public Map<String, Object> clear() {
        return Map.of("removed", service.clear(TenantContext.get()));
    }
}
