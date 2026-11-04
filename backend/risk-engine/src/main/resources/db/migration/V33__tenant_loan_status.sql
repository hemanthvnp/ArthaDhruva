-- Where a stored loan stands now, so a portfolio run can project a late loan from where it is (the delinquency model)
-- instead of as a current one (openspec/changes/delinquency-aware-risk-and-uncertainty, task 7.4).
--
-- Both columns are optional and NULL for every loan stored before this migration: such a loan is projected exactly as it
-- was. The status comes with the upload (POST /v1/admin/portfolio, POST /v1/ingest/loans); a loan uploaded again without
-- one has its status cleared, since a stale status would be worse than none. tenant_loan already has row-level security
-- on tenant_id, which covers the new columns.

ALTER TABLE tenant_loan ADD COLUMN days_past_due INT CHECK (days_past_due IS NULL OR days_past_due BETWEEN 0 AND 3650);
-- The last 12 months of payment history as JSON (DelinquencyHistory); NULL when the organization has none to give.
ALTER TABLE tenant_loan ADD COLUMN delinquency_history TEXT;
