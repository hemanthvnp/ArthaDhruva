CREATE TABLE api_key (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES organization(id),
    name VARCHAR(80) NOT NULL,
    key_prefix CHAR(8) NOT NULL UNIQUE,
    key_hash CHAR(64) NOT NULL,
    created_by VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_used_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ
);
CREATE INDEX idx_api_key_tenant ON api_key(tenant_id);

ALTER TABLE api_key ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON api_key
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);

-- Authenticating an API key happens BEFORE any tenant is known (the key is what tells us), so the
-- normal tenant-scoped policy would hide every row from the lookup. A SECURITY DEFINER function
-- runs with its owner's rights (the owner is exempt from RLS) and does exactly one narrow thing:
-- match a non-revoked key by prefix + hash and return only its tenant id. The app role gets EXECUTE
-- on this function but still cannot read the api_key table across tenants.
CREATE FUNCTION api_key_authenticate(p_prefix text, p_hash text)
RETURNS TABLE (tenant_id bigint, key_id bigint)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    RETURN QUERY
    WITH hit AS (
        SELECT k.id, k.tenant_id AS tid FROM api_key k
        WHERE k.key_prefix = p_prefix AND k.key_hash = p_hash AND k.revoked_at IS NULL
    ), touched AS (
        UPDATE api_key k SET last_used_at = now()
        FROM hit WHERE k.id = hit.id AND (k.last_used_at IS NULL OR k.last_used_at < now() - interval '1 minute')
        RETURNING k.id
    )
    SELECT hit.tid, hit.id FROM hit;
END;
$$;
REVOKE ALL ON FUNCTION api_key_authenticate(text, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION api_key_authenticate(text, text) TO arthadhruva_app;
