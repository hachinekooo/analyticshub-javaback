#!/usr/bin/env bash
set -euo pipefail

# Back up the AnalyticsHub PostgreSQL database with pg_dump.
# The script reads DB connection info from the root-only env file and does not
# print the database password.

APP_NAME="${APP_NAME:-analyticshub}"
ENV_FILE="${ENV_FILE:-/etc/${APP_NAME}/${APP_NAME}.env}"
BACKUP_DIR="${BACKUP_DIR:-/var/backups/analyticshub}"
RETENTION_DAYS="${RETENTION_DAYS:-14}"
TIMESTAMP="$(date +%Y%m%d%H%M%S)"

require_root() {
  if [[ "${EUID}" -ne 0 ]]; then
    echo "Please run as root: sudo $0" >&2
    exit 1
  fi
}

require_command() {
  local cmd="$1"
  if ! command -v "$cmd" >/dev/null 2>&1; then
    echo "Required command missing: $cmd" >&2
    exit 1
  fi
}

get_env_value() {
  local key="$1"
  awk -F= -v key="$key" '$1 == key {sub(/^[^=]*=/, ""); print; exit}' "$ENV_FILE"
}

require_root
require_command pg_dump

if [[ ! -f "$ENV_FILE" ]]; then
  echo "Env file missing: $ENV_FILE" >&2
  exit 1
fi

db_host="$(get_env_value DB_HOST)"
db_port="$(get_env_value DB_PORT)"
db_name="$(get_env_value DB_NAME)"
db_user="$(get_env_value DB_USER)"
db_password="$(get_env_value DB_PASSWORD)"

if [[ -z "$db_host" || -z "$db_port" || -z "$db_name" || -z "$db_user" || -z "$db_password" ]]; then
  echo "DB_HOST, DB_PORT, DB_NAME, DB_USER, and DB_PASSWORD must be configured in $ENV_FILE." >&2
  exit 1
fi

install -d -m 700 -o root -g root "$BACKUP_DIR"
backup_file="${BACKUP_DIR}/${db_name}-${TIMESTAMP}.dump"

PGPASSWORD="$db_password" pg_dump \
  -h "$db_host" \
  -p "$db_port" \
  -U "$db_user" \
  -d "$db_name" \
  --format=custom \
  --file="$backup_file"

chmod 600 "$backup_file"
chown root:root "$backup_file"

find "$BACKUP_DIR" -type f -name "${db_name}-*.dump" -mtime "+${RETENTION_DAYS}" -delete

echo "AnalyticsHub database backup created: $backup_file"
