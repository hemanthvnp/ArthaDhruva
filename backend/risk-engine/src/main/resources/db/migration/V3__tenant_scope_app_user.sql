ALTER TABLE app_user ADD COLUMN tenant_id BIGINT;

UPDATE app_user SET tenant_id = (SELECT id FROM organization WHERE slug = 'legacy');

ALTER TABLE app_user ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE app_user ADD CONSTRAINT fk_app_user_tenant FOREIGN KEY (tenant_id) REFERENCES organization(id);

-- Hibernate's ddl-auto=update generated a single-column UNIQUE constraint on username from the
-- old @Column(unique=true) mapping, under an implicit, non-deterministic name (a hash of
-- table+column, not something safe to hardcode here). Find and drop it dynamically rather than
-- guessing the name -- username is now unique only per-tenant, not globally.
DO $$
DECLARE
    constraint_name text;
BEGIN
    SELECT tc.constraint_name INTO constraint_name
    FROM information_schema.table_constraints tc
    JOIN information_schema.key_column_usage kcu
        ON tc.constraint_name = kcu.constraint_name AND tc.table_schema = kcu.table_schema
    WHERE tc.table_name = 'app_user'
      AND tc.constraint_type = 'UNIQUE'
      AND kcu.column_name = 'username'
    LIMIT 1;

    IF constraint_name IS NOT NULL THEN
        EXECUTE format('ALTER TABLE app_user DROP CONSTRAINT %I', constraint_name);
    END IF;
END $$;

ALTER TABLE app_user ADD CONSTRAINT uq_app_user_tenant_username UNIQUE (tenant_id, username);
CREATE INDEX idx_app_user_tenant ON app_user(tenant_id);
