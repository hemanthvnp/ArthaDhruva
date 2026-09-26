#!/usr/bin/env bash
# Nightly logical backup of the only store that cannot be rebuilt (Postgres). Keeps the newest N dumps.
#   0 3 * * * /home/ubuntu/ArthaDhruva/deploy/backup.sh >> /home/ubuntu/backups/backup.log 2>&1
# Copy the dumps off the server too (rclone to a free cloud bucket, or scp): a backup on the same
# disk does not survive losing the VM.
set -euo pipefail
cd "$(dirname "$0")/.."

DEST="${BACKUP_DIR:-$HOME/backups}"
KEEP="${BACKUP_KEEP:-7}"
COMPOSE="docker compose -f docker-compose.yml -f docker-compose.prod.yml -f docker-compose.deploy.yml"

mkdir -p "$DEST"
file="$DEST/arthadhruva-$(date -u +%Y%m%dT%H%M%SZ).sql.gz"

# Runs as the table owner over the container's local socket, so row-level security does not hide any
# tenant's rows from the dump.
$COMPOSE exec -T postgres pg_dump -U arthadhruva -d arthadhruva --no-owner | gzip > "$file"

# A dump this small means something went wrong (an empty database still produces a few KB of DDL).
[ "$(wc -c < "$file")" -gt 2000 ] || { echo "backup suspiciously small: $file" >&2; rm -f "$file"; exit 1; }

ls -1t "$DEST"/arthadhruva-*.sql.gz | tail -n +"$((KEEP + 1))" | xargs -r rm --
echo "$(date -u +%FT%TZ) wrote $file ($(du -h "$file" | cut -f1))"
