CREATE TABLE webhook_subscription (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES organization(id),
    url VARCHAR(500) NOT NULL,
    event_types VARCHAR(200) NOT NULL,
    secret VARCHAR(80) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_webhook_subscription_tenant ON webhook_subscription(tenant_id, enabled);

-- Transactional outbox: a row is inserted in the SAME transaction as the state change that caused
-- it (BEFORE_COMMIT listener), so it exists iff the change committed. A separate worker delivers.
CREATE TABLE webhook_outbox (
    id BIGSERIAL PRIMARY KEY,
    event_id UUID NOT NULL,
    tenant_id BIGINT NOT NULL REFERENCES organization(id),
    subscription_id BIGINT NOT NULL REFERENCES webhook_subscription(id) ON DELETE CASCADE,
    event_type VARCHAR(40) NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(12) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'IN_FLIGHT', 'DELIVERED', 'FAILED')),
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    locked_until TIMESTAMPTZ,
    last_error VARCHAR(300),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    delivered_at TIMESTAMPTZ
);
-- Partial index: the worker's claim query only ever looks at undelivered rows.
CREATE INDEX idx_webhook_outbox_due ON webhook_outbox(next_attempt_at) WHERE status IN ('PENDING', 'IN_FLIGHT');
CREATE INDEX idx_webhook_outbox_tenant ON webhook_outbox(tenant_id, created_at DESC);

ALTER TABLE webhook_subscription ENABLE ROW LEVEL SECURITY;
ALTER TABLE webhook_outbox ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON webhook_subscription
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);
CREATE POLICY tenant_isolation ON webhook_outbox
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);

-- The delivery worker is cross-tenant by nature (it drains everyone's outbox), so it gets its own
-- role instead of weakening the app role: it can read subscriptions and read/update the outbox
-- and nothing else -- no other table is granted to it at all.
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'arthadhruva_worker') THEN
        CREATE ROLE arthadhruva_worker LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD '${workerDbPassword}';
    END IF;
END
$$;
GRANT USAGE ON SCHEMA public TO arthadhruva_worker;
GRANT SELECT ON webhook_subscription TO arthadhruva_worker;
GRANT SELECT, UPDATE ON webhook_outbox TO arthadhruva_worker;
CREATE POLICY worker_all_tenants ON webhook_subscription FOR SELECT TO arthadhruva_worker USING (true);
CREATE POLICY worker_all_tenants ON webhook_outbox TO arthadhruva_worker USING (true) WITH CHECK (true);
