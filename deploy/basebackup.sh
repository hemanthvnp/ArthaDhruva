#!/usr/bin/env bash
# Physical base backup of Postgres, for point-in-time restore (deploy/restore-pitr.sh). Run by backup.sh
# every night, next to the logical dump; on its own: bash deploy/basebackup.sh
#
# A base backup plus the WAL that Postgres archives after it (the wal_archive volume, see the postgres
# service in docker-compose.yml) can be replayed to any moment between the backup and the newest archived
# segment, at most 15 minutes behind: archive_timeout forces a segment out that often whenever anything
# was written. Keeps the newest BACKUP_KEEP base backups and deletes archived WAL that only an older one
# would need. Like backup.sh, it cannot copy anything off the server for you: do that too.
set -euo pipefail
cd "$(dirname "$0")/.."

DEST="${BACKUP_DIR:-$HOME/backups}"
KEEP="${BACKUP_KEEP:-7}"
COMPOSE="${COMPOSE:-docker compose -f docker-compose.yml -f docker-compose.prod.yml -f docker-compose.deploy.yml}"

mkdir -p "$DEST"
base="$DEST/arthadhruva-$(date -u +%Y%m%dT%H%M%SZ).base.tar.gz"
trap 'rm -f "$base.partial"' EXIT

# Tar to stdout, with the WAL the backup itself needs inside it (-X fetch), so the newest backup restores
# even with an empty archive. Over the container's local socket as the owner (pg_hba.conf allows it).
$COMPOSE exec -T postgres pg_basebackup -U arthadhruva -D - -F tar -X fetch -c fast | gzip > "$base.partial"
gzip -t "$base.partial"
gunzip -c "$base.partial" | tar -t | grep -x backup_label > /dev/null || { echo "the base backup has no backup_label" >&2; exit 1; }
mv "$base.partial" "$base"

kept=("$DEST"/arthadhruva-*.base.tar.gz)   # sorted by name, which carries the UTC time
excess=$(( ${#kept[@]} - KEEP ))
if [ "$excess" -gt 0 ]; then rm -- "${kept[@]:0:excess}"; kept=("${kept[@]:excess}"); fi

# WAL archived before the oldest kept backup was written is needed by no restore any more (each backup
# carries the WAL of its own run). An hour of margin, since file times on two clocks are compared.
oldest_age_min=$(( ( $(date +%s) - $(stat -c %Y "${kept[0]}") ) / 60 + 60 ))
removed="$($COMPOSE exec -T postgres sh -c "find /wal-archive -type f -name '*.gz' -mmin +$oldest_age_min -print -delete | wc -l" | tr -d '\r ')"
echo "$(date -u +%FT%TZ) wrote $base ($(du -h "$base" | cut -f1)); ${#kept[@]} base backup(s) kept; $removed archived WAL file(s) no longer needed removed"
