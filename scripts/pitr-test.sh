#!/usr/bin/env bash
# Point-in-time restore test: the backup and restore scripts that run in production, against a throwaway
# Postgres started from docker-compose.yml with its WAL archiving as configured there.
#
# Writes rows, takes a base backup (deploy/basebackup.sh), writes more, notes the time, then makes a
# mistake (deletes everything). Passes only if deploy/restore-pitr.sh, given that base backup and the noted
# time, brings back every row written before the mistake, including those written after the backup, which
# can only have come from the archived WAL. Runs under its own compose project and removes it at the end.
set -euo pipefail
export MSYS_NO_PATHCONV=1   # Git Bash on Windows would rewrite /dev/null and /wal-archive into Windows paths
cd "$(dirname "$0")/.."

project=${PITR_PROJECT:-pitrtest}
export COMPOSE="docker compose -p $project -f docker-compose.yml"
work="$(mktemp -d)"
export BACKUP_DIR="$work"
cleanup() { $COMPOSE down -v --remove-orphans >/dev/null 2>&1 || true; rm -rf "$work"; }
trap cleanup EXIT

fail() {
  printf 'FAILED: %s\n' "$*" >&2
  $COMPOSE logs --tail 40 postgres >&2 || true
  exit 1
}
sql() { $COMPOSE exec -T postgres psql -X -q -v ON_ERROR_STOP=1 -U arthadhruva -d arthadhruva -Atc "$1" | tr -d '\r'; }

$COMPOSE up -d --wait postgres

sql "CREATE TABLE pitr_probe (id int PRIMARY KEY, note text)"
sql "INSERT INTO pitr_probe SELECT g, 'before the backup' FROM generate_series(1, 100) g"

bash deploy/basebackup.sh
base="$(ls "$work"/arthadhruva-*.base.tar.gz)"

sql "INSERT INTO pitr_probe SELECT g, 'after the backup' FROM generate_series(101, 150) g"
sleep 2
target="$(sql "SELECT now()")"
sleep 2
sql "DELETE FROM pitr_probe"                  # the mistake
sql "SELECT pg_switch_wal()" >/dev/null       # archive the segment holding it now, not in 15 minutes
for _ in $(seq 1 30); do
  [ "$(sql "SELECT last_archived_wal >= pg_walfile_name(pg_current_wal_lsn() - 1) FROM pg_stat_archiver")" = t ] && break
  sleep 1
done
[ "$(sql "SELECT count(*) FROM pitr_probe")" = 0 ] || fail "the mistake did not happen"

CONFIRM_RESTORE=yes bash deploy/restore-pitr.sh "$base" "$target"

before="$(sql "SELECT count(*) FROM pitr_probe WHERE note = 'before the backup'")"
after="$(sql "SELECT count(*) FROM pitr_probe WHERE note = 'after the backup'")"
[ "$before" = 100 ] || fail "expected the 100 rows from before the backup, found $before"
[ "$after" = 50 ] || fail "expected the 50 rows written after the backup (from the archived WAL), found $after"
[ "$(sql "SELECT pg_is_in_recovery()")" = f ] || fail "the restored server is still in recovery"
sql "INSERT INTO pitr_probe VALUES (151, 'written after the restore')"   # it takes writes again
[ -n "$(ls "$work"/pgdata-before-restore-*.tar.gz)" ] || fail "the data directory before the restore was not kept"

echo "PASSED: restored to $target; 100 rows from the base backup and 50 replayed from the WAL archive"
