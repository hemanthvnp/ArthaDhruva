-- Idempotency-Key support: a client may safely retry a mutating request; the first execution's
-- response is stored and replayed instead of executing the mutation twice.
CREATE TABLE idempotency_key (
    tenant_id BIGINT NOT NULL REFERENCES organization(id),
    idem_key VARCHAR(120) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    state VARCHAR(12) NOT NULL CHECK (state IN ('IN_PROGRESS', 'DONE')),
    response_status INT,
    response_content_type VARCHAR(100),
    response_body TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, idem_key)
);
CREATE INDEX idx_idempotency_created ON idempotency_key(created_at);
ALTER TABLE idempotency_key ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON idempotency_key
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);
