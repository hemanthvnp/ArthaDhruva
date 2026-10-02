-- Replaces instant self-service signup: a prospect submits a request, and a PLATFORM_ADMIN
-- provisions the organization after the sales conversation. Not tenant-scoped (like
-- organization/plan): the row exists before any organization does.
CREATE TABLE access_request (
    id BIGSERIAL PRIMARY KEY,
    company_name VARCHAR(100) NOT NULL,
    contact_name VARCHAR(100) NOT NULL,
    work_email VARCHAR(255) NOT NULL,
    job_title VARCHAR(100),
    message VARCHAR(2000),
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    reviewed_at TIMESTAMP(6) WITH TIME ZONE,
    reviewed_by VARCHAR(255),
    organization_id BIGINT REFERENCES organization(id)
);

CREATE INDEX idx_access_request_status ON access_request(status, created_at DESC);
