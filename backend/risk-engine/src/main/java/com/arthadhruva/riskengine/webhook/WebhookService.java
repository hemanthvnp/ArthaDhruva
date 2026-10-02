package com.arthadhruva.riskengine.webhook;

import com.arthadhruva.riskengine.search.PageResult;
import com.arthadhruva.riskengine.security.SecretCipher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;

/** The {@code webhook} module's facade: subscription management and delivery visibility. */
@Service
public class WebhookService {

    /** Bounds per tenant: each subscription multiplies outbox rows and outbound calls per event. */
    static final int MAX_SUBSCRIPTIONS = 20;

    private final WebhookSubscriptionRepository subscriptions;
    private final WebhookOutboxRepository outbox;
    private final UrlGuard urlGuard;
    private final SecretCipher cipher;
    private final SecureRandom random = new SecureRandom();

    public WebhookService(WebhookSubscriptionRepository subscriptions, WebhookOutboxRepository outbox, UrlGuard urlGuard,
                          SecretCipher cipher) {
        this.subscriptions = subscriptions;
        this.outbox = outbox;
        this.urlGuard = urlGuard;
        this.cipher = cipher;
    }

    /** A new subscription and its signing secret in plaintext -- the only time it is ever revealed; it
     * is stored encrypted. */
    public record Created(WebhookSubscription subscription, String secret) {
    }

    public Created subscribe(Long tenantId, String url, List<WebhookEventType> types) {
        urlGuard.check(url);
        if (types == null || types.isEmpty()) {
            throw new IllegalArgumentException("Select at least one event type");
        }
        if (subscriptions.findByTenantIdOrderByIdAsc(tenantId).size() >= MAX_SUBSCRIPTIONS) {
            throw new IllegalArgumentException("At most " + MAX_SUBSCRIPTIONS + " webhook subscriptions per organization");
        }
        byte[] raw = new byte[24];
        random.nextBytes(raw);
        String secret = "whsec_" + HexFormat.of().formatHex(raw);
        WebhookSubscription saved = subscriptions.save(new WebhookSubscription(tenantId, url.trim(),
                types.stream().distinct().toList(), cipher.encrypt(secret, SecretCipher.WEBHOOK_SECRET)));
        return new Created(saved, secret);
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
        PageRequest pr = PageRequest.of(PageResult.boundedPage(page), PageResult.boundedSize(size));
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
