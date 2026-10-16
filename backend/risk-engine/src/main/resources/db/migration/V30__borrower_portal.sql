-- Borrower portal (see openspec/changes/borrower-portal-and-safety-assistant).
--
-- A borrower is shown a status band and a sentence on how it has moved, never a probability. loan_score keeps
-- only the latest score, so the band it was in before, and since when the current band holds, are recorded
-- beside it on each upsert. All three columns are nullable: rows scored before this migration get them on
-- their next score, and nothing that read loan_score before reads them. No table is added there, so its
-- existing row-level-security policy covers the new columns.
ALTER TABLE loan_score
    ADD COLUMN band VARCHAR(8),
    ADD COLUMN band_since TIMESTAMPTZ,
    ADD COLUMN previous_band VARCHAR(8),
    ADD CONSTRAINT loan_score_band_valid CHECK (band IS NULL OR band IN ('LOW', 'MEDIUM', 'HIGH')),
    ADD CONSTRAINT loan_score_previous_band_valid CHECK (previous_band IS NULL OR previous_band IN ('LOW', 'MEDIUM', 'HIGH'));

-- Who a borrower contacts when their loan has no open case with an assigned analyst. One row per
-- organization, set by its admin; without a row the portal shows a generic "contact your lender" card.
CREATE TABLE borrower_contact (
    tenant_id BIGINT PRIMARY KEY REFERENCES organization(id),
    name VARCHAR(120) NOT NULL,
    title VARCHAR(120),
    email VARCHAR(254),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
ALTER TABLE borrower_contact ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON borrower_contact
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);
