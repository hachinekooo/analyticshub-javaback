#!/usr/bin/env bash
set -euo pipefail

# Limit systemd journal disk usage.

SYSTEM_MAX_USE="${SYSTEM_MAX_USE:-512M}"
SYSTEM_KEEP_FREE="${SYSTEM_KEEP_FREE:-1G}"
MAX_RETENTION_SEC="${MAX_RETENTION_SEC:-14day}"
CONF_FILE="${CONF_FILE:-/etc/systemd/journald.conf.d/90-analyticshub-limits.conf}"

require_root() {
  if [[ "${EUID}" -ne 0 ]]; then
    echo "Please run as root: sudo $0" >&2
    exit 1
  fi
}

require_root

install -d -m 755 "$(dirname "$CONF_FILE")"
cat >"$CONF_FILE" <<EOF
[Journal]
SystemMaxUse=${SYSTEM_MAX_USE}
SystemKeepFree=${SYSTEM_KEEP_FREE}
MaxRetentionSec=${MAX_RETENTION_SEC}
Compress=yes
EOF

systemctl restart systemd-journald
journalctl --vacuum-time="$MAX_RETENTION_SEC" >/dev/null || true

echo "journald limits installed: $CONF_FILE"
