#!/usr/bin/env bash
# Deallocates this Azure VM once the site has had no visitors for a while, so that compute is paid for
# only while someone is using it. Run every few minutes by artha-idle-stop.timer; the Function in
# wake/ starts the VM again when someone opens the link.
#
# "Deallocate", not "shut down": a VM that is powered off from the inside stays allocated and billed.
# The request goes to Azure with the VM's own managed identity, whose only permission is to
# deallocate this VM (role "ArthaDhruva VM sleeper", created by setup-wake.sh).
#
# The containers are deliberately not stopped first. Stopping them by hand would mark them as stopped
# on purpose, and `restart: unless-stopped` would then leave them down at the next boot. The operating
# system's shutdown stops them instead, and they come back by themselves.
set -euo pipefail

IDLE_MINUTES="${IDLE_MINUTES:-30}"
NGINX_CONTAINER="${NGINX_CONTAINER:-arthadhruva-nginx-1}"
REPO="${REPO:-/home/ubuntu/ArthaDhruva}"
RUN_AS="${RUN_AS:-ubuntu}"
IMDS="http://169.254.169.254/metadata"

say() { echo "idle-stop: $*"; }

# Someone is working on the server (a deploy, a restore drill): never pull it out from under them.
if pgrep -f '^sshd(-session)?: .*@' > /dev/null; then
  say "an SSH session is open; staying up"
  exit 0
fi

# After every start, give the stack time to come up and the visitor who woke it time to arrive.
up_minutes=$(( $(cut -d. -f1 /proc/uptime) / 60 ))
if [ "$up_minutes" -lt "$IDLE_MINUTES" ]; then
  say "up for $up_minutes min; staying up"
  exit 0
fi

# Who counts as a visitor. Not "any request": crawlers find a new hostname within the hour and load the
# whole page, and would keep the server up for ever. Two things do count, both read from nginx's log:
#   * an API call that succeeded, which takes a signed-in user (anonymous API calls answer 401);
#   * a visitor sent in by the wake page, which requests /wake-visit as it does so, so that they are
#     not switched off while still reading the sign-in page.
# If the log cannot be read (the stack is down), that counts as no visitors: a broken stack should not
# burn credit either.
requests="$(docker logs --since "${IDLE_MINUTES}m" "$NGINX_CONTAINER" 2>&1 \
  | grep -cE '"[A-Z]+ /v1/[^" ]* HTTP/[0-9.]+" 2[0-9]{2} |"GET /wake-visit HTTP/[0-9.]+" 200 ' || true)"
if [ "${requests:-0}" -gt 0 ]; then
  say "$requests visitor requests in the last $IDLE_MINUTES min; staying up"
  exit 0
fi

# The token comes first. Without an identity there is nothing this script can do, and it must find
# that out before the backup: a check that fails every five minutes would otherwise write a backup
# every five minutes and push the older ones out of the retention window.
if ! token="$(curl -fsS -H Metadata:true "$IMDS/identity/oauth2/token?api-version=2018-02-01&resource=https%3A%2F%2Fmanagement.azure.com%2F" \
    | python3 -c 'import json, sys; print(json.load(sys.stdin)["access_token"])')"; then
  say "no managed-identity token: has setup-wake.sh been run for this VM? Staying up."
  exit 1
fi
vm_id="$(curl -fsS -H Metadata:true "$IMDS/instance/compute/resourceId?api-version=2021-02-01&format=text")"

say "no visitors for $IDLE_MINUTES min: backing up, then deallocating"
# The nightly backup job rarely finds this server running, so the backup is taken here instead: every
# session's changes are captured before the machine goes away. Data only changes through the API, and
# there were no API calls in the idle window, so a backup taken inside that window is still current.
if sudo -u "$RUN_AS" -H bash -c "find ~/backups -name 'arthadhruva-*.sql.gz' -mmin -$IDLE_MINUTES 2> /dev/null | grep -q ."; then
  say "the latest backup is newer than the idle window; not taking another"
elif ! sudo -u "$RUN_AS" -H bash -c "mkdir -p ~/backups && bash '$REPO/deploy/backup.sh' >> ~/backups/backup.log 2>&1"; then
  say "the backup failed (see ~/backups/backup.log); deallocating anyway"
fi

curl -fsS -o /dev/null -X POST -H "Authorization: Bearer $token" -H "Content-Length: 0" \
  "https://management.azure.com${vm_id}/deallocate?api-version=2023-09-01"
say "deallocation requested"
