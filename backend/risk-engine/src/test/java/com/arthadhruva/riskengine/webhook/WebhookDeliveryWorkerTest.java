package com.arthadhruva.riskengine.webhook;

import com.arthadhruva.riskengine.AbstractIntegrationTest;
import com.arthadhruva.riskengine.scheduling.WorkerDb;
import com.arthadhruva.riskengine.security.SecretCipher;
import com.arthadhruva.riskengine.tenant.Organization;
import com.arthadhruva.riskengine.tenant.OrganizationService;
import com.arthadhruva.riskengine.tenant.TenantContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The delivery worker against the real outbox, as the worker role. The subscriptions point at a private
 * address, which the worker refuses before any connection is made, so nothing leaves the test: what is
 * exercised is the claim (rows of several subscriptions, across delivery threads), the lease and the
 * fenced write of the outcome.
 */
class WebhookDeliveryWorkerTest extends AbstractIntegrationTest {

    @Autowired WebhookDeliveryWorker worker;
    @Autowired OrganizationService organizations;
    @Autowired JdbcTemplate jdbc;
    @Autowired WorkerDb workerDb;
    @Autowired SecretCipher cipher;

    private <T> T inTenant(Organization org, Supplier<T> work) {
        TenantContext.set(org.getId());
        try {
            return work.get();
        } finally {
            TenantContext.clear();
        }
    }

    private long subscription(Organization org) {
        return inTenant(org, () -> jdbc.queryForObject("INSERT INTO webhook_subscription (tenant_id, url, event_types, secret) "
                + "VALUES (?, 'https://10.0.0.5/hook', 'LOAN_SCORED', 'not-read') RETURNING id", Long.class, org.getId()));
    }

    @Test
    void everyDueRowOfEverySubscriptionIsAttemptedOnceAndItsOutcomeRecorded() throws Exception {
        Organization org = organizations.create("hooks-" + UUID.randomUUID().toString().substring(0, 8), "hooks", false, null, null);
        List<Long> subscriptions = List.of(subscription(org), subscription(org), subscription(org));
        inTenant(org, () -> {
            for (long subscription : subscriptions) {
                for (int i = 0; i < 4; i++) {
                    jdbc.update("INSERT INTO webhook_outbox (event_id, tenant_id, subscription_id, event_type, payload) "
                            + "VALUES (?, ?, ?, 'LOAN_SCORED', '{}')", UUID.randomUUID(), org.getId(), subscription);
                }
            }
            return null;
        });

        List<Map<String, Object>> rows = List.of();
        for (int i = 0; i < 120; i++) {
            worker.poll();
            rows = inTenant(org, () -> jdbc.queryForList("SELECT status, attempts, last_error, locked_until, next_attempt_at > now() AS later "
                    + "FROM webhook_outbox WHERE tenant_id = ?", org.getId()));
            if (rows.stream().allMatch(row -> "PENDING".equals(row.get("status")) && ((Number) row.get("attempts")).intValue() >= 1)) {
                break;
            }
            Thread.sleep(250);
        }
        assertEquals(12, rows.size());
        for (Map<String, Object> row : rows) {
            assertEquals("PENDING", row.get("status"), "refused, so it is to be retried");
            assertEquals(1, ((Number) row.get("attempts")).intValue(), "attempted once: no row was claimed twice");
            assertNotNull(row.get("last_error"));
            assertTrue(row.get("locked_until") == null, "the lease was given up with the outcome");
            assertEquals(Boolean.TRUE, row.get("later"), "and the retry is backed off");
        }
    }

    /**
     * An endpoint that does not answer holds one delivery thread, not the worker: the other subscriptions'
     * events go out past it, and its own are sent one at a time, oldest first, once it answers.
     */
    @Test
    void aSlowEndpointDoesNotHoldUpTheOtherSubscriptions() throws Exception {
        Organization org = organizations.create("slow-" + UUID.randomUUID().toString().substring(0, 8), "slow", false, null, null);
        String secret = cipher.encrypt("signing-secret", SecretCipher.WEBHOOK_SECRET);
        long slow = subscription(org, "https://10.0.0.9/slow", secret);
        List<Long> fast = List.of(subscription(org, "https://10.0.0.10/a", secret), subscription(org, "https://10.0.0.10/b", secret));
        // The slow subscription's events are the oldest, so they are what the first claim reaches for.
        for (int i = 0; i < 3; i++) {
            event(org, slow, i);
        }
        for (long subscription : fast) {
            for (int i = 0; i < 3; i++) {
                event(org, subscription, i);
            }
        }

        CountDownLatch answer = new CountDownLatch(1);
        List<String> slowBodies = new CopyOnWriteArrayList<>();
        // No request leaves the test: the client is replaced, and with it the address check made on connecting.
        UrlGuard anyTarget = new UrlGuard(true);
        OutboundHttp stub = new OutboundHttp(anyTarget) {
            @Override
            public int postJson(URI uri, Map<String, String> headers, String body) throws IOException {
                if (uri.getPath().equals("/slow")) {
                    slowBodies.add(body);
                    try {
                        answer.await();
                    } catch (InterruptedException e) {
                        throw new IOException(e);
                    }
                }
                return 200;
            }
        };
        WebhookDeliveryWorker own = new WebhookDeliveryWorker(workerDb, anyTarget, stub, cipher, 6, 4, new SimpleMeterRegistry());
        try {
            own.poll();
            assertTrue(eventually(() -> delivered(org, fast.get(0)) == 3 && delivered(org, fast.get(1)) == 3),
                    "the other subscriptions were delivered while the slow endpoint had not answered");
            assertEquals(0, delivered(org, slow));
            assertEquals(1, slowBodies.size(), "one delivery to a subscription at a time");

            answer.countDown();
            assertTrue(eventually(() -> delivered(org, slow) == 3));
            assertEquals(List.of("{\"n\":0}", "{\"n\":1}", "{\"n\":2}"), slowBodies, "oldest first");
        } finally {
            answer.countDown();
            own.destroy();
            stub.destroy();
        }
    }

    private long subscription(Organization org, String url, String secret) {
        return inTenant(org, () -> jdbc.queryForObject("INSERT INTO webhook_subscription (tenant_id, url, event_types, secret) "
                + "VALUES (?, ?, 'LOAN_SCORED', ?) RETURNING id", Long.class, org.getId(), url, secret));
    }

    private void event(Organization org, long subscription, int n) {
        inTenant(org, () -> jdbc.update("INSERT INTO webhook_outbox (event_id, tenant_id, subscription_id, event_type, payload) "
                + "VALUES (?, ?, ?, 'LOAN_SCORED', ?)", UUID.randomUUID(), org.getId(), subscription, "{\"n\":" + n + "}"));
    }

    private int delivered(Organization org, long subscription) {
        return inTenant(org, () -> jdbc.queryForObject("SELECT count(*) FROM webhook_outbox WHERE subscription_id = ? AND status = 'DELIVERED'",
                Integer.class, subscription));
    }

    private static boolean eventually(BooleanSupplier condition) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(100);
        }
        return false;
    }
}
