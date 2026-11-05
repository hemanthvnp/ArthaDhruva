-- Realized outcomes, so stored scores can be compared with what happened (openspec/changes/model-risk-governance,
-- "a scheduled comparison of stored scores with realized outcomes").
--
-- This deployment has no servicing feed, so the outcome source is the organization's own: an administrator records, per
-- loan, whether it later defaulted, prepaid, or was last seen performing, and when (POST /v1/admin/outcomes). The
-- comparison is a report: a deferred job inserts a QUEUED row per organization and a compute thread works it, so the
-- scheduler thread only ever inserts.

CREATE TABLE loan_outcome (
    tenant_id   BIGINT       NOT NULL REFERENCES organization(id),
    loan_id     VARCHAR(255) NOT NULL,
    -- DEFAULTED and PREPAID: the date it happened. ACTIVE: the date the loan was last seen performing.
    outcome     VARCHAR(10)  NOT NULL CHECK (outcome IN ('DEFAULTED', 'PREPAID', 'ACTIVE')),
    observed_on DATE         NOT NULL,
    recorded_by VARCHAR(255),
    recorded_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, loan_id)
);
ALTER TABLE loan_outcome ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON loan_outcome
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);

CREATE TABLE score_calibration_report (
    id             UUID         PRIMARY KEY,
    tenant_id      BIGINT       NOT NULL REFERENCES organization(id),
    status         VARCHAR(10)  NOT NULL CHECK (status IN ('QUEUED', 'RUNNING', 'COMPLETED', 'FAILED')),
    requested_by   VARCHAR(255) NOT NULL,
    horizon_months INT          NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    started_at     TIMESTAMPTZ,
    finished_at    TIMESTAMPTZ,
    error          TEXT,
    report         JSONB
);
-- One report in flight per organization, enforced by the index and not by a check in the code, as for portfolio runs.
CREATE UNIQUE INDEX one_active_calibration_report ON score_calibration_report (tenant_id) WHERE status IN ('QUEUED', 'RUNNING');
CREATE INDEX idx_score_calibration_report_tenant ON score_calibration_report (tenant_id, created_at DESC);
ALTER TABLE score_calibration_report ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON score_calibration_report
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);
