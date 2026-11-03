package com.arthadhruva.riskengine.governance;

import com.arthadhruva.riskengine.tenant.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Stored scores against realized outcomes. Recording outcomes and asking for a comparison are ADMIN (under /admin/**);
 * reading the latest report is open to analysts like the rest of governance.
 */
@RestController
public class RealizedOutcomeController {

    private final RealizedOutcomeService outcomes;

    public RealizedOutcomeController(RealizedOutcomeService outcomes) {
        this.outcomes = outcomes;
    }

    public record OutcomeRequest(
            @NotBlank @Size(max = 255) String loanId,
            @NotNull RealizedOutcomeService.Outcome outcome,
            @NotNull @PastOrPresent LocalDate observedOn
    ) {
    }

    public record OutcomeBatch(@NotEmpty @Size(max = 5000) List<@Valid OutcomeRequest> outcomes) {
    }

    /** What was recorded, and how many outcomes the organization now has. */
    @PostMapping("/admin/outcomes")
    public Map<String, Object> record(@Valid @RequestBody OutcomeBatch batch, Authentication authentication) {
        Long tenant = TenantContext.get();
        int n = outcomes.record(tenant, authentication.getName(), batch.outcomes().stream()
                .map(o -> new RealizedOutcomeService.OutcomeEntry(o.loanId().trim(), o.outcome(), o.observedOn())).toList());
        return Map.of("recorded", n, "total", outcomes.outcomeCount(tenant));
    }

    /** The counts, and the latest comparison of stored PD scores with the recorded outcomes (null until one has been asked for). */
    @GetMapping("/models/pd_24m/realized")
    public RealizedOutcomeService.Overview overview() {
        return outcomes.overview(TenantContext.get());
    }

    /** Queues a comparison now; it runs on a compute thread. 202 with the report row to poll. */
    @PostMapping("/admin/models/pd_24m/realized")
    public ResponseEntity<RealizedOutcomeService.ReportView> run(Authentication authentication) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(outcomes.request(TenantContext.get(), authentication.getName()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> onInvalid(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
