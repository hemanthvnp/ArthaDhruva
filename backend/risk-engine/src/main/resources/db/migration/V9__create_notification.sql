-- Brand-new feature, created tenant-scoped from day one -- unlike V3-V8, no baseline/backfill
-- dance is needed since no pre-Flyway row of this table has ever existed.
CREATE TABLE notification (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES organization(id),
    recipient_username VARCHAR(255) NOT NULL,
    message VARCHAR(500) NOT NULL,
    link VARCHAR(255),
    read BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_notification_recipient ON notification(tenant_id, recipient_username, created_at DESC);
