package com.arthadhruva.riskengine.webhook;

import com.arthadhruva.riskengine.search.PageResult;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;

/** The {@code webhook} module's facade: subscription management and delivery visibility. */
@Service
public class WebhookService {

    private final WebhookSubscriptionRepository subscriptions;
    private final WebhookOutboxRepository outbox;
    private final UrlGuard urlGuard;

    public WebhookService(WebhookSubscriptionRepository subscriptions, WebhookOutboxRepository outbox, UrlGuard urlGuard) {
        this.subscriptions = subscriptions;
        this.outbox = outbox;
        this.urlGuard = urlGuard;
    }

    /** The returned entity carries the signing secret; it is shown to the admin exactly once. */
    public WebhookSubscription subscribe(Long tenantId, String url, List<WebhookEventType> types) {
        urlGuard.check(url);
        if (types == null || types.isEmpty()) {
            throw new IllegalArgumentException("Select at least one event type");
        }
        byte[] raw = new byte[24];
        new SecureRandom().nextBytes(raw);
        return subscriptions.save(new WebhookSubscription(tenantId, url, types.stream().distinct().toList(),
                "whsec_" + HexFormat.of().formatHex(raw)));
    }

    public List<WebhookSubscription> list(Long tenantId) {
        return subscriptions.findByTenantIdOrderByIdAsc(tenantId);
    }

    public boolean unsubscribe(Long tenantId, Long id) {
        return subscriptions.findByIdAndTenantId(id, tenantId).map(s -> {
            subscriptions.delete(s);
            return true;
        }).orElse(false);
    }

    public Page<WebhookOutbox> deliveries(Long tenantId, String status, int page, int size) {
        PageRequest pr = PageRequest.of(Math.max(0, page), PageResult.boundedSize(size));
        return status == null || status.isBlank()
                ? outbox.findByTenantIdOrderByIdDesc(tenantId, pr)
                : outbox.findByTenantIdAndStatusOrderByIdDesc(tenantId, status.toUpperCase(), pr);
    }

    /** Package-visible for the outbox writer (same module). */
    List<WebhookSubscription> activeSubscriptions(Long tenantId) {
        return subscriptions.findByTenantIdAndEnabledTrue(tenantId);
    }

    WebhookOutbox enqueue(WebhookOutbox row) {
        return outbox.save(row);
    }
}
