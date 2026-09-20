-- Per-organization OIDC single sign-on. `enforced` makes SSO mandatory for everyone except ADMINs,
-- who keep local password + 2FA login as the break-glass path if the IdP is down or misconfigured.
CREATE TABLE org_sso_config (
    tenant_id BIGINT PRIMARY KEY REFERENCES organization(id),
    issuer VARCHAR(300) NOT NULL,
    client_id VARCHAR(200) NOT NULL,
    client_secret VARCHAR(300) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    enforced BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
ALTER TABLE org_sso_config ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON org_sso_config
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);
