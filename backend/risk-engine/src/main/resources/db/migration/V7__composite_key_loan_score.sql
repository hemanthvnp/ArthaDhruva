-- loan_id alone stops being a safe primary key the moment a second tenant can exist -- two
-- tenants scoring their own "loan L123" would otherwise collide on the same row. Postgres names
-- an unnamed PRIMARY KEY constraint "<table>_pkey" by its own convention (not Hibernate's), so
-- loan_score_pkey is safe to reference directly here, unlike the Hibernate-hash-named unique
-- constraint V3 had to look up dynamically.
ALTER TABLE loan_score ADD COLUMN tenant_id BIGINT;
UPDATE loan_score SET tenant_id = (SELECT id FROM organization WHERE slug = 'legacy');
ALTER TABLE loan_score ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE loan_score DROP CONSTRAINT loan_score_pkey;
ALTER TABLE loan_score ADD PRIMARY KEY (tenant_id, loan_id);
ALTER TABLE loan_score ADD CONSTRAINT fk_loan_score_tenant FOREIGN KEY (tenant_id) REFERENCES organization(id);
-- No extra index needed: the composite PK (tenant_id, loan_id) already serves tenant-only
-- filtering via the leftmost-prefix rule.
