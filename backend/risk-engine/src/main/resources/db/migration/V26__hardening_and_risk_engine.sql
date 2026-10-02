-- Hardening round: audit integrity, session revocation, TOTP replay protection, case controls,
-- idempotency scoping, webhook lease fencing, model traceability, once-per-period jobs, SSO linking,
-- note search, and portfolio risk snapshots for the survival / ECL engine.

-- 1. Audit trail: record who did what and with which outcome, and make the trail append-only for
--    the application role (it previously held UPDATE/DELETE on it like on every other table).
ALTER TABLE model_invocation_events
    ADD COLUMN actor VARCHAR(255),
    ADD COLUMN http_method VARCHAR(10),
    ADD COLUMN path VARCHAR(500),
    ADD COLUMN status_code INT,
    ADD COLUMN client_ip VARCHAR(64),
    ADD COLUMN model_version VARCHAR(64);
CREATE INDEX idx_model_invocation_events_tenant_time ON model_invocation_events (tenant_id, occurred_at DESC);
REVOKE UPDATE, DELETE, TRUNCATE ON model_invocation_events FROM arthadhruva_app;
REVOKE UPDATE, DELETE, TRUNCATE ON login_attempt FROM arthadhruva_app;

-- 2. Sessions. session_version is carried in every session token; bumping it (logout, password
--    change or reset, role change, deactivation) invalidates every token issued before. The TOTP time
--    step last accepted blocks replay of a code inside its validity window (RFC 6238 section 5.2).
ALTER TABLE app_user
    ADD COLUMN session_version INT NOT NULL DEFAULT 0,
    ADD COLUMN totp_last_step BIGINT,
    ADD COLUMN sso_issuer VARCHAR(300),
    ADD COLUMN sso_subject VARCHAR(255);
CREATE UNIQUE INDEX uk_app_user_sso_identity ON app_user (tenant_id, sso_issuer, sso_subject) WHERE sso_subject IS NOT NULL;

-- 3. Case workflow: who escalated (four-eyes rule on clearing an escalation) and an immutable history
--    of every transition.
ALTER TABLE loan_case ADD COLUMN escalated_by VARCHAR(255);
CREATE TABLE loan_case_event (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES organization(id),
    loan_id VARCHAR(255) NOT NULL,
    actor VARCHAR(255) NOT NULL,
    from_status VARCHAR(20),
    to_status VARCHAR(20) NOT NULL,
    from_assignee VARCHAR(255),
    to_assignee VARCHAR(255),
    from_flagged BOOLEAN,
    to_flagged BOOLEAN NOT NULL,
    reason VARCHAR(500),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_loan_case_event_loan ON loan_case_event (tenant_id, loan_id, occurred_at DESC);
ALTER TABLE loan_case_event ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON loan_case_event
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);
REVOKE UPDATE, DELETE, TRUNCATE ON loan_case_event FROM arthadhruva_app;

-- 4. Idempotency keys are scoped to the caller, not just the tenant, and each claim carries an owner
--    token so a stale takeover can never be completed or released by the original, slower request.
ALTER TABLE idempotency_key
    ADD COLUMN principal VARCHAR(255) NOT NULL DEFAULT '',
    ADD COLUMN owner_token UUID,
    ADD COLUMN body_withheld BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE idempotency_key DROP CONSTRAINT idempotency_key_pkey;
ALTER TABLE idempotency_key ADD PRIMARY KEY (tenant_id, principal, idem_key);

-- 5. Webhooks: signing secrets are now encrypted at rest (longer values), and each delivery lease
--    carries a token so a worker whose lease expired cannot overwrite the outcome of the next one.
ALTER TABLE webhook_subscription ALTER COLUMN secret TYPE VARCHAR(400);
ALTER TABLE webhook_outbox ADD COLUMN lease_token UUID;
ALTER TABLE org_sso_config ALTER COLUMN client_secret TYPE VARCHAR(800);

-- 6. Every persisted score records the model version that produced it (SR 11-7 traceability).
ALTER TABLE loan_score ADD COLUMN model_version VARCHAR(64);

-- 7. Once-per-period execution for scheduled jobs: an advisory lock only prevents overlap, so a
--    replica whose clock fires a moment after another finished would run the job again.
CREATE TABLE job_run (
    job_name VARCHAR(80) NOT NULL,
    period_key VARCHAR(40) NOT NULL,
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at TIMESTAMPTZ,
    PRIMARY KEY (job_name, period_key)
);

-- 8. SSO hand-off: the callback issues a single-use, 60-second code instead of putting a session token
--    in the browser URL; the frontend exchanges it. Only the code's SHA-256 is stored.
CREATE TABLE sso_handoff (
    code_hash CHAR(64) PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES organization(id),
    username VARCHAR(255) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL
);

-- 9. Substring note search used a sequential scan; a trigram index serves LIKE '%text%'.
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX idx_loan_note_text_trgm ON loan_note USING gin (lower(text) gin_trgm_ops);

-- 10. Attachment integrity.
ALTER TABLE loan_attachment ADD COLUMN content_sha256 VARCHAR(64);

-- 11. Portfolio risk. A run projects every loan under each scenario on a background worker; the
--     request that starts it returns at once and the client polls the run. heartbeat_at lets any replica
--     tell a live run from one whose worker died, and the partial unique index allows one active run
--     per organization. Each finished run stores one snapshot per scenario: the headline numbers as
--     columns (for the trend over time) and the monthly series, concentrations and top loans as JSON.
CREATE TABLE portfolio_risk_run (
    id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES organization(id),
    requested_by VARCHAR(255) NOT NULL,
    status VARCHAR(12) NOT NULL,
    scenarios VARCHAR(200) NOT NULL,
    loans_total INT NOT NULL,
    loans_done INT NOT NULL DEFAULT 0,
    requested_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at TIMESTAMPTZ,
    heartbeat_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at TIMESTAMPTZ,
    error VARCHAR(500)
);
CREATE UNIQUE INDEX uk_portfolio_risk_run_active ON portfolio_risk_run (tenant_id) WHERE status IN ('QUEUED', 'RUNNING');
CREATE INDEX idx_portfolio_risk_run_tenant ON portfolio_risk_run (tenant_id, requested_at DESC);
ALTER TABLE portfolio_risk_run ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON portfolio_risk_run
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);

CREATE TABLE portfolio_risk_snapshot (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES organization(id),
    as_of DATE NOT NULL,
    scenario VARCHAR(30) NOT NULL,
    loans INT NOT NULL,
    loans_excluded INT NOT NULL,
    sampled_loans INT NOT NULL,
    exposure NUMERIC(20, 2) NOT NULL,
    pd_12m NUMERIC(12, 8) NOT NULL,
    pd_lifetime NUMERIC(12, 8) NOT NULL,
    ecl_12m NUMERIC(20, 2) NOT NULL,
    ecl_lifetime NUMERIC(20, 2) NOT NULL,
    ecl_ifrs9 NUMERIC(20, 2) NOT NULL,
    stage1 INT NOT NULL,
    stage2 INT NOT NULL,
    stage3 INT NOT NULL,
    model_version VARCHAR(64) NOT NULL,
    detail JSONB NOT NULL,
    computed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, as_of, scenario)
);
ALTER TABLE portfolio_risk_snapshot ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON portfolio_risk_snapshot
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);

-- The loans behind the latest run's baseline totals: stage and allowance loan by loan (the listing an
-- auditor asks for), and the inputs of the portfolio loss simulation. Replaced as a whole by each run.
-- weight is how many portfolio loans a row stands for: 1, unless the portfolio was large enough to be
-- sampled.
CREATE TABLE portfolio_risk_loan (
    tenant_id BIGINT NOT NULL REFERENCES organization(id),
    loan_id VARCHAR(255) NOT NULL,
    as_of DATE NOT NULL,
    property_state VARCHAR(2) NOT NULL,
    exposure DOUBLE PRECISION NOT NULL,
    pd_12m DOUBLE PRECISION NOT NULL,
    pd_lifetime DOUBLE PRECISION NOT NULL,
    lgd DOUBLE PRECISION NOT NULL,
    ecl_12m DOUBLE PRECISION NOT NULL,
    ecl_lifetime DOUBLE PRECISION NOT NULL,
    ecl_ifrs9 DOUBLE PRECISION NOT NULL,
    stage SMALLINT NOT NULL,
    stage_reason VARCHAR(200) NOT NULL,
    weight DOUBLE PRECISION NOT NULL,
    PRIMARY KEY (tenant_id, loan_id)
);
CREATE INDEX idx_portfolio_risk_loan_ecl ON portfolio_risk_loan (tenant_id, ecl_lifetime DESC);
ALTER TABLE portfolio_risk_loan ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON portfolio_risk_loan
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);
