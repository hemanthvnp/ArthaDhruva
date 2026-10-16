-- Portfolio runs as a queue any replica works from, and checkpoints so a restarted run resumes.

-- 1. A QUEUED run is claimed by whichever replica has a free worker (FOR UPDATE SKIP LOCKED), and the
--    leading replica puts a run whose worker stopped reporting back in the queue. Both look across
--    organizations, which the app role must never do, so they use the worker role that already drains the
--    webhook outbox: it may read and update runs, and nothing else about a portfolio (not the loans, not
--    the results, not the checkpoints below).
GRANT SELECT, UPDATE ON portfolio_risk_run TO arthadhruva_worker;
CREATE POLICY worker_all_tenants ON portfolio_risk_run TO arthadhruva_worker USING (true) WITH CHECK (true);

-- 2. What a run has finished so far, a chunk of loans at a time. chunk_key is a hash of everything the
--    chunk's figures depend on (model version, forecast origin, scenarios, sampling scale, the loans), so a
--    later attempt reuses a chunk only if it would compute exactly the same thing. Rows live as long as
--    the run is active: they are removed when it completes or fails, and with the run row.
CREATE TABLE portfolio_risk_run_chunk (
    run_id UUID NOT NULL REFERENCES portfolio_risk_run(id) ON DELETE CASCADE,
    chunk_key VARCHAR(64) NOT NULL,
    tenant_id BIGINT NOT NULL REFERENCES organization(id),
    payload BYTEA NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (run_id, chunk_key)
);
CREATE INDEX idx_portfolio_risk_run_chunk_tenant ON portfolio_risk_run_chunk (tenant_id);
ALTER TABLE portfolio_risk_run_chunk ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON portfolio_risk_run_chunk
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);
