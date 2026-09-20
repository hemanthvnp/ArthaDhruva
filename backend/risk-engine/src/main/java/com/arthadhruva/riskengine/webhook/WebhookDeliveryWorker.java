package com.arthadhruva.riskengine.webhook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Transactional Outbox, delivery side: at-least-once, safe to run on any number of replicas.
 *
 * <p>Claiming uses {@code FOR UPDATE SKIP LOCKED}: concurrent workers each lock a different set of
 * due rows instead of blocking on (or double-delivering) each other's. A claimed row is leased
 * (IN_FLIGHT + locked_until) and the claim is committed <em>before</em> the slow HTTP call, so no
 * row lock is held across the network; if the worker dies mid-delivery the lease expires and
 * another worker retries, hence "at least once". Every delivery of an event carries the same
 * {@code X-Webhook-Event-Id}, so receivers can de-duplicate. Failures retry with exponential
 * backoff and jitter (avoids synchronized retry storms) up to {@code webhook.max-attempts}, then the
 * row is marked FAILED and kept, never silently dropped.
 */
@Configuration
@EnableScheduling
class WebhookWorkerConfig {
}

@Component
class WebhookDeliveryWorker {

    private static final Logger log = LoggerFactory.getLogger(WebhookDeliveryWorker.class);
    private static final int BATCH = 10;
    private static final int LEASE_SECONDS = 60;

    private final WorkerDb db;
    private final UrlGuard urlGuard;
    private final int maxAttempts;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER) // a redirect could hop past the SSRF guard
            .build();

    WebhookDeliveryWorker(WorkerDb db, UrlGuard urlGuard, @Value("${webhook.max-attempts:6}") int maxAttempts) {
        this.db = db;
        this.urlGuard = urlGuard;
        this.maxAttempts = maxAttempts;
    }

    @Scheduled(fixedDelayString = "${webhook.worker.poll-ms:2000}")
    void poll() {
        try {
            for (Map<String, Object> row : claim()) {
                deliver(row);
            }
        } catch (Exception e) {
            log.warn("Webhook poll failed; will retry next tick", e);
        }
    }

    private List<Map<String, Object>> claim() {
        return db.jdbc().queryForList("""
                UPDATE webhook_outbox o
                   SET status = 'IN_FLIGHT', attempts = attempts + 1,
                       locked_until = now() + make_interval(secs => ?)
                 WHERE o.id IN (SELECT id FROM webhook_outbox
                                 WHERE (status = 'PENDING' AND next_attempt_at <= now())
                                    OR (status = 'IN_FLIGHT' AND locked_until < now())
                                 ORDER BY next_attempt_at
                                 LIMIT ? FOR UPDATE SKIP LOCKED)
             RETURNING o.id, o.event_id, o.subscription_id, o.payload, o.attempts
                """, LEASE_SECONDS, BATCH);
    }

    private void deliver(Map<String, Object> row) {
        long id = ((Number) row.get("id")).longValue();
        int attempts = ((Number) row.get("attempts")).intValue();
        try {
            Map<String, Object> sub = db.jdbc().queryForMap(
                    "SELECT url, secret FROM webhook_subscription WHERE id = ?", row.get("subscription_id"));
            URI uri = urlGuard.check((String) sub.get("url"));
            String body = (String) row.get("payload");

            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .header("X-Webhook-Event-Id", String.valueOf(row.get("event_id")))
                    .header("X-Webhook-Signature", "sha256=" + sign((String) sub.get("secret"), body))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());

            if (response.statusCode() / 100 == 2) {
                db.jdbc().update("UPDATE webhook_outbox SET status='DELIVERED', delivered_at=now(), locked_until=NULL, last_error=NULL WHERE id=?", id);
            } else {
                retryOrFail(id, attempts, "HTTP " + response.statusCode());
            }
        } catch (Exception e) {
            retryOrFail(id, attempts, e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage()));
        }
    }

    private void retryOrFail(long id, int attempts, String error) {
        String trimmed = error.length() > 250 ? error.substring(0, 250) : error;
        if (attempts >= maxAttempts) {
            db.jdbc().update("UPDATE webhook_outbox SET status='FAILED', locked_until=NULL, last_error=? WHERE id=?", trimmed, id);
            log.warn("Webhook outbox {} permanently failed after {} attempts: {}", id, attempts, trimmed);
            return;
        }
        db.jdbc().update("UPDATE webhook_outbox SET status='PENDING', locked_until=NULL, last_error=?, "
                + "next_attempt_at = now() + make_interval(secs => ?) WHERE id=?", trimmed, backoffSeconds(attempts), id);
    }

    /** min(5 min, 5s * 2^attempts), then +-50% jitter. */
    static double backoffSeconds(int attempts) {
        double base = Math.min(300.0, 5.0 * Math.pow(2, attempts));
        return base * (0.5 + ThreadLocalRandom.current().nextDouble());
    }

    private static String sign(String secret, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }
}
