package com.arthadhruva.riskengine.sso;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/** Per-organization OIDC settings (V24). Callers run under the tenant context (row-level security). */
@Service
public class SsoConfigService {

    public record SsoConfig(String issuer, String clientId, String clientSecret, boolean enabled, boolean enforced) {
    }

    private final JdbcTemplate jdbc;

    public SsoConfigService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<SsoConfig> find(Long tenantId) {
        List<SsoConfig> rows = jdbc.query("SELECT issuer, client_id, client_secret, enabled, enforced FROM org_sso_config WHERE tenant_id = ?",
                (rs, i) -> new SsoConfig(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBoolean(4), rs.getBoolean(5)), tenantId);
        return rows.stream().findFirst();
    }

    public void save(Long tenantId, SsoConfig c) {
        jdbc.update("INSERT INTO org_sso_config (tenant_id, issuer, client_id, client_secret, enabled, enforced) VALUES (?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT (tenant_id) DO UPDATE SET issuer = EXCLUDED.issuer, client_id = EXCLUDED.client_id, "
                        + "client_secret = EXCLUDED.client_secret, enabled = EXCLUDED.enabled, enforced = EXCLUDED.enforced, updated_at = now()",
                tenantId, c.issuer(), c.clientId(), c.clientSecret(), c.enabled(), c.enforced());
    }

    /** True when local login must be refused for non-ADMIN users of this organization. */
    public boolean isEnforced(Long tenantId) {
        return find(tenantId).map(c -> c.enabled() && c.enforced()).orElse(false);
    }
}
