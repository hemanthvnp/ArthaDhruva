CREATE TABLE organization (
    id BIGSERIAL PRIMARY KEY,
    slug VARCHAR(100) NOT NULL UNIQUE,
    name VARCHAR(255) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);

-- Every account/row that existed before multi-tenancy was introduced belongs to this tenant
-- (see V3 and later migrations' backfill). id = 1 deterministically: BIGSERIAL on a table that
-- was just created empty in this same migration.
INSERT INTO organization (slug, name) VALUES ('legacy', 'Legacy Organization');
