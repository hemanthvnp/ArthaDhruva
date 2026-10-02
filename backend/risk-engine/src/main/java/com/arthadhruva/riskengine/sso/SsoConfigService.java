package com.arthadhruva.riskengine.sso;

import com.arthadhruva.riskengine.security.SecretCipher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/** Per-organization OIDC settings (V24). Callers run under the tenant context (row-level security).
 * The client secret is stored encrypted ({@link SecretCipher}); a legacy plaintext row still reads and
 * is re-encrypted on the next save. */
@Service
public class SsoConfigService {

    public record SsoConfig(String issuer, String clientId, String clientSecret, boolean enabled, boolean enforced) {
    }

    private final JdbcTemplate jdbc;
    private final SecretCipher cipher;

    public SsoConfigService(JdbcTemplate jdbc, SecretCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    public Optional<SsoConfig> find(Long tenantId) {
        List<SsoConfig> rows = jdbc.query("SELECT issuer, client_id, client_secret, enabled, enforced FROM org_sso_config WHERE tenant_id = ?",
                (rs, i) -> new SsoConfig(rs.getString(1), rs.getString(2),
                        cipher.decrypt(rs.getString(3), SecretCipher.SSO_CLIENT_SECRET), rs.getBoolean(4), rs.getBoolean(5)), tenantId);
        return rows.stream().findFirst();
    }

    public void save(Long tenantId, SsoConfig c) {
        jdbc.update("INSERT INTO org_sso_config (tenant_id, issuer, client_id, client_secret, enabled, enforced) VALUES (?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT (tenant_id) DO UPDATE SET issuer = EXCLUDED.issuer, client_id = EXCLUDED.client_id, "
                        + "client_secret = EXCLUDED.client_secret, enabled = EXCLUDED.enabled, enforced = EXCLUDED.enforced, updated_at = now()",
                tenantId, c.issuer(), c.clientId(), cipher.encrypt(c.clientSecret(), SecretCipher.SSO_CLIENT_SECRET),
                c.enabled(), c.enforced());
    }

    /** True when local login must be refused for non-ADMIN users of this organization. */
    public boolean isEnforced(Long tenantId) {
        Boolean enforced = jdbc.query("SELECT enabled AND enforced FROM org_sso_config WHERE tenant_id = ?",
                rs -> rs.next() ? rs.getBoolean(1) : Boolean.FALSE, tenantId);
        return Boolean.TRUE.equals(enforced);
    }
}
