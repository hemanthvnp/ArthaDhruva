package com.arthadhruva.riskengine.webhook;

import com.arthadhruva.riskengine.scheduling.WorkerDb;
import com.arthadhruva.riskengine.security.SecretCipher;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
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
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Transactional Outbox, delivery side: at-least-once, safe on any number of replicas.
 *
 * <ul>
 *   <li><b>Claiming</b> uses {@code FOR UPDATE SKIP LOCKED}: concurrent workers take disjoint rows. The
 *       claim commits before any HTTP call, so no row lock is held across the network.</li>
 *   <li><b>Leases with fencing.</b> A claimed row is leased for {@value #LEASE_SECONDS}s -- longer than the
 *       rows of one claim can take at worst (never more of them than there are delivery threads, each
 *       3s connect + 5s response) -- and stamped with a fresh lease token. Every outcome is written
 *       {@code WHERE lease_token = ?}: if a lease did expire and another worker re-claimed the row, the
 *       late worker's write matches nothing instead of overwriting the newer outcome.</li>
 *   <li><b>One slow endpoint does not hold up the rest.</b> Deliveries run on a few threads, and a
 *       subscription that has a delivery in progress here is left out of the next claim: an endpoint
 *       that takes its full timeout on every call occupies one thread, while the other subscriptions
 *       are delivered past it. A subscription's own events still go out one at a time, in order.</li>
 *   <li><b>Signatures</b> follow the widely used {@code t=<unix seconds>,v1=<hex HMAC-SHA256 of
 *       "t.body">} scheme: receivers reject timestamps older than a few minutes, so a captured delivery
 *       cannot be replayed later, and {@code X-Webhook-Event-Id} (stable across retries) de-duplicates.</li>
 *   <li><b>Retries</b> back off exponentially with jitter up to {@code webhook.max-attempts}; the row is
 *       then kept as FAILED, never silently dropped.</li>
 *   <li><b>Draining</b>: a thread that finishes claims again at once, so a backlog is worked without
 *       waiting for the next tick; the tick only starts things when there was nothing to do.</li>
 * </ul>
 */
@Configuration
@EnableScheduling
class WebhookWorkerConfig {
}

@Component
class WebhookDeliveryWorker implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(WebhookDeliveryWorker.class);
    static final int LEASE_SECONDS = 120;

    private final WorkerDb db;
    private final UrlGuard urlGuard;
    private final OutboundHttp http;
    private final SecretCipher cipher;
    private final int maxAttempts;
    private final int concurrency;
    private final ExecutorService deliveries;
    /** Delivery threads in use. */
    private final AtomicInteger inFlight = new AtomicInteger();
    /** Subscriptions with a delivery in progress on this replica. */
    private final Set<Long> busy = ConcurrentHashMap.newKeySet();
    private final Counter delivered;
    private final Counter failed;

    WebhookDeliveryWorker(WorkerDb db, UrlGuard urlGuard, OutboundHttp http, SecretCipher cipher,
                          @Value("${webhook.max-attempts:6}") int maxAttempts,
                          @Value("${webhook.worker.concurrency:4}") int concurrency, MeterRegistry meters) {
        this.db = db;
        this.urlGuard = urlGuard;
        this.http = http;
        this.cipher = cipher;
        this.maxAttempts = maxAttempts;
        this.concurrency = Math.max(1, concurrency);
        AtomicInteger threads = new AtomicInteger();
        this.deliveries = Executors.newFixedThreadPool(this.concurrency, runnable -> {
            Thread thread = new Thread(runnable, "webhook-delivery-" + threads.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        this.delivered = Counter.builder("webhook.deliveries").tag("outcome", "delivered").register(meters);
        this.failed = Counter.builder("webhook.deliveries").tag("outcome", "failed").register(meters);
    }

    @Scheduled(fixedDelayString = "${webhook.worker.poll-ms:2000}")
    void poll() {
        try {
            pump();
        } catch (Exception e) {
            log.warn("Webhook poll failed; will retry next tick", e);
        }
    }

    /**
     * Claims as many due rows as there are free delivery threads and starts them. One caller at a time, so
     * two threads finishing together cannot both claim for the same free thread.
     */
    private synchronized void pump() {
        int free = concurrency - inFlight.get();
        if (free <= 0) {
            return;
        }
        // The rows of one subscription go to one thread, in the order they were due.
        Map<Long, List<Map<String, Object>>> bySubscription = new LinkedHashMap<>();
        for (Map<String, Object> row : claim(free)) {
            bySubscription.computeIfAbsent(((Number) row.get("subscription_id")).longValue(), id -> new ArrayList<>()).add(row);
        }
        bySubscription.forEach((subscription, rows) -> {
            busy.add(subscription);
            inFlight.incrementAndGet();
            try {
                deliveries.execute(() -> {
                    try {
                        rows.forEach(this::deliver);
                    } finally {
                        busy.remove(subscription);
                        inFlight.decrementAndGet();
                        poll();
                    }
                });
            } catch (RejectedExecutionException e) {
                // Shutting down: the rows stay leased and are claimed again when the lease runs out.
                busy.remove(subscription);
                inFlight.decrementAndGet();
            }
        });
    }

    private List<Map<String, Object>> claim(int limit) {
        return db.jdbc().queryForList("""
                UPDATE webhook_outbox o
                   SET status = 'IN_FLIGHT', attempts = attempts + 1,
                       locked_until = now() + make_interval(secs => ?), lease_token = gen_random_uuid()
                 WHERE o.id IN (SELECT id FROM webhook_outbox
                                 WHERE ((status = 'PENDING' AND next_attempt_at <= now())
                                     OR (status = 'IN_FLIGHT' AND locked_until < now()))
                                   AND NOT (subscription_id = ANY (?))
                                 ORDER BY next_attempt_at
                                 LIMIT ? FOR UPDATE SKIP LOCKED)
             RETURNING o.id, o.event_id, o.subscription_id, o.payload, o.attempts, o.lease_token, o.next_attempt_at
                """, LEASE_SECONDS, busy.toArray(new Long[0]), limit).stream()
                // RETURNING gives the rows in no particular order; a subscription's events go out oldest first.
                .sorted((a, b) -> ((java.sql.Timestamp) a.get("next_attempt_at")).compareTo((java.sql.Timestamp) b.get("next_attempt_at")))
                .toList();
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
        try {
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
        } catch (RuntimeException e) {
            // The outcome could not be recorded: the row stays leased and is claimed again when the lease runs out.
            log.warn("Could not record the outcome of webhook outbox {}: {}", id, e.toString());
        }
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

    @Override
    public void destroy() {
        deliveries.shutdownNow();
    }
}
