-- Optimistic locking: every update bumps version, and a write based on a stale read fails
-- (0 rows updated) instead of silently overwriting a concurrent analyst's change.
ALTER TABLE loan_case ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
