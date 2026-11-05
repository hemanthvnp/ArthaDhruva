package com.arthadhruva.riskengine.score;

import com.arthadhruva.riskengine.tenant.TenantContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    /**
     * @param loans    the loans' origination features
     * @param statuses where some of them stand now, by loan id (optional): days past due and recent payment history. A
     *                 portfolio run projects such a loan from where it is. A loan with no entry is stored without a status.
     */
    public record BatchRequest(List<LoanFeatures> loans, Map<String, LoanStatus> statuses) {
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
        Map<String, LoanStatus> statuses = request.statuses() == null ? Map.of() : request.statuses();
        Set<String> batchIds = new HashSet<>();
        request.loans().forEach(l -> {
            if (l != null && l.loanId() != null) {
                batchIds.add(l.loanId().trim());
            }
        });
        List<String> unknown = statuses.keySet().stream().filter(id -> id == null || !batchIds.contains(id.trim())).sorted(
                java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder())).limit(5).toList();
        if (!unknown.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "statuses names loans that are not in this request: " + unknown + ". A status goes with the loan it describes"));
        }
        try {
            List<TenantLoanService.ItemResult> results = service.upsertAll(TenantContext.get(), request.loans(), statuses);
            int ok = (int) results.stream().filter(TenantLoanService.ItemResult::ok).count();
            return ResponseEntity.ok(new BatchResponse(ok, results.size() - ok, results));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/admin/portfolio")
    public Map<String, Object> status() {
        int loans = service.count(TenantContext.get());
        return Map.of("usingOwnPortfolio", loans > 0, "loanCount", loans);
    }

    /** Revert to the shared demo catalog. */
    @DeleteMapping("/admin/portfolio")
    public Map<String, Object> clear() {
        return Map.of("removed", service.clear(TenantContext.get()));
    }
}
