package com.arthadhruva.riskengine.retention;

import com.arthadhruva.riskengine.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The partitioned audit trail and the retention job (V31): what the app may and may not do to the trail,
 * where rows land, and that each retention rule removes what is past it and keeps what is not.
 */
class RetentionTest extends AbstractIntegrationTest {

    @Autowired JdbcTemplate app;   // the app role, as in production
    @Autowired RetentionJob job;
    @Value("${spring.datasource.url}") String url;
    @Value("${spring.flyway.user}") String ownerUser;
    @Value("${spring.flyway.password}") String ownerPassword;

    JdbcTemplate owner;
    long tenant;

    @BeforeEach
    void setUp() {
        owner = new JdbcTemplate(new DriverManagerDataSource(url, ownerUser, ownerPassword));
        tenant = owner.queryForObject("SELECT min(id) FROM organization", Long.class);
    }

    private long audit(String occurredAt) {
        return app.queryForObject("INSERT INTO model_invocation_events (endpoint, latency_ms, occurred_at, success) "
                + "VALUES ('RetentionTest', 1, " + occurredAt + ", true) RETURNING id", Long.class);
    }

    private String partitionOf(long id) {
        return app.queryForObject("SELECT tableoid::regclass::text FROM model_invocation_events WHERE id = ?", String.class, id);
    }

    private static String monthName(String expr, JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT 'model_invocation_events_' || to_char(" + expr + ", 'YYYY_MM')", String.class);
    }

    @Test
    void theTrailIsPartitionedByMonthAndAppendOnlyForTheApp() {
        assertEquals(1, owner.queryForObject("SELECT count(*) FROM pg_partitioned_table "
                + "WHERE partrelid = 'model_invocation_events'::regclass", Integer.class));
        for (int ahead = 0; ahead <= 3; ahead++) {
            String name = monthName("now() AT TIME ZONE 'UTC' + interval '" + ahead + " month'", owner);
            assertTrue(owner.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean.class, name), name);
        }

        long id = audit("now()");
        assertEquals(monthName("now() AT TIME ZONE 'UTC'", app), partitionOf(id));
        assertTrue(app.queryForObject("SELECT count(*) FROM model_invocation_events WHERE id = ?", Integer.class, id) == 1);

        // Append-only for the app, and no way round it through a partition.
        assertThrows(DataAccessException.class, () -> app.update("UPDATE model_invocation_events SET success = false WHERE id = ?", id));
        assertThrows(DataAccessException.class, () -> app.update("DELETE FROM model_invocation_events WHERE id = ?", id));
        assertThrows(DataAccessException.class, () -> app.queryForObject("SELECT count(*) FROM model_invocation_events_default", Long.class));
        String month = monthName("now() AT TIME ZONE 'UTC'", app);
        assertThrows(DataAccessException.class, () -> app.queryForObject("SELECT count(*) FROM " + month, Long.class));
        // And the maintenance it may run is only the maintenance it is given.
        assertThrows(DataAccessException.class, () -> app.queryForObject("SELECT audit_create_partition(date '2020-01-01')", Integer.class));
        assertThrows(DataAccessException.class, () -> app.execute("DROP TABLE " + month));
    }

    @Test
    void aMonthWithoutAPartitionLandsInTheDefaultAndMovesWhenItGetsOne() {
        long id = audit("now() + interval '8 months'");
        assertEquals("model_invocation_events_default", partitionOf(id));
        assertTrue(job.ensurePartitions() >= 0);   // three months ahead: not yet that month
        app.queryForObject("SELECT audit_ensure_partitions(9)", Integer.class);
        assertEquals(monthName("now() AT TIME ZONE 'UTC' + interval '8 months'", app), partitionOf(id));
    }

    @Test
    void monthsPastTheRetentionAreDroppedButNeverUnderAYear() {
        // A month fourteen months back, as an older database would have it.
        owner.queryForObject("SELECT audit_create_partition(date_trunc('month', now() AT TIME ZONE 'UTC' - interval '14 months')::date)", Integer.class);
        String old = monthName("now() AT TIME ZONE 'UTC' - interval '14 months'", owner);
        long inOldMonth = audit("now() - interval '14 months'");
        assertEquals(old, partitionOf(inOldMonth));
        long inDefault = audit("now() - interval '20 months'");   // no partition that far back: the default
        long recent = audit("now() - interval '2 months'");

        assertThrows(DataAccessException.class, () -> app.queryForObject("SELECT audit_drop_partitions(6)", Integer.class));
        assertTrue(app.queryForObject("SELECT audit_drop_partitions(12)", Integer.class) >= 1);

        assertNull(owner.queryForObject("SELECT to_regclass(?)::text", String.class, old));
        assertEquals(0, app.queryForObject("SELECT count(*) FROM model_invocation_events WHERE id IN (?, ?)", Integer.class,
                inOldMonth, inDefault));
        assertEquals(1, app.queryForObject("SELECT count(*) FROM model_invocation_events WHERE id = ?", Integer.class, recent));
    }

    @Test
    void expiredRowsGoAndCurrentOnesStay() {
        long subscription = owner.queryForObject("INSERT INTO webhook_subscription (tenant_id, url, event_types, secret) "
                + "VALUES (?, 'https://example.test/hook', 'LOAN_SCORED', 'x') RETURNING id", Long.class, tenant);
        String outbox = "INSERT INTO webhook_outbox (event_id, tenant_id, subscription_id, event_type, payload, status, created_at, "
                + "delivered_at) VALUES (?, ?, ?, 'LOAN_SCORED', '{}', ?, now() - ?::interval, now() - ?::interval) RETURNING id";
        long deliveredOld = owner.queryForObject(outbox, Long.class, UUID.randomUUID(), tenant, subscription, "DELIVERED", "40 days", "40 days");
        long deliveredNew = owner.queryForObject(outbox, Long.class, UUID.randomUUID(), tenant, subscription, "DELIVERED", "10 days", "10 days");
        long failedOld = owner.queryForObject(outbox, Long.class, UUID.randomUUID(), tenant, subscription, "FAILED", "100 days", "100 days");
        long pendingOld = owner.queryForObject(outbox, Long.class, UUID.randomUUID(), tenant, subscription, "PENDING", "100 days", "100 days");

        String notification = "INSERT INTO notification (tenant_id, recipient_username, message, read, created_at) "
                + "VALUES (?, 'retention-test', 'm', ?, now() - ?::interval) RETURNING id";
        long readOld = owner.queryForObject(notification, Long.class, tenant, true, "100 days");
        long unreadOld = owner.queryForObject(notification, Long.class, tenant, false, "100 days");
        long unreadAncient = owner.queryForObject(notification, Long.class, tenant, false, "400 days");

        long attemptOld = owner.queryForObject("INSERT INTO login_attempt (occurred_at, success, username) "
                + "VALUES (now() - interval '400 days', false, 'retention-test') RETURNING id", Long.class);
        long attemptNew = owner.queryForObject("INSERT INTO login_attempt (occurred_at, success, username) "
                + "VALUES (now() - interval '10 days', false, 'retention-test') RETURNING id", Long.class);

        assertTrue(job.purge() >= 5);

        List<Long> outboxLeft = owner.queryForList("SELECT id FROM webhook_outbox WHERE subscription_id = ? ORDER BY id", Long.class, subscription);
        assertEquals(List.of(deliveredNew, pendingOld), outboxLeft, "an undelivered row is never purged, however old");
        assertFalse(outboxLeft.contains(deliveredOld) || outboxLeft.contains(failedOld));
        List<Long> notificationsLeft = owner.queryForList("SELECT id FROM notification WHERE recipient_username = 'retention-test' "
                + "ORDER BY id", Long.class);
        assertEquals(List.of(unreadOld), notificationsLeft);
        assertFalse(notificationsLeft.contains(readOld) || notificationsLeft.contains(unreadAncient));
        assertEquals(List.of(attemptNew), owner.queryForList("SELECT id FROM login_attempt WHERE username = 'retention-test' ORDER BY id",
                Long.class));
        assertTrue(attemptOld > 0);

        // A setting under a floor is refused by the database, whatever the app is configured with.
        assertThrows(DataAccessException.class, () -> app.queryForObject("SELECT purge_expired_rows(100, 0, 90, 90, 365, 365, 30)", Integer.class));
    }
}
