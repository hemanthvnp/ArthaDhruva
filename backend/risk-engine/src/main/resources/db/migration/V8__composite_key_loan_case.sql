-- Identical treatment to V7, for the other loanId-as-natural-key table.
ALTER TABLE loan_case ADD COLUMN tenant_id BIGINT;
UPDATE loan_case SET tenant_id = (SELECT id FROM organization WHERE slug = 'legacy');
ALTER TABLE loan_case ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE loan_case DROP CONSTRAINT loan_case_pkey;
ALTER TABLE loan_case ADD PRIMARY KEY (tenant_id, loan_id);
ALTER TABLE loan_case ADD CONSTRAINT fk_loan_case_tenant FOREIGN KEY (tenant_id) REFERENCES organization(id);
