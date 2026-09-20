CREATE TABLE automation_rule (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES organization(id),
    name VARCHAR(120) NOT NULL,
    trigger_event VARCHAR(32) NOT NULL CHECK (trigger_event IN ('LOAN_SCORED', 'LOAN_CASE_UPDATED')),
    condition_field VARCHAR(40) NOT NULL,
    condition_op VARCHAR(8) NOT NULL CHECK (condition_op IN ('GT', 'GTE', 'LT', 'LTE', 'EQ', 'NE')),
    condition_value VARCHAR(120) NOT NULL,
    actions TEXT NOT NULL,
    position INT NOT NULL DEFAULT 0,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX idx_automation_rule_lookup ON automation_rule(tenant_id, trigger_event, enabled, position);

ALTER TABLE automation_rule ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON automation_rule
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);
