ALTER TABLE notification ADD COLUMN event_type VARCHAR(30) NOT NULL DEFAULT 'GENERAL';
-- INSTANT: visible now. QUEUED: waiting for the user's next digest. DIGESTED: folded into a digest.
ALTER TABLE notification ADD COLUMN status VARCHAR(10) NOT NULL DEFAULT 'INSTANT'
    CHECK (status IN ('INSTANT', 'QUEUED', 'DIGESTED'));
CREATE INDEX idx_notification_queued ON notification(tenant_id, recipient_username) WHERE status = 'QUEUED';

CREATE TABLE notification_preference (
    tenant_id BIGINT NOT NULL REFERENCES organization(id),
    username VARCHAR(100) NOT NULL,
    event_type VARCHAR(30) NOT NULL,
    mode VARCHAR(10) NOT NULL CHECK (mode IN ('INSTANT', 'DIGEST', 'OFF')),
    PRIMARY KEY (tenant_id, username, event_type)
);
ALTER TABLE notification_preference ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON notification_preference
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);
