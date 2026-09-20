-- Contact email for account-recovery mail.
ALTER TABLE app_user ADD COLUMN email VARCHAR(200);

-- A platform-level operator role, distinct from a tenant's own ADMIN.
ALTER TABLE app_user DROP CONSTRAINT app_user_role_check;
ALTER TABLE app_user ADD CONSTRAINT app_user_role_check
    CHECK (role IN ('ANALYST', 'ADMIN', 'CLIENT', 'PLATFORM_ADMIN'));

CREATE TABLE plan (
    id SERIAL PRIMARY KEY,
    code VARCHAR(20) NOT NULL UNIQUE,
    name VARCHAR(60) NOT NULL,
    seat_limit INT NOT NULL CHECK (seat_limit > 0),
    rate_capacity INT NOT NULL CHECK (rate_capacity > 0),
    rate_per_second NUMERIC(8,2) NOT NULL CHECK (rate_per_second > 0),
    price_cents INT NOT NULL DEFAULT 0
);
INSERT INTO plan (code, name, seat_limit, rate_capacity, rate_per_second, price_cents) VALUES
    ('TRIAL',   'Trial',   5,   30,  10,   0),
    ('STARTER', 'Starter', 15,  100, 50,   4900),
    ('PRO',     'Pro',     200, 500, 250,  29900);

ALTER TABLE organization ADD COLUMN plan_id INT REFERENCES plan(id);
ALTER TABLE organization ADD COLUMN sandbox BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE organization ADD COLUMN trial_ends_at TIMESTAMPTZ;
ALTER TABLE organization ADD COLUMN billing_customer_id VARCHAR(80);
UPDATE organization SET plan_id = (SELECT id FROM plan WHERE code = 'STARTER') WHERE plan_id IS NULL;
ALTER TABLE organization ALTER COLUMN plan_id SET NOT NULL;

-- Billable usage, one counter per tenant / month / metric. Increments are a single atomic upsert.
CREATE TABLE usage_record (
    tenant_id BIGINT NOT NULL REFERENCES organization(id),
    period CHAR(7) NOT NULL,
    metric VARCHAR(30) NOT NULL,
    quantity BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (tenant_id, period, metric)
);
ALTER TABLE usage_record ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON usage_record
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);

-- New organizations land on STARTER unless the signup flow says otherwise (seeded ids are fixed).
ALTER TABLE organization ALTER COLUMN plan_id SET DEFAULT 2;
