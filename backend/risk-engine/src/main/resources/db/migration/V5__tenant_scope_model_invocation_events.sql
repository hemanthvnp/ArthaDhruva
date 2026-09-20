-- Same reasoning as V4: nullable, no FK -- a handful of endpoints this app's audit aspect wraps
-- can run without a resolved tenant in context. Existing rows predate multi-tenancy and
-- unambiguously belong to the legacy org.
ALTER TABLE model_invocation_events ADD COLUMN tenant_id BIGINT;
UPDATE model_invocation_events SET tenant_id = (SELECT id FROM organization WHERE slug = 'legacy');
CREATE INDEX idx_model_invocation_events_tenant ON model_invocation_events(tenant_id);
