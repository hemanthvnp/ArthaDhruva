-- Nullable, no FK -- a login attempt against an unresolvable org slug can't be assigned a real
-- tenant, and that's exactly the enumeration signal worth keeping (see LoginAttempt's class doc).
-- Existing rows all predate multi-tenancy, so they unambiguously belong to the legacy org --
-- backfilled here, not left null (null is reserved for genuinely unresolvable attempts going
-- forward, not "happened before tenants existed").
ALTER TABLE login_attempt ADD COLUMN tenant_id BIGINT;
UPDATE login_attempt SET tenant_id = (SELECT id FROM organization WHERE slug = 'legacy');
CREATE INDEX idx_login_attempt_tenant ON login_attempt(tenant_id);
