package com.arthadhruva.riskengine.apikey;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Tenant-scoped API keys for programmatic access. A key looks like {@code ak_<8 char prefix>_<32
 * char secret>}; only the prefix (a lookup handle) and a SHA-256 of the whole key are stored, so a
 * database leak does not leak usable keys. SHA-256 rather than bcrypt is deliberate: the secret is
 * about 190 bits of randomness, so there is nothing to brute-force and no need for a slow hash on
 * every request. The full key is shown to the admin exactly once, at creation.
 */
@Service
public class ApiKeyService {

    public record Created(long id, String key, String prefix) {
    }

    public record KeyView(long id, String name, String prefix, String createdBy, Instant createdAt,
                          Instant lastUsedAt, Instant revokedAt) {
    }

    public record Authenticated(long tenantId, long keyId) {
    }

    private static final String ALPHABET = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private final SecureRandom random = new SecureRandom();
    private final JdbcTemplate jdbc;

    public ApiKeyService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Created create(Long tenantId, String name, String createdBy) {
        String prefix = randomString(8);
        String key = "ak_" + prefix + "_" + randomString(32);
        long id = jdbc.queryForObject("INSERT INTO api_key (tenant_id, name, key_prefix, key_hash, created_by) "
                + "VALUES (?, ?, ?, ?, ?) RETURNING id", Long.class, tenantId, name, prefix, sha256(key), createdBy);
        return new Created(id, key, prefix);
    }

    public List<KeyView> list(Long tenantId) {
        return jdbc.query("SELECT id, name, key_prefix, created_by, created_at, last_used_at, revoked_at "
                + "FROM api_key WHERE tenant_id = ? ORDER BY id", (rs, i) -> new KeyView(rs.getLong("id"),
                rs.getString("name"), rs.getString("key_prefix"), rs.getString("created_by"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("last_used_at") == null ? null : rs.getTimestamp("last_used_at").toInstant(),
                rs.getTimestamp("revoked_at") == null ? null : rs.getTimestamp("revoked_at").toInstant()), tenantId);
    }

    public boolean revoke(Long tenantId, long id) {
        return jdbc.update("UPDATE api_key SET revoked_at = now() WHERE id = ? AND tenant_id = ? AND revoked_at IS NULL",
                id, tenantId) == 1;
    }

    /** Runs before any tenant is known, so it goes through the SECURITY DEFINER lookup (V18). */
    public Optional<Authenticated> authenticate(String presented) {
        if (presented == null || !presented.matches("ak_[A-Za-z0-9]{8}_[A-Za-z0-9]{32}")) {
            return Optional.empty();
        }
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT tenant_id, key_id FROM api_key_authenticate(?, ?)",
                presented.substring(3, 11), sha256(presented));
        return rows.isEmpty() ? Optional.empty()
                : Optional.of(new Authenticated(((Number) rows.get(0).get("tenant_id")).longValue(),
                        ((Number) rows.get(0).get("key_id")).longValue()));
    }

    private String randomString(int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
