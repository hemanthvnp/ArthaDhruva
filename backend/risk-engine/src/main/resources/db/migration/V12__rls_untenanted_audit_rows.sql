-- INSERT ... RETURNING (which Hibernate uses to fetch generated ids) must also satisfy the table's
-- read policy, so the V11 policy that allowed writing an untenanted login_attempt (unresolvable
-- org slug) but not reading it back made every such insert fail. Widen the read side for these two
-- audit tables only: untenanted rows carry just the submitted username and are never returned by
-- any tenant-scoped repository query; every tenanted row stays strictly isolated.
DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['login_attempt','model_invocation_events']
    LOOP
        EXECUTE format('DROP POLICY tenant_isolation ON %I', t);
        EXECUTE format($p$CREATE POLICY tenant_isolation ON %I
            USING (tenant_id IS NULL OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
            WITH CHECK (tenant_id IS NULL OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)$p$, t);
    END LOOP;
END
$$;
