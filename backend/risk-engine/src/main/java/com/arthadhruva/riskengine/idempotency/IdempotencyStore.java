package com.arthadhruva.riskengine.idempotency;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Persistence for idempotency records (Postgres, not Redis: this is a correctness guarantee and must
 * survive a cache flush). Keys are scoped to (tenant, principal): two users never share a key space.
 *
 * <p>Every claim carries an owner token. {@link #complete} and {@link #release} only act while the
 * caller still owns the record, so a slow original request whose record was taken over after
 * {@link #STALE_SECONDS} cannot overwrite or delete the new owner's result.
 */
@Component
class IdempotencyStore {

    /** Longer than any request is allowed to run (the slowest endpoints time out well before this). */
    static final int STALE_SECONDS = 120;

    record Stored(String requestHash, String state, Integer status, String contentType, String body, boolean bodyWithheld) {
    }

    private final JdbcTemplate jdbc;

    IdempotencyStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** True = this caller now owns the key and executes the request. */
    boolean claim(Long tenantId, String principal, String key, String requestHash, UUID owner) {
        return jdbc.update("INSERT INTO idempotency_key (tenant_id, principal, idem_key, request_hash, state, owner_token) "
                + "VALUES (?, ?, ?, ?, 'IN_PROGRESS', ?) ON CONFLICT DO NOTHING", tenantId, principal, key, requestHash, owner) == 1;
    }

    /** Takes over a record whose first attempt crashed -- only for the SAME request (same hash). */
    boolean reclaimIfStale(Long tenantId, String principal, String key, String requestHash, UUID owner) {
        return jdbc.update("UPDATE idempotency_key SET owner_token = ?, created_at = now() "
                        + "WHERE tenant_id = ? AND principal = ? AND idem_key = ? AND state = 'IN_PROGRESS' "
                        + "AND request_hash = ? AND created_at < now() - make_interval(secs => ?)",
                owner, tenantId, principal, key, requestHash, STALE_SECONDS) == 1;
    }

    Stored find(Long tenantId, String principal, String key) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT request_hash, state, response_status, "
                + "response_content_type, response_body, body_withheld FROM idempotency_key "
                + "WHERE tenant_id = ? AND principal = ? AND idem_key = ?", tenantId, principal, key);
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> r = rows.get(0);
        return new Stored((String) r.get("request_hash"), (String) r.get("state"), (Integer) r.get("response_status"),
                (String) r.get("response_content_type"), (String) r.get("response_body"),
                Boolean.TRUE.equals(r.get("body_withheld")));
    }

    void complete(Long tenantId, String principal, String key, UUID owner, int status, String contentType, String body,
                  boolean bodyWithheld) {
        jdbc.update("UPDATE idempotency_key SET state = 'DONE', response_status = ?, response_content_type = ?, "
                        + "response_body = ?, body_withheld = ? WHERE tenant_id = ? AND principal = ? AND idem_key = ? AND owner_token = ?",
                status, contentType, body, bodyWithheld, tenantId, principal, key, owner);
    }

    /** Server errors are not stored: a retry should be allowed to actually run again. */
    void release(Long tenantId, String principal, String key, UUID owner) {
        jdbc.update("DELETE FROM idempotency_key WHERE tenant_id = ? AND principal = ? AND idem_key = ? AND owner_token = ?",
                tenantId, principal, key, owner);
    }

    /** Deletes the current tenant's expired records (the caller sets the tenant; row-level security scopes it). */
    int purgeOlderThanHours(int hours) {
        return jdbc.update("DELETE FROM idempotency_key WHERE created_at < now() - make_interval(hours => ?)", hours);
    }
}
