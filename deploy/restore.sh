#!/usr/bin/env bash
# Restores what backup.sh wrote, on a new server or after the volumes were lost.
#   bash deploy/restore.sh ~/backups/arthadhruva-<stamp>.sql.gz [~/backups/arthadhruva-<stamp>.files.tar.gz]
#
# Run it once .env exists and your private copy of secrets/ is back in place, and before the first
# deploy.sh: the database has to be empty, and the backend fills an empty database the moment it starts.
#
# Why not simply pipe the dump into psql. The backend logs in as two restricted roles that migrations
# V11 and V15 create. A restored database records those migrations as applied, so on a new server
# nothing creates the roles again; every GRANT and two row-level-security policies in the dump then
# fail for want of them, and psql carries on past the errors and exits 0. So the roles are created
# first, with the passwords this server's backend will log in with, and the load is one transaction
# that stops at the first error.
set -euo pipefail
cd "$(dirname "$0")/.."

dump="${1:?usage: restore.sh <dump.sql.gz> [<files.tar.gz>]}"
files="${2:-}"
COMPOSE="docker compose -f docker-compose.yml -f docker-compose.prod.yml -f docker-compose.deploy.yml"

say() { printf '\n==> %s\n' "$*"; }
die() { echo "$*" >&2; exit 1; }

for f in "$dump" ${files:+"$files"}; do
  [ -r "$f" ] || die "Cannot read $f"
  gzip -t "$f" || die "$f is not a complete gzip file"
done

# A database password from secrets/. It goes into a SQL string below, so anything that would need
# quoting is refused rather than escaped (gen-secrets.sh only ever writes letters and digits).
password() {
  [ -s "secrets/$1" ] || die "secrets/$1 is missing. Put your copy of secrets/ back first."
  local value
  value="$(tr -d '\r\n' < "secrets/$1")"
  case "$value" in *[!A-Za-z0-9_-]*) die "secrets/$1 holds characters other than letters, digits, - and _";; esac
  printf %s "$value"
}
app_password="$(password DB_APP_PASSWORD)"
worker_password="$(password DB_WORKER_PASSWORD)"

say "Starting Postgres"
$COMPOSE up -d --wait postgres
sql() { $COMPOSE exec -T postgres psql -X -q -v ON_ERROR_STOP=1 -U arthadhruva -d arthadhruva "$@"; }

tables="$(sql -Atc "SELECT count(*) FROM pg_tables WHERE schemaname = 'public'")"
[ "$tables" = 0 ] || die "The database already holds $tables tables. Restore only into an empty one: stop the stack, remove the pgdata volume and run this again."

say "Creating the application's database roles"
sql <<SQL
SELECT format('CREATE ROLE %I LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE', r)
  FROM unnest(ARRAY['arthadhruva_app', 'arthadhruva_worker']) AS r
 WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = r) \gexec
ALTER ROLE arthadhruva_app PASSWORD '$app_password';
ALTER ROLE arthadhruva_worker PASSWORD '$worker_password';
SQL

say "Loading $dump"
gunzip -c "$dump" | sql --single-transaction > /dev/null

# The app role must be able to read every table except Flyway's own; a restore that lost its grants
# would otherwise only show up as a backend that cannot serve a single request.
ungranted="$(sql -Atc "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
  WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p') AND c.relname <> 'flyway_schema_history'
  AND NOT has_table_privilege('arthadhruva_app', c.oid, 'SELECT')")"
[ "$ungranted" = 0 ] || die "The restore finished but $ungranted tables are not readable by the application role."
sql -Atc "SELECT 'schema version ' || max(version::int) || ', ' || (SELECT count(*) FROM organization) || ' organizations, '
  || (SELECT count(*) FROM pg_policies) || ' row-level policies' FROM flyway_schema_history WHERE success"

if [ -n "$files" ]; then
  say "Restoring uploaded documents (builds the backend image if this server has not built it yet)"
  $COMPOSE run --rm --no-deps -T --entrypoint tar backend -xzf - -C /data/attachments < "$files"
fi

say "Restored. Start the stack with: bash deploy/deploy.sh"
