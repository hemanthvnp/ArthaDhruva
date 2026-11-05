package com.arthadhruva.riskengine.config;

import com.arthadhruva.riskengine.AbstractIntegrationTest;
import com.arthadhruva.riskengine.scheduling.WorkerDb;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The server-side limits are on every pooled connection, and what the database does when one is reached
 * arrives as the exception {@link DatabaseTimeoutAdvice} answers with 503 (not as something it misses).
 */
class DatabaseTimeoutsTest extends AbstractIntegrationTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired WorkerDb workerDb;
    @Autowired TransactionTemplate tx;
    @Autowired DatabaseTimeoutAdvice advice;
    @Autowired MeterRegistry meters;

    private static String show(JdbcTemplate jdbc, String setting) {
        return jdbc.queryForObject("SHOW " + setting, String.class);
    }

    @Test
    void bothPoolsCarryTheLimits() {
        for (JdbcTemplate pool : new JdbcTemplate[] {jdbc, workerDb.jdbc()}) {
            assertEquals("30s", show(pool, "statement_timeout"));
            assertEquals("5s", show(pool, "lock_timeout"));
            assertEquals("1min", show(pool, "idle_in_transaction_session_timeout"));
        }
        // A RESET goes back to the limit, not to "no limit": they are the session's defaults.
        tx.executeWithoutResult(s -> {
            jdbc.execute("SET LOCAL statement_timeout = '1h'");
            jdbc.execute("RESET statement_timeout");
            assertEquals("30s", show(jdbc, "statement_timeout"));
        });
    }

    @Test
    void aStatementPastItsTimeIsCancelledAsAQueryTimeout() {
        assertThrows(QueryTimeoutException.class, () -> tx.executeWithoutResult(s -> {
            jdbc.execute("SET LOCAL statement_timeout = '100ms'");
            jdbc.execute("SELECT pg_sleep(2)");
        }));
    }

    @Test
    void aWaitPastTheLockTimeoutIsAnswered503() throws Exception {
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> tx.executeWithoutResult(s -> {
            jdbc.queryForObject("SELECT pg_advisory_xact_lock(424242)::text", String.class);
            held.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        try {
            assertTrue(held.await(10, TimeUnit.SECONDS));
            // Through JdbcTemplate a lock timeout is not translated to a locking failure; the advice knows it
            // by its SQLSTATE and still answers 503.
            UncategorizedSQLException e = assertThrows(UncategorizedSQLException.class, () -> tx.executeWithoutResult(s -> {
                jdbc.execute("SET LOCAL lock_timeout = '100ms'");
                jdbc.queryForObject("SELECT pg_advisory_xact_lock(424242)::text", String.class);
            }));
            double before = meters.counter("db.timeouts", "kind", "lock").count();
            assertEquals(503, advice.onUncategorized(e).getStatusCode().value());
            assertEquals(before + 1, meters.counter("db.timeouts", "kind", "lock").count());
        } finally {
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void bothAreAnsweredWith503AndCounted() {
        double before = meters.counter("db.timeouts", "kind", "statement").count();
        ResponseEntity<Map<String, String>> response = advice.onStatementTimeout(new QueryTimeoutException("cancelled"));
        assertEquals(503, response.getStatusCode().value());
        assertEquals(DatabaseTimeoutAdvice.RETRY_AFTER_SECONDS, response.getHeaders().getFirst("Retry-After"));
        assertEquals(before + 1, meters.counter("db.timeouts", "kind", "statement").count());
        assertEquals(503, advice.onLockTimeout(new PessimisticLockingFailureException("lock")).getStatusCode().value());
        // Any other uncategorized error is not this advice's to answer.
        UncategorizedSQLException other = new UncategorizedSQLException("x", "SELECT 1", new java.sql.SQLException("boom", "XX000"));
        assertThrows(UncategorizedSQLException.class, () -> advice.onUncategorized(other));
    }
}
