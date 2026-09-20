package com.arthadhruva.riskengine.idempotency;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** Persistence for idempotency records (Postgres, not Redis: this is a correctness guarantee, so it
 * must survive a cache flush or restart). Every call runs under the request's tenant tag, so the
 * table's row-level security scopes it. */
@Component
class IdempotencyStore {

    static final int STALE_SECONDS = 60;

    record Stored(String requestHash, String state, Integer status, String contentType, String body) {
    }

    private final JdbcTemplate jdbc;

    IdempotencyStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Atomically claims the key. True = this caller executes the request; false = someone already did/is. */
    boolean claim(Long tenantId, String key, String requestHash) {
        return jdbc.update("INSERT INTO idempotency_key (tenant_id, idem_key, request_hash, state) "
                + "VALUES (?, ?, ?, 'IN_PROGRESS') ON CONFLICT DO NOTHING", tenantId, key, requestHash) == 1;
    }

    /** A crashed first attempt leaves IN_PROGRESS forever; after a grace period a retry may take it over. */
    boolean reclaimIfStale(Long tenantId, String key) {
        return jdbc.update("UPDATE idempotency_key SET created_at = now() WHERE tenant_id = ? AND idem_key = ? "
                + "AND state = 'IN_PROGRESS' AND created_at < now() - make_interval(secs => ?)",
                tenantId, key, STALE_SECONDS) == 1;
    }

    Stored find(Long tenantId, String key) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT request_hash, state, response_status, "
                + "response_content_type, response_body FROM idempotency_key WHERE tenant_id = ? AND idem_key = ?", tenantId, key);
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> r = rows.get(0);
        return new Stored((String) r.get("request_hash"), (String) r.get("state"),
                (Integer) r.get("response_status"), (String) r.get("response_content_type"), (String) r.get("response_body"));
    }

    void complete(Long tenantId, String key, int status, String contentType, String body) {
        jdbc.update("UPDATE idempotency_key SET state = 'DONE', response_status = ?, response_content_type = ?, "
                + "response_body = ? WHERE tenant_id = ? AND idem_key = ?", status, contentType, body, tenantId, key);
    }

    /** Server errors are not cached: a retry should be allowed to actually run again. */
    void release(Long tenantId, String key) {
        jdbc.update("DELETE FROM idempotency_key WHERE tenant_id = ? AND idem_key = ?", tenantId, key);
    }

    /** Deletes the current tenant's expired records (row-level security scopes the DELETE to the request's tenant). */
    int purgeOlderThanHours(int hours) {
        return jdbc.update("DELETE FROM idempotency_key WHERE created_at < now() - make_interval(hours => ?)", hours);
    }
}
