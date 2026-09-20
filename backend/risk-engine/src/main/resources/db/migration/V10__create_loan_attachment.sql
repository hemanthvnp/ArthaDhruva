-- Brand-new feature, tenant-scoped from day one (same reasoning as V9__create_notification.sql).
CREATE TABLE loan_attachment (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES organization(id),
    loan_id VARCHAR(255) NOT NULL,
    filename VARCHAR(255) NOT NULL,
    content_type VARCHAR(255) NOT NULL,
    size_bytes BIGINT NOT NULL,
    storage_path VARCHAR(500) NOT NULL,
    uploaded_by VARCHAR(255) NOT NULL,
    uploaded_at TIMESTAMP(6) WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_loan_attachment_tenant_loan ON loan_attachment(tenant_id, loan_id);
