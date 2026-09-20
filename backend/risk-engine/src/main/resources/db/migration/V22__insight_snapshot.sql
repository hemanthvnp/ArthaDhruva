-- Latest result of a scheduled ML insight job (note topics, borrower segments) per tenant, so the
-- API serves a precomputed answer instead of clustering on every request.
CREATE TABLE insight_snapshot (
    tenant_id BIGINT NOT NULL REFERENCES organization(id),
    kind VARCHAR(30) NOT NULL,
    computed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    payload TEXT NOT NULL,
    PRIMARY KEY (tenant_id, kind)
);
ALTER TABLE insight_snapshot ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON insight_snapshot
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);
