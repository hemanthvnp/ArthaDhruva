ALTER TABLE loan_note ADD COLUMN tenant_id BIGINT;
UPDATE loan_note SET tenant_id = (SELECT id FROM organization WHERE slug = 'legacy');
ALTER TABLE loan_note ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE loan_note ADD CONSTRAINT fk_loan_note_tenant FOREIGN KEY (tenant_id) REFERENCES organization(id);
CREATE INDEX idx_loan_note_tenant_loan ON loan_note(tenant_id, loan_id);
