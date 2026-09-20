-- Defense in depth, DB layer: even a query that bypasses the ORM (native SQL, a missed
-- tenantId parameter, a future bug) cannot read or write another tenant's rows, because Postgres
-- itself filters them. The app connects as arthadhruva_app, a non-owner, non-superuser role
-- (superusers and table owners bypass RLS), and tags each connection with app.tenant_id (see
-- TenantAwareDataSource). Migrations keep running as the owner role.

DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'arthadhruva_app') THEN
        CREATE ROLE arthadhruva_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD '${appDbPassword}';
    END IF;
END
$$;

-- Least privilege: DML only. No DDL, no TRUNCATE, no ownership.
GRANT USAGE ON SCHEMA public TO arthadhruva_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO arthadhruva_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO arthadhruva_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO arthadhruva_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO arthadhruva_app;
-- Flyway's own history table is not the app's business.
REVOKE ALL ON flyway_schema_history FROM arthadhruva_app;

-- Strict tables: rows are visible/writable only when tenant_id matches the connection's tenant.
-- current_setting(..., true) yields NULL/'' when unset, so an untagged connection sees nothing.
DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['app_user','loan_score','loan_case','loan_note','notification','loan_attachment']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format($p$CREATE POLICY tenant_isolation ON %I
            USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
            WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)$p$, t);
    END LOOP;
END
$$;

-- Audit-style tables: tenant_id is nullable (e.g. a login attempt against an unresolvable org
-- slug is still recorded). Untenanted rows may be inserted but never read back by any tenant.
DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['login_attempt','model_invocation_events']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format($p$CREATE POLICY tenant_isolation ON %I
            USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
            WITH CHECK (tenant_id IS NULL OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)$p$, t);
    END LOOP;
END
$$;

-- user_loan_id has no tenant_id of its own; it inherits isolation through app_user, which is
-- itself RLS-filtered inside this subquery.
ALTER TABLE user_loan_id ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON user_loan_id
    USING (EXISTS (SELECT 1 FROM app_user u WHERE u.id = user_loan_id.user_id))
    WITH CHECK (EXISTS (SELECT 1 FROM app_user u WHERE u.id = user_loan_id.user_id));
