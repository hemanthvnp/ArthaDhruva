-- Rough-set regions for a portfolio run (see openspec/changes/rough-set-boundary-regions).
--
-- A run that asks for regions judges every loan under one target scenario and stores, beside the loan's
-- baseline result, which of three regions it fell in and how far the variants of the stress disagreed. The
-- run's aggregates (region split, boundary exposure by state, the loss range) go on the target scenario's
-- snapshot. Every column is nullable: a run without regions leaves them empty, and nothing that read these
-- tables before reads the new columns. No table is added, so the row-level-security policies already on
-- portfolio_risk_loan and portfolio_risk_snapshot cover the new columns, and no grant changes.
ALTER TABLE portfolio_risk_loan
    ADD COLUMN region VARCHAR(16),
    ADD COLUMN region_high SMALLINT,
    ADD COLUMN region_members SMALLINT,
    ADD COLUMN region_metric_min DOUBLE PRECISION,
    ADD COLUMN region_metric_max DOUBLE PRECISION,
    ADD COLUMN region_ecl_min DOUBLE PRECISION,
    ADD COLUMN region_ecl_max DOUBLE PRECISION,
    ADD COLUMN region_extrapolated BOOLEAN,
    ADD CONSTRAINT portfolio_risk_loan_region_valid CHECK (region IS NULL OR region IN ('CERTAIN_HIGH', 'CERTAIN_LOW', 'BOUNDARY'));

CREATE INDEX idx_portfolio_risk_loan_region ON portfolio_risk_loan (tenant_id, region, ecl_lifetime DESC) WHERE region IS NOT NULL;

ALTER TABLE portfolio_risk_snapshot ADD COLUMN decision JSONB;
