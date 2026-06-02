#!/usr/bin/env bash
set -euo pipefail

APP_NAME="analyticshub"
ENV_FILE="/etc/analyticshub/analyticshub.env"
APP_DIR="/opt/analyticshub"
failures=0

ok() { echo "OK    $1"; }
warn() { echo "WARN  $1"; }
fail() { echo "FAIL  $1"; failures=$((failures + 1)); }
command_exists() { command -v "$1" >/dev/null 2>&1; }

if [[ -f "$ENV_FILE" ]]; then
  set -a
  # shellcheck disable=SC1090
  source "$ENV_FILE"
  set +a
  ok "env file exists: $ENV_FILE"
else
  fail "env file missing: $ENV_FILE"
fi

for key in SPRING_PROFILES_ACTIVE SERVER_PORT DB_HOST DB_PORT DB_NAME DB_USER DB_PASSWORD ADMIN_TOKEN; do
  [[ -n "${!key:-}" ]] && ok "env configured: $key" || fail "missing env: $key"
done

[[ "${SPRING_PROFILES_ACTIVE:-}" == "prod" ]] && ok "prod profile enabled" || warn "SPRING_PROFILES_ACTIVE is not prod"
admin_token="${ADMIN_TOKEN:-}"
(( ${#admin_token} >= 32 )) && ok "ADMIN_TOKEN length OK" || fail "ADMIN_TOKEN must be at least 32 characters"
[[ -f "$APP_DIR/app.jar" ]] && ok "jar exists: $APP_DIR/app.jar" || warn "jar missing: $APP_DIR/app.jar"

if command_exists psql; then
  if PGPASSWORD="${DB_PASSWORD:-}" psql -h "${DB_HOST:-127.0.0.1}" -p "${DB_PORT:-5432}" -U "${DB_USER:-}" -d "${DB_NAME:-}" -v ON_ERROR_STOP=1 -c "select 1" >/dev/null 2>&1; then
    ok "PostgreSQL login works"
  else
    fail "PostgreSQL login failed"
  fi
else
  warn "psql missing, skip database login check"
fi

if systemctl list-unit-files "$APP_NAME.service" >/dev/null 2>&1; then
  ok "systemd service registered: $APP_NAME"
else
  fail "systemd service missing: $APP_NAME"
fi

if systemctl is-active --quiet "$APP_NAME"; then
  ok "systemd service active: $APP_NAME"
  curl -fsS "http://127.0.0.1:${SERVER_PORT:-3001}/api/health" >/dev/null && ok "health endpoint works" || fail "health endpoint failed"
else
  warn "systemd service is not active yet: $APP_NAME"
fi

echo
if (( failures > 0 )); then
  echo "App check failed: $failures issue(s)."
  exit 1
fi

echo "App check passed."
