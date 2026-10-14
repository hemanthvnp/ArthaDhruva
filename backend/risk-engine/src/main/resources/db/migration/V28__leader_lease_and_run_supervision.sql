-- Leader election and supervised portfolio runs.

-- 1. One lease row per elected role. A replica holds the role while its lease is unexpired and renews it
--    well before it lapses; another replica takes over only after expiry. fencing_token grows by one on
--    every change of holder, so work started under an old term can be told from the current one. Not a
--    tenant table (no tenant_id, no row-level security), like job_run.
CREATE TABLE leader_lease (
    name VARCHAR(80) PRIMARY KEY,
    holder VARCHAR(80) NOT NULL,
    fencing_token BIGINT NOT NULL DEFAULT 1,
    expires_at TIMESTAMPTZ NOT NULL
);

-- 2. Portfolio runs become restartable. request holds what is needed to run again on any replica (the
--    scenarios and the region-analysis plan; the loans are read from the portfolio). attempt is the run's
--    fencing token: a worker writes only WHERE attempt = the one it was started with, so a worker that was
--    slow rather than dead cannot overwrite the attempt that replaced it. deferred marks a run that waits
--    for the engine to be idle (or for the off-peak window) instead of competing with interactive runs; it
--    is still QUEUED, so the one-active-run-per-organization index covers it unchanged.
ALTER TABLE portfolio_risk_run
    ADD COLUMN attempt INT NOT NULL DEFAULT 1,
    ADD COLUMN deferred BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN request JSONB;
