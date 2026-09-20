-- From an EXPLAIN ANALYZE review at 200k rows per tenant: every "most recent N" list was a parallel
-- sequential scan of the whole tenant followed by a top-N sort (27-89 ms). Each index below matches
-- one query's tenant filter + sort order so Postgres reads exactly N index entries in order.
CREATE INDEX idx_loan_case_tenant_updated ON loan_case (tenant_id, updated_at DESC);
CREATE INDEX idx_loan_note_tenant_created ON loan_note (tenant_id, created_at DESC);
CREATE INDEX idx_loan_score_tenant_computed ON loan_score (tenant_id, computed_at DESC);
-- The notification inbox only ever reads visible (INSTANT) rows, so index only those.
CREATE INDEX idx_notification_inbox ON notification (tenant_id, recipient_username, created_at DESC) WHERE status = 'INSTANT';
