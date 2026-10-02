#!/usr/bin/env bash
# Nightly backup of the two stores that cannot be rebuilt: the Postgres database and the uploaded loan
# documents (the `attachments` volume). Redis is a cache and Neo4j is reloaded at startup. Keeps the
# newest N of each.
#   0 3 * * * bash /home/ubuntu/ArthaDhruva/deploy/backup.sh >> /home/ubuntu/backups/backup.log 2>&1
# Restore with deploy/restore.sh.
#
# Two things this script cannot do for you. Copy the files off the server (rclone to a free cloud
# bucket, or scp): a backup on the same disk does not survive losing the VM. And keep a private copy
# of secrets/: it holds the key that the stored 2FA, SSO and webhook secrets are encrypted with, and
# that key is deliberately not in a backup that sits next to the data it protects.
set -euo pipefail
cd "$(dirname "$0")/.."

DEST="${BACKUP_DIR:-$HOME/backups}"
KEEP="${BACKUP_KEEP:-7}"
COMPOSE="docker compose -f docker-compose.yml -f docker-compose.prod.yml -f docker-compose.deploy.yml"

mkdir -p "$DEST"
stamp="$(date -u +%Y%m%dT%H%M%SZ)"
db="$DEST/arthadhruva-$stamp.sql.gz"
files="$DEST/arthadhruva-$stamp.files.tar.gz"
# Written under a temporary name and renamed only once complete. A run that dies halfway must not
# leave a truncated file that looks like a backup and pushes a good one out of the retention window.
trap 'rm -f "$db.partial" "$files.partial"' EXIT

# Runs as the database superuser over the container's local socket, so row-level security does not
# hide any tenant's rows from the dump.
$COMPOSE exec -T postgres pg_dump -U arthadhruva -d arthadhruva --no-owner | gzip > "$db.partial"
# pg_dump ends a complete dump with this line; a dump cut short does not have it.
gunzip -c "$db.partial" | tail -n 10 | grep -q "PostgreSQL database dump complete" \
  || { echo "the database dump is incomplete" >&2; exit 1; }

$COMPOSE exec -T backend tar -czf - -C /data/attachments . > "$files.partial"
gzip -t "$files.partial"

mv "$db.partial" "$db"
mv "$files.partial" "$files"

for kind in sql.gz files.tar.gz; do
  kept=("$DEST"/arthadhruva-*."$kind")   # a glob sorts by name, and the name carries the UTC time
  excess=$(( ${#kept[@]} - KEEP ))
  if [ "$excess" -gt 0 ]; then rm -- "${kept[@]:0:excess}"; fi
done
echo "$(date -u +%FT%TZ) wrote $db ($(du -h "$db" | cut -f1)) and $files ($(du -h "$files" | cut -f1))"
