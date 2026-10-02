#!/usr/bin/env bash
# Run once on the VM, as root: sudo bash deploy/azure/install-idle-stop.sh
# Installs the idle check and its timer, and the rule that keeps containers away from the metadata
# service. The VM only actually turns itself off once setup-wake.sh has given it the identity and the
# permission to do so; until then the check logs an error and leaves the VM running.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"

install -m 755 "$here/idle-stop.sh" /usr/local/sbin/artha-idle-stop
install -m 644 "$here/artha-idle-stop.service" "$here/artha-idle-stop.timer" "$here/artha-imds-guard.service" /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now artha-imds-guard.service artha-idle-stop.timer
systemctl list-timers artha-idle-stop.timer --no-pager
