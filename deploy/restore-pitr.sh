#!/usr/bin/env bash
# Point-in-time restore: a base backup from basebackup.sh, replayed through the archived WAL up to a moment
# you choose, typically just before a mistake (a bad bulk update, a dropped table).
#   CONFIRM_RESTORE=yes bash deploy/restore-pitr.sh ~/backups/arthadhruva-<stamp>.base.tar.gz '2026-10-15 09:41:00+00'
# Leave the time out to replay everything archived (the newest state the archive holds).
#
# It replaces the database the stack is running on, so it needs CONFIRM_RESTORE=yes, and it first saves
# the current data directory next to the backups, so even a restore to the wrong moment can be undone.
# The backend is stopped for the duration; start it again afterwards with deploy.sh. The WAL archive is
# not touched: the restored server continues it on a new timeline, so a second restore stays possible.
set -euo pipefail
cd "$(dirname "$0")/.."

base="${1:?usage: CONFIRM_RESTORE=yes restore-pitr.sh <base.tar.gz> ['<target time with zone>']}"
target="${2:-}"
DEST="${BACKUP_DIR:-$HOME/backups}"
COMPOSE="${COMPOSE:-docker compose -f docker-compose.yml -f docker-compose.prod.yml -f docker-compose.deploy.yml}"

say() { printf '\n==> %s\n' "$*"; }
die() { echo "$*" >&2; exit 1; }

[ "${CONFIRM_RESTORE:-}" = yes ] || die "This replaces the running database. Run it again with CONFIRM_RESTORE=yes."
[ -r "$base" ] || die "Cannot read $base"
gzip -t "$base" || die "$base is not a complete gzip file"
gunzip -c "$base" | tar -t | grep -x backup_label > /dev/null || die "$base is not a base backup (no backup_label)"
# The time goes into postgresql.auto.conf inside quotes; anything that would need escaping is refused.
case "$target" in *\'*|*\\*) die "The target time may not contain quotes or backslashes";; esac

if [ -n "$($COMPOSE ps -q backend 2>/dev/null)" ]; then
  say "Stopping the backend"
  $COMPOSE stop backend
fi
say "Stopping Postgres"
$COMPOSE stop postgres

mkdir -p "$DEST"
keep="$DEST/pgdata-before-restore-$(date -u +%Y%m%dT%H%M%SZ).tar.gz"
say "Saving the current data directory to $keep"
$COMPOSE run --rm --no-deps -T --entrypoint tar postgres -czf - -C /var/lib/postgresql/data . > "$keep"
gzip -t "$keep"

say "Laying down $(basename "$base")${target:+, to replay up to $target}"
# shellcheck disable=SC2016  # expanded by the shell in the container, not here
inner='set -e
d=/var/lib/postgresql/data
find "$d" -mindepth 1 -delete
tar -xf - -C "$d"
touch "$d/recovery.signal"
printf "restore_command = '"'"'gunzip -c /wal-archive/%%f.gz > %%p'"'"'\n" >> "$d/postgresql.auto.conf"
if [ -n "$TARGET" ]; then
  printf "recovery_target_time = '"'"'%s'"'"'\nrecovery_target_action = '"'"'promote'"'"'\n" "$TARGET" >> "$d/postgresql.auto.conf"
fi
chown -R postgres:postgres "$d"
chmod 700 "$d"'
gunzip -c "$base" | $COMPOSE run --rm --no-deps -T -e TARGET="$target" --entrypoint sh postgres -c "$inner"

say "Starting Postgres and replaying the archived WAL"
$COMPOSE up -d postgres
sql() { $COMPOSE exec -T postgres psql -X -q -v ON_ERROR_STOP=1 -U arthadhruva -d arthadhruva "$@"; }
for _ in $(seq 1 120); do
  if [ "$(sql -Atc 'SELECT pg_is_in_recovery()' 2>/dev/null | tr -d '\r')" = f ]; then
    recovered=1
    break
  fi
  sleep 5
done
if [ -z "${recovered:-}" ]; then
  $COMPOSE logs --tail 40 postgres >&2
  die "Postgres did not finish recovery (a target later than the newest archived WAL stops it). The previous data is in $keep."
fi

# The recovery settings have done their work; leaving them would only confuse the next person to look.
sql > /dev/null <<'SQL'
ALTER SYSTEM RESET restore_command;
ALTER SYSTEM RESET recovery_target_time;
ALTER SYSTEM RESET recovery_target_action;
SELECT pg_reload_conf();
SQL
sql -Atc "SELECT 'last transaction replayed: ' || coalesce(pg_last_xact_replay_timestamp()::text, 'none after the backup')"

say "Restored. Start the rest of the stack with: bash deploy/deploy.sh"
