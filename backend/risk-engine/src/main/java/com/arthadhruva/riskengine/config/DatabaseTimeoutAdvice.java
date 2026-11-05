package com.arthadhruva.riskengine.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.sql.SQLException;
import java.util.Map;

/**
 * A statement the database cancelled for running past {@code statement_timeout}, or one that gave up
 * waiting for a row lock ({@code lock_timeout}, or chosen as a deadlock victim), is the server shedding
 * load, not a bug in the request: answered with 503 and a short {@code Retry-After}, so a client backs off
 * and tries again, rather than a 500. Counted by kind ({@code db.timeouts}), which the
 * {@code DatabaseStatementTimeouts} alert watches.
 *
 * <p>Spring translates a cancelled statement (SQLSTATE 57014) to {@link QueryTimeoutException} on every
 * path, but a lock timeout (55P03) only through Hibernate; through a {@code JdbcTemplate} it arrives
 * uncategorized, so that case is recognised by its SQLSTATE.
 */
@RestControllerAdvice
public class DatabaseTimeoutAdvice {

    private static final Logger log = LoggerFactory.getLogger(DatabaseTimeoutAdvice.class);
    static final String RETRY_AFTER_SECONDS = "2";
    private static final String LOCK_NOT_AVAILABLE = "55P03";
    private static final String QUERY_CANCELED = "57014";
    private static final Map<String, String> BODY =
            Map.of("error", "The database is busy and did not answer in time. Try again in a moment.");

    private final Counter statementTimeouts;
    private final Counter lockTimeouts;

    public DatabaseTimeoutAdvice(MeterRegistry meters) {
        this.statementTimeouts = Counter.builder("db.timeouts").tag("kind", "statement").register(meters);
        this.lockTimeouts = Counter.builder("db.timeouts").tag("kind", "lock").register(meters);
    }

    @ExceptionHandler(QueryTimeoutException.class)
    public ResponseEntity<Map<String, String>> onStatementTimeout(QueryTimeoutException e) {
        statementTimeouts.increment();
        log.warn("A statement ran past the database's statement_timeout and was cancelled", e);
        return busy();
    }

    @ExceptionHandler(PessimisticLockingFailureException.class)
    public ResponseEntity<Map<String, String>> onLockTimeout(PessimisticLockingFailureException e) {
        lockTimeouts.increment();
        log.warn("A statement gave up waiting for a lock (lock_timeout or deadlock)", e);
        return busy();
    }

    @ExceptionHandler(UncategorizedSQLException.class)
    public ResponseEntity<Map<String, String>> onUncategorized(UncategorizedSQLException e) {
        String state = sqlState(e);
        if (LOCK_NOT_AVAILABLE.equals(state)) {
            return onLockTimeout(new PessimisticLockingFailureException(e.getMessage(), e));
        }
        if (QUERY_CANCELED.equals(state)) {
            return onStatementTimeout(new QueryTimeoutException(e.getMessage(), e));
        }
        throw e;
    }

    private static String sqlState(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
        }
        return null;
    }

    private static ResponseEntity<Map<String, String>> busy() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS).body(BODY);
    }
}
