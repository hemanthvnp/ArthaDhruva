package com.arthadhruva.riskengine.config;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.sql.SQLException;
import java.util.Map;

/**
 * Maps write-write conflicts to 409 so a client can re-read and retry, rather than a 500 (or a
 * silent last-writer-wins overwrite): a stale {@code @Version} (two people edited the same case)
 * and a lost race to create the same row (unique violation, SQLSTATE 23505, on concurrent first
 * access). Other integrity violations (NOT NULL, FK, ...) are real bugs and still surface as 500s.
 */
@RestControllerAdvice
public class ConcurrencyConflictAdvice {

    private static final String UNIQUE_VIOLATION = "23505";
    private static final Map<String, String> BODY =
            Map.of("error", "This record was modified concurrently. Reload and try again.");

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<Map<String, String>> onStaleVersion(ObjectOptimisticLockingFailureException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(BODY);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, String>> onIntegrityViolation(DataIntegrityViolationException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && UNIQUE_VIOLATION.equals(sql.getSQLState())) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(BODY);
            }
        }
        throw e;
    }
}
