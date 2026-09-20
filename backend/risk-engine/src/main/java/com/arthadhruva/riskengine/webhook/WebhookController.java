package com.arthadhruva.riskengine.webhook;

import com.arthadhruva.riskengine.search.PageResult;
import com.arthadhruva.riskengine.tenant.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Admin-only (under /admin/**): webhook subscriptions and their delivery history. */
@RestController
public class WebhookController {

    private final WebhookService service;

    public WebhookController(WebhookService service) {
        this.service = service;
    }

    public record SubscribeRequest(@NotBlank String url, @NotNull List<WebhookEventType> events) {
    }

    public record SubscriptionView(Long id, String url, List<WebhookEventType> events, boolean enabled, String secret) {
        static SubscriptionView of(WebhookSubscription s, boolean revealSecret) {
            return new SubscriptionView(s.getId(), s.getUrl(), s.getEventTypes(), s.isEnabled(),
                    revealSecret ? s.getSecret() : null);
        }
    }

    public record DeliveryView(Long id, String eventId, String eventType, String status, int attempts,
                               String lastError, Instant createdAt, Instant deliveredAt) {
    }

    @PostMapping("/admin/webhooks")
    public ResponseEntity<?> subscribe(@Valid @RequestBody SubscribeRequest r) {
        try {
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(SubscriptionView.of(service.subscribe(TenantContext.get(), r.url(), r.events()), true));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/admin/webhooks")
    public List<SubscriptionView> list() {
        return service.list(TenantContext.get()).stream().map(s -> SubscriptionView.of(s, false)).toList();
    }

    @DeleteMapping("/admin/webhooks/{id}")
    public ResponseEntity<?> unsubscribe(@PathVariable Long id) {
        return service.unsubscribe(TenantContext.get(), id) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    @GetMapping("/admin/webhooks/deliveries")
    public PageResult<DeliveryView> deliveries(@RequestParam(required = false) String status,
                                               @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "25") int size) {
        return PageResult.of(service.deliveries(TenantContext.get(), status, page, size),
                d -> new DeliveryView(d.getId(), d.getEventId().toString(), d.getEventType(), d.getStatus(),
                        d.getAttempts(), d.getLastError(), d.getCreatedAt(), d.getDeliveredAt()));
    }
}
