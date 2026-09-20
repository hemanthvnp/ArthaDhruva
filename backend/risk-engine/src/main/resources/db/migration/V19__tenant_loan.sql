-- A tenant's own loan portfolio (uploaded by an admin or pushed by a core system through the ingest
-- API). A tenant with no rows here keeps seeing the shared demo catalog.
CREATE TABLE tenant_loan (
    tenant_id BIGINT NOT NULL REFERENCES organization(id),
    loan_id VARCHAR(80) NOT NULL,
    property_state VARCHAR(8) NOT NULL,
    features TEXT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, loan_id)
);
CREATE INDEX idx_tenant_loan_state ON tenant_loan(tenant_id, property_state);

ALTER TABLE tenant_loan ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenant_loan
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);
