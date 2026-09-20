-- Each user's own dashboard layout: an ordered JSON array of widget ids.
CREATE TABLE dashboard_config (
    tenant_id BIGINT NOT NULL REFERENCES organization(id),
    username VARCHAR(100) NOT NULL,
    widgets TEXT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, username)
);
ALTER TABLE dashboard_config ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON dashboard_config
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);
