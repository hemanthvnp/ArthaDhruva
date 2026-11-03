-- Retention, so the tables that only ever grow stop doing so, and monthly partitions for the audit trail.
--
-- 1. The audit trail (model_invocation_events) becomes a table partitioned by month on occurred_at. Old
--    months are then dropped whole, at a cost that does not depend on their size, instead of deleted row
--    by row (which the app role cannot do anyway: the trail is append-only for it, V26); and a query for
--    a time window reads only the months it covers. A DEFAULT partition takes any row whose month has no
--    partition yet, so an insert never fails for want of one.
-- 2. Functions that do the maintenance as the owner (SECURITY DEFINER), each with a floor under its
--    retention so a mistaken setting cannot empty a table, and each working in bounded batches so one
--    call stays well inside statement_timeout. RetentionJob calls them, once a day, on one replica.
--
-- Postgres 16 has no identity columns on partitioned tables (17 does), so the id comes from a sequence
-- with the same effect: inserts leave id out and read it back with RETURNING.

ALTER TABLE model_invocation_events RENAME TO model_invocation_events_old;
ALTER INDEX IF EXISTS idx_model_invocation_events_tenant RENAME TO idx_model_invocation_events_old_tenant;
ALTER INDEX IF EXISTS idx_model_invocation_events_tenant_time RENAME TO idx_model_invocation_events_old_tenant_time;

CREATE SEQUENCE model_invocation_events_seq;
SELECT setval('model_invocation_events_seq', COALESCE((SELECT max(id) FROM model_invocation_events_old), 0) + 1, false);

CREATE TABLE model_invocation_events (
    id            BIGINT                   NOT NULL DEFAULT nextval('model_invocation_events_seq'),
    endpoint      VARCHAR(255)             NOT NULL,
    error_message TEXT,
    latency_ms    BIGINT                   NOT NULL,
    occurred_at   TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    request_json  TEXT,
    response_json TEXT,
    success       BOOLEAN                  NOT NULL,
    tenant_id     BIGINT,
    actor         VARCHAR(255),
    http_method   VARCHAR(10),
    path          VARCHAR(500),
    status_code   INT,
    client_ip     VARCHAR(64),
    model_version VARCHAR(64),
    PRIMARY KEY (id, occurred_at)
) PARTITION BY RANGE (occurred_at);
ALTER SEQUENCE model_invocation_events_seq OWNED BY model_invocation_events.id;

CREATE TABLE model_invocation_events_default PARTITION OF model_invocation_events DEFAULT;
CREATE INDEX idx_model_invocation_events_tenant_time ON model_invocation_events (tenant_id, occurred_at DESC);

-- Creates the month partitions from this month to months_ahead months on. A month that already has rows
-- in the DEFAULT partition gets them moved into its own partition first (attaching checks the default
-- holds none of that month). Returns how many partitions it created.
CREATE FUNCTION audit_ensure_partitions(months_ahead INT) RETURNS INT
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    first_month DATE := date_trunc('month', now() AT TIME ZONE 'UTC')::date;
    created INT := 0;
BEGIN
    IF months_ahead < 0 OR months_ahead > 24 THEN
        RAISE EXCEPTION 'months_ahead must be between 0 and 24, not %', months_ahead;
    END IF;
    FOR i IN 0..months_ahead LOOP
        created := created + audit_create_partition((first_month + make_interval(months => i))::date);
    END LOOP;
    RETURN created;
END
$$;

CREATE FUNCTION audit_create_partition(month DATE) RETURNS INT
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    name TEXT := format('model_invocation_events_%s', to_char(month, 'YYYY_MM'));
    lo TIMESTAMPTZ := month::timestamp AT TIME ZONE 'UTC';
    hi TIMESTAMPTZ := (month + interval '1 month')::timestamp AT TIME ZONE 'UTC';
BEGIN
    IF date_trunc('month', month)::date <> month THEN
        RAISE EXCEPTION 'a partition starts on the first of a month, not %', month;
    END IF;
    IF to_regclass(name) IS NOT NULL THEN
        RETURN 0;
    END IF;
    EXECUTE format('CREATE TABLE %I (LIKE model_invocation_events INCLUDING DEFAULTS INCLUDING CONSTRAINTS)', name);
    EXECUTE format('WITH moved AS (DELETE FROM model_invocation_events_default WHERE occurred_at >= %L AND occurred_at < %L RETURNING *) '
                   'INSERT INTO %I SELECT * FROM moved', lo, hi, name);
    EXECUTE format('ALTER TABLE model_invocation_events ATTACH PARTITION %I FOR VALUES FROM (%L) TO (%L)', name, lo, hi);
    -- The app reaches the trail only through the parent, whose grants and row-level security apply; a
    -- partition read directly would bypass both, so the app has no grant on any partition.
    EXECUTE format('REVOKE ALL ON %I FROM arthadhruva_app', name);
    RETURN 1;
END
$$;

-- Drops the month partitions that ended more than keep_months months ago, and deletes rows that old
-- from the DEFAULT partition. The floor of twelve months is deliberate: an audit trail is kept for a
-- regulator's look-back, and no setting may shorten it below a year. Returns partitions dropped.
CREATE FUNCTION audit_drop_partitions(keep_months INT) RETURNS INT
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    cutoff DATE;
    part RECORD;
    dropped INT := 0;
BEGIN
    IF keep_months < 12 THEN
        RAISE EXCEPTION 'the audit trail is kept for at least 12 months, not %', keep_months;
    END IF;
    cutoff := (date_trunc('month', now() AT TIME ZONE 'UTC') - make_interval(months => keep_months))::date;
    FOR part IN
        SELECT c.relname
          FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid
         WHERE i.inhparent = 'model_invocation_events'::regclass
           AND c.relname ~ '^model_invocation_events_[0-9]{4}_[0-9]{2}$'
           AND to_date(right(c.relname, 7), 'YYYY_MM') < cutoff
    LOOP
        EXECUTE format('DROP TABLE %I', part.relname);
        dropped := dropped + 1;
    END LOOP;
    DELETE FROM model_invocation_events_default WHERE occurred_at < cutoff::timestamp AT TIME ZONE 'UTC';
    RETURN dropped;
END
$$;

-- One bounded batch of retention deletes on the tables that only grow. Each table loses at most
-- batch_size rows per call; the caller repeats while anything was deleted, every call its own short
-- statement. Returns rows deleted in this batch. Floors keep a bad setting from emptying a table.
CREATE FUNCTION purge_expired_rows(batch_size INT, delivered_webhook_days INT, failed_webhook_days INT,
                                   read_notification_days INT, notification_days INT, login_attempt_days INT,
                                   job_run_days INT) RETURNS INT
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    n INT;
    total INT := 0;
BEGIN
    IF batch_size < 1 OR batch_size > 50000 THEN
        RAISE EXCEPTION 'batch_size must be between 1 and 50000, not %', batch_size;
    END IF;
    IF delivered_webhook_days < 1 OR failed_webhook_days < 7 OR read_notification_days < 7
       OR notification_days < 30 OR login_attempt_days < 30 OR job_run_days < 7 THEN
        RAISE EXCEPTION 'a retention period is below its floor';
    END IF;

    DELETE FROM webhook_outbox WHERE ctid IN (SELECT ctid FROM webhook_outbox
        WHERE status = 'DELIVERED' AND delivered_at < now() - make_interval(days => delivered_webhook_days) LIMIT batch_size);
    GET DIAGNOSTICS n = ROW_COUNT; total := total + n;

    DELETE FROM webhook_outbox WHERE ctid IN (SELECT ctid FROM webhook_outbox
        WHERE status = 'FAILED' AND created_at < now() - make_interval(days => failed_webhook_days) LIMIT batch_size);
    GET DIAGNOSTICS n = ROW_COUNT; total := total + n;

    DELETE FROM notification WHERE ctid IN (SELECT ctid FROM notification
        WHERE (read AND created_at < now() - make_interval(days => read_notification_days))
           OR created_at < now() - make_interval(days => notification_days) LIMIT batch_size);
    GET DIAGNOSTICS n = ROW_COUNT; total := total + n;

    DELETE FROM login_attempt WHERE ctid IN (SELECT ctid FROM login_attempt
        WHERE occurred_at < now() - make_interval(days => login_attempt_days) LIMIT batch_size);
    GET DIAGNOSTICS n = ROW_COUNT; total := total + n;

    DELETE FROM job_run WHERE ctid IN (SELECT ctid FROM job_run
        WHERE started_at < now() - make_interval(days => job_run_days) LIMIT batch_size);
    GET DIAGNOSTICS n = ROW_COUNT; total := total + n;

    RETURN total;
END
$$;

-- The existing rows: a partition for every month they cover, then this month and three ahead.
DO $$
DECLARE
    m DATE;
    last_month DATE := date_trunc('month', now() AT TIME ZONE 'UTC')::date;
BEGIN
    SELECT date_trunc('month', min(occurred_at) AT TIME ZONE 'UTC')::date INTO m FROM model_invocation_events_old;
    WHILE m IS NOT NULL AND m < last_month LOOP
        PERFORM audit_create_partition(m);
        m := (m + interval '1 month')::date;
    END LOOP;
    PERFORM audit_ensure_partitions(3);
END
$$;

INSERT INTO model_invocation_events (id, endpoint, error_message, latency_ms, occurred_at, request_json, response_json,
                                     success, tenant_id, actor, http_method, path, status_code, client_ip, model_version)
SELECT id, endpoint, error_message, latency_ms, occurred_at, request_json, response_json,
       success, tenant_id, actor, http_method, path, status_code, client_ip, model_version
  FROM model_invocation_events_old;
DROP TABLE model_invocation_events_old;

-- The same isolation and the same append-only grant as before (V11, V12, V26).
ALTER TABLE model_invocation_events ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON model_invocation_events
    USING (tenant_id IS NULL OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
    WITH CHECK (tenant_id IS NULL OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);
REVOKE ALL ON model_invocation_events FROM arthadhruva_app;
GRANT SELECT, INSERT ON model_invocation_events TO arthadhruva_app;
GRANT USAGE, SELECT ON SEQUENCE model_invocation_events_seq TO arthadhruva_app;
REVOKE ALL ON model_invocation_events_default FROM arthadhruva_app;

-- Maintenance is run by the app, as the owner, through these functions only.
REVOKE ALL ON FUNCTION audit_ensure_partitions(INT), audit_create_partition(DATE), audit_drop_partitions(INT),
    purge_expired_rows(INT, INT, INT, INT, INT, INT, INT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION audit_ensure_partitions(INT), audit_drop_partitions(INT),
    purge_expired_rows(INT, INT, INT, INT, INT, INT, INT) TO arthadhruva_app;
