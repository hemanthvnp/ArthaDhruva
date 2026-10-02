package com.arthadhruva.riskengine.webhook;

import com.arthadhruva.riskengine.security.SecretCipher;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
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
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Transactional Outbox, delivery side: at-least-once, safe on any number of replicas.
 *
 * <ul>
 *   <li><b>Claiming</b> uses {@code FOR UPDATE SKIP LOCKED}: concurrent workers take disjoint rows. The
 *       claim commits before any HTTP call, so no row lock is held across the network.</li>
 *   <li><b>Leases with fencing.</b> A claimed row is leased for {@value #LEASE_SECONDS}s -- longer than a
 *       whole batch can take at worst (10 x (3s connect + 5s response) = 80s) -- and stamped with a fresh
 *       lease token. Every outcome is written {@code WHERE lease_token = ?}: if a lease did expire and
 *       another worker re-claimed the row, the late worker's write matches nothing instead of
 *       overwriting the newer outcome.</li>
 *   <li><b>Signatures</b> follow the widely used {@code t=<unix seconds>,v1=<hex HMAC-SHA256 of
 *       "t.body">} scheme: receivers reject timestamps older than a few minutes, so a captured delivery
 *       cannot be replayed later, and {@code X-Webhook-Event-Id} (stable across retries) de-duplicates.</li>
 *   <li><b>Retries</b> back off exponentially with jitter up to {@code webhook.max-attempts}; the row is
 *       then kept as FAILED, never silently dropped.</li>
 *   <li><b>Adaptive polling</b>: a full batch means a backlog, so the worker keeps draining within the
 *       tick (bounded) instead of waiting for the next one.</li>
 * </ul>
 */
@Configuration
@EnableScheduling
class WebhookWorkerConfig {
}

@Component
class WebhookDeliveryWorker {

    private static final Logger log = LoggerFactory.getLogger(WebhookDeliveryWorker.class);
    static final int BATCH = 10;
    static final int LEASE_SECONDS = 120;
    private static final int MAX_BATCHES_PER_TICK = 20;

    private final WorkerDb db;
    private final UrlGuard urlGuard;
    private final OutboundHttp http;
    private final SecretCipher cipher;
    private final int maxAttempts;
    private final Counter delivered;
    private final Counter failed;

    WebhookDeliveryWorker(WorkerDb db, UrlGuard urlGuard, OutboundHttp http, SecretCipher cipher,
                          @Value("${webhook.max-attempts:6}") int maxAttempts, MeterRegistry meters) {
        this.db = db;
        this.urlGuard = urlGuard;
        this.http = http;
        this.cipher = cipher;
        this.maxAttempts = maxAttempts;
        this.delivered = Counter.builder("webhook.deliveries").tag("outcome", "delivered").register(meters);
        this.failed = Counter.builder("webhook.deliveries").tag("outcome", "failed").register(meters);
    }

    @Scheduled(fixedDelayString = "${webhook.worker.poll-ms:2000}")
    void poll() {
        try {
            for (int batch = 0; batch < MAX_BATCHES_PER_TICK; batch++) {
                List<Map<String, Object>> rows = claim();
                for (Map<String, Object> row : rows) {
                    deliver(row);
                }
                if (rows.size() < BATCH) {
                    return;
                }
            }
        } catch (Exception e) {
            log.warn("Webhook poll failed; will retry next tick", e);
        }
    }

    private List<Map<String, Object>> claim() {
        return db.jdbc().queryForList("""
                UPDATE webhook_outbox o
                   SET status = 'IN_FLIGHT', attempts = attempts + 1,
                       locked_until = now() + make_interval(secs => ?), lease_token = gen_random_uuid()
                 WHERE o.id IN (SELECT id FROM webhook_outbox
                                 WHERE (status = 'PENDING' AND next_attempt_at <= now())
                                    OR (status = 'IN_FLIGHT' AND locked_until < now())
                                 ORDER BY next_attempt_at
                                 LIMIT ? FOR UPDATE SKIP LOCKED)
             RETURNING o.id, o.event_id, o.subscription_id, o.payload, o.attempts, o.lease_token
                """, LEASE_SECONDS, BATCH);
    }

    private void deliver(Map<String, Object> row) {
        long id = ((Number) row.get("id")).longValue();
        int attempts = ((Number) row.get("attempts")).intValue();
        UUID lease = (UUID) row.get("lease_token");
        try {
            Map<String, Object> sub = db.jdbc().queryForMap(
                    "SELECT url, secret, enabled FROM webhook_subscription WHERE id = ?", row.get("subscription_id"));
            if (!Boolean.TRUE.equals(sub.get("enabled"))) {
                finish(id, lease, "FAILED", "Subscription disabled");
                return;
            }
            URI uri = urlGuard.check((String) sub.get("url"));
            String body = (String) row.get("payload");
            long timestamp = Instant.now().getEpochSecond();
            String secret = cipher.decrypt((String) sub.get("secret"), SecretCipher.WEBHOOK_SECRET);
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("User-Agent", "ArthaDhruva-Webhooks/1.0");
            headers.put("X-Webhook-Event-Id", String.valueOf(row.get("event_id")));
            headers.put("X-Webhook-Timestamp", String.valueOf(timestamp));
            headers.put("X-Webhook-Signature", "t=" + timestamp + ",v1=" + sign(secret, timestamp + "." + body));
            int status = http.postJson(uri, headers, body);
            if (status / 100 == 2) {
                if (db.jdbc().update("UPDATE webhook_outbox SET status='DELIVERED', delivered_at=now(), locked_until=NULL, "
                        + "last_error=NULL WHERE id=? AND lease_token=?", id, lease) == 1) {
                    delivered.increment();
                }
            } else {
                retryOrFail(id, lease, attempts, "HTTP " + status);
            }
        } catch (Exception e) {
            retryOrFail(id, lease, attempts, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private void retryOrFail(long id, UUID lease, int attempts, String error) {
        String trimmed = error.length() > 250 ? error.substring(0, 250) : error;
        if (attempts >= maxAttempts) {
            if (finish(id, lease, "FAILED", trimmed)) {
                failed.increment();
                log.warn("Webhook outbox {} permanently failed after {} attempts: {}", id, attempts, trimmed);
            }
            return;
        }
        db.jdbc().update("UPDATE webhook_outbox SET status='PENDING', locked_until=NULL, last_error=?, "
                + "next_attempt_at = now() + make_interval(secs => ?) WHERE id=? AND lease_token=?",
                trimmed, backoffSeconds(attempts), id, lease);
    }

    private boolean finish(long id, UUID lease, String status, String error) {
        return db.jdbc().update("UPDATE webhook_outbox SET status=?, locked_until=NULL, last_error=? WHERE id=? AND lease_token=?",
                status, error, id, lease) == 1;
    }

    /** min(5 min, 5s * 2^attempts), then +-50% jitter (spreads retries instead of synchronizing them). */
    static double backoffSeconds(int attempts) {
        double base = Math.min(300.0, 5.0 * Math.pow(2, attempts));
        return base * (0.5 + ThreadLocalRandom.current().nextDouble());
    }

    static String sign(String secret, String signedPayload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(signedPayload.getBytes(StandardCharsets.UTF_8)));
    }
}
