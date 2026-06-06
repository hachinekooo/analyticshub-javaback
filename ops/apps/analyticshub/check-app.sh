#!/usr/bin/env bash
set -euo pipefail

BASE_NAME="${BASE_NAME:-analyticshub}"
DEPLOY_ENV="${DEPLOY_ENV:-prod}"
APP_NAME="${APP_NAME:-${BASE_NAME}-${DEPLOY_ENV}}"
ENV_FILE="${ENV_FILE:-/etc/$APP_NAME/$APP_NAME.env}"
APP_DIR="${APP_DIR:-/opt/$APP_NAME}"
HOST="${HOST:-127.0.0.1}"
failures=0

ok() { echo "OK    $1"; }
warn() { echo "WARN  $1"; }
fail() { echo "FAIL  $1"; failures=$((failures + 1)); }
command_exists() { command -v "$1" >/dev/null 2>&1; }

is_placeholder() {
  local value="${1:-}"
  [[ -z "$value" || "$value" == replace-with-* || "$value" == *example.com* ]]
}

require_real_value() {
  local key="$1"
  local value="${!key:-}"
  if is_placeholder "$value"; then
    fail "$key must be replaced with a real value"
  else
    ok "env value configured: $key"
  fi
}

if [[ -f "$ENV_FILE" ]]; then
  set -a
  # shellcheck disable=SC1090
  source "$ENV_FILE"
  set +a
  ok "env file exists: $ENV_FILE"
else
  fail "env file missing: $ENV_FILE"
fi

for key in SPRING_PROFILES_ACTIVE SERVER_PORT DB_HOST DB_PORT DB_NAME DB_SCHEMA DB_USER DB_PASSWORD ADMIN_TOKEN; do
  [[ -n "${!key:-}" ]] && ok "env configured: $key" || fail "missing env: $key"
done

for key in DB_PASSWORD ADMIN_TOKEN; do
  require_real_value "$key"
done

[[ "${SPRING_PROFILES_ACTIVE:-}" == "prod" ]] && ok "prod profile enabled" || fail "SPRING_PROFILES_ACTIVE should be prod"
admin_token="${ADMIN_TOKEN:-}"
(( ${#admin_token} >= 32 )) && ok "ADMIN_TOKEN length OK" || fail "ADMIN_TOKEN must be at least 32 characters"
[[ -f "$APP_DIR/app.jar" ]] && ok "jar exists: $APP_DIR/app.jar" || warn "jar missing: $APP_DIR/app.jar"

case "$DEPLOY_ENV" in
  prod)
    [[ "${SERVER_PORT:-}" == "3001" ]] && ok "prod port OK" || warn "prod expected SERVER_PORT=3001"
    [[ "${DB_NAME:-}" == "analytics_prod" ]] && ok "prod DB OK" || warn "prod expected DB_NAME=analytics_prod"
    ;;
  test)
    [[ "${SERVER_PORT:-}" == "13001" ]] && ok "test port OK" || warn "test expected SERVER_PORT=13001"
    [[ "${DB_NAME:-}" == "analytics_test" ]] && ok "test DB OK" || warn "test expected DB_NAME=analytics_test"
    ;;
esac

if [[ "${MAIL_ENABLED:-false}" == "true" ]]; then
  for key in MAIL_HOST MAIL_USERNAME MAIL_PASSWORD ALERT_EMAIL; do
    require_real_value "$key"
  done
else
  warn "mail is disabled"
fi

if [[ "${APP_SECURITY_2FA_ENABLED:-false}" == "true" ]]; then
  require_real_value APP_SECURITY_2FA_SECRET
else
  warn "admin 2FA is disabled"
fi

if command_exists psql; then
  if PGPASSWORD="${DB_PASSWORD:-}" psql -h "${DB_HOST:-127.0.0.1}" -p "${DB_PORT:-5432}" -U "${DB_USER:-}" -d "${DB_NAME:-}" -v ON_ERROR_STOP=1 -c "select 1" >/dev/null 2>&1; then
    ok "PostgreSQL login works"
  else
    fail "PostgreSQL login failed"
  fi
  schema_count="$(
    PGPASSWORD="${DB_PASSWORD:-}" psql -h "${DB_HOST:-127.0.0.1}" -p "${DB_PORT:-5432}" -U "${DB_USER:-}" -d "${DB_NAME:-}" \
      -v ON_ERROR_STOP=1 -tAc "select count(*) from information_schema.schemata where schema_name = '${DB_SCHEMA:-analytics}'" 2>/dev/null || true
  )"
  if [[ "$schema_count" == "1" ]]; then
    ok "PostgreSQL schema exists: ${DB_SCHEMA:-analytics}"
  else
    fail "PostgreSQL schema missing: ${DB_SCHEMA:-analytics}"
  fi
else
  warn "psql missing, skip database login check"
fi

if systemctl cat "$APP_NAME.service" >/dev/null 2>&1; then
  ok "systemd service registered: $APP_NAME"
else
  fail "systemd service missing: $APP_NAME"
fi

if systemctl is-active --quiet "$APP_NAME"; then
  ok "systemd service active: $APP_NAME"
  curl -fsS "http://$HOST:${SERVER_PORT:-3001}/api/health" >/dev/null && ok "health endpoint works" || fail "health endpoint failed"
else
  warn "systemd service is not active yet: $APP_NAME"
fi

echo
if (( failures > 0 )); then
  echo "App check failed: $failures issue(s)."
  exit 1
fi

echo "App check passed."
