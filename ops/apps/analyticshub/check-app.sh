#!/usr/bin/env bash
set -euo pipefail

APP_NAME="${APP_NAME:-analyticshub}"
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
  local compact_value="${value//[[:space:]]/}"
  [[ -z "$compact_value" || "$compact_value" == replace-with-* || "$compact_value" == *example.com* ]]
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

check_totp_secret() {
  local value="${APP_SECURITY_2FA_SECRET:-}"
  local length="${#value}"
  local remainder=$((length % 8))

  if is_placeholder "$value"; then
    fail "APP_SECURITY_2FA_SECRET must be replaced with a real value"
    return
  fi
  if (( length < 16 || length > 128 )) \
    || [[ ! "$value" =~ ^[A-Za-z2-7]+$ ]] \
    || [[ ! "$remainder" =~ ^(0|2|4|5|7)$ ]]; then
    fail "APP_SECURITY_2FA_SECRET must be a valid unpadded Base32 TOTP secret"
    return
  fi

  ok "APP_SECURITY_2FA_SECRET format is valid"
}

check_positive_integer() {
  local key="$1"
  local default_value="$2"
  local value="${!key:-$default_value}"
  if [[ "$value" =~ ^[1-9][0-9]*$ ]]; then
    ok "$key is a positive integer: $value"
  else
    fail "$key must be a positive integer"
  fi
}

check_integer_minimum() {
  local key="$1"
  local default_value="$2"
  local minimum="$3"
  local value="${!key:-$default_value}"
  if [[ "$value" =~ ^[0-9]+$ ]] && (( value >= minimum )); then
    ok "$key is at least $minimum: $value"
  else
    fail "$key must be an integer greater than or equal to $minimum"
  fi
}

check_integer_range() {
  local key="$1"
  local default_value="$2"
  local minimum="$3"
  local maximum="$4"
  local value="${!key:-$default_value}"
  if [[ "$value" =~ ^[0-9]+$ ]] && (( value >= minimum && value <= maximum )); then
    ok "$key is in range [$minimum, $maximum]: $value"
  else
    fail "$key must be an integer in range [$minimum, $maximum]"
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

if [[ -n "${LOG_PATH:-}" ]]; then
  [[ -d "$LOG_PATH" ]] && ok "log dir exists: $LOG_PATH" || fail "log dir missing: $LOG_PATH"
else
  warn "LOG_PATH is not configured, file logs will use application default"
fi

for key in DB_PASSWORD ADMIN_TOKEN PROJECT_CREDENTIAL_ENCRYPTION_KEY; do
  require_real_value "$key"
done

credential_key_bytes="$(
  printf '%s' "${PROJECT_CREDENTIAL_ENCRYPTION_KEY:-}" \
    | openssl base64 -d -A 2>/dev/null \
    | wc -c \
    | tr -d ' '
)" || true
[[ "$credential_key_bytes" == "32" ]] \
  && ok "PROJECT_CREDENTIAL_ENCRYPTION_KEY decodes to 32 bytes" \
  || fail "PROJECT_CREDENTIAL_ENCRYPTION_KEY must be Base64 for exactly 32 bytes"

if [[ -n "${PROJECT_CREDENTIAL_PREVIOUS_ENCRYPTION_KEY:-}" ]]; then
  previous_credential_key_bytes="$(
    printf '%s' "$PROJECT_CREDENTIAL_PREVIOUS_ENCRYPTION_KEY" \
      | openssl base64 -d -A 2>/dev/null \
      | wc -c \
      | tr -d ' '
  )" || true
  [[ "$previous_credential_key_bytes" == "32" ]] \
    && ok "PROJECT_CREDENTIAL_PREVIOUS_ENCRYPTION_KEY decodes to 32 bytes" \
    || fail "PROJECT_CREDENTIAL_PREVIOUS_ENCRYPTION_KEY must be Base64 for exactly 32 bytes"
fi

[[ "${SPRING_PROFILES_ACTIVE:-}" == "prod" ]] && ok "prod profile enabled" || fail "SPRING_PROFILES_ACTIVE should be prod"
[[ "${SERVER_ADDRESS:-127.0.0.1}" == "127.0.0.1" ]] \
  && ok "application bind address is loopback-only" \
  || fail "SERVER_ADDRESS must be 127.0.0.1 behind the bundled Nginx proxy"
[[ "${SERVER_PORT:-}" == "3001" ]] && ok "port OK" || warn "expected SERVER_PORT=3001"
[[ "${DB_NAME:-}" == "analytics" ]] && ok "DB OK" || warn "expected DB_NAME=analytics"
[[ "${DB_USER:-}" == "analytic" ]] && ok "DB user OK" || warn "expected DB_USER=analytic"
admin_token="${ADMIN_TOKEN:-}"
if [[ "$admin_token" =~ ^[[:space:]] || "$admin_token" =~ [[:space:]]$ ]]; then
  fail "ADMIN_TOKEN must not contain surrounding whitespace"
elif (( ${#admin_token} >= 32 )); then
  ok "ADMIN_TOKEN format OK"
else
  fail "ADMIN_TOKEN must be at least 32 characters"
fi
check_positive_integer APP_SECURITY_MAX_REQUEST_BODY_BYTES 1048576
check_positive_integer APP_JSON_MAX_NESTING_DEPTH 64
check_positive_integer APP_JSON_MAX_STRING_LENGTH 262144
check_positive_integer APP_JSON_MAX_NUMBER_LENGTH 128
check_positive_integer APP_RATE_LIMIT_REQUESTS 100
check_integer_minimum APP_RATE_LIMIT_WINDOW_MS 60000 1000
check_integer_range CREDENTIAL_ROTATION_GRACE_SECONDS 600 1 86400
check_positive_integer PROJECT_DATASOURCE_MAXIMUM_POOL_SIZE 5
check_integer_range PROJECT_DATASOURCE_MINIMUM_IDLE 0 0 1000
project_pool_max="${PROJECT_DATASOURCE_MAXIMUM_POOL_SIZE:-5}"
project_pool_min="${PROJECT_DATASOURCE_MINIMUM_IDLE:-0}"
if [[ "$project_pool_max" =~ ^[0-9]+$ && "$project_pool_min" =~ ^[0-9]+$ ]] \
  && (( project_pool_min <= project_pool_max )); then
  ok "project datasource minimum idle does not exceed maximum pool size"
else
  fail "PROJECT_DATASOURCE_MINIMUM_IDLE must not exceed PROJECT_DATASOURCE_MAXIMUM_POOL_SIZE"
fi
[[ "${APP_RATE_LIMIT_ENABLED:-true}" =~ ^(true|false)$ ]] \
  && ok "APP_RATE_LIMIT_ENABLED is boolean" \
  || fail "APP_RATE_LIMIT_ENABLED must be true or false"
[[ "${ALLOW_INSECURE_DEVICE_REREGISTRATION:-false}" =~ ^(true|false)$ ]] \
  && ok "ALLOW_INSECURE_DEVICE_REREGISTRATION is boolean" \
  || fail "ALLOW_INSECURE_DEVICE_REREGISTRATION must be true or false"
[[ -f "$APP_DIR/app.jar" ]] && ok "jar exists: $APP_DIR/app.jar" || warn "jar missing: $APP_DIR/app.jar"

if [[ "${MAIL_ENABLED:-false}" == "true" ]]; then
  for key in MAIL_HOST MAIL_USERNAME MAIL_PASSWORD ALERT_EMAIL; do
    require_real_value "$key"
  done
else
  warn "mail is disabled"
fi

case "${APP_SECURITY_2FA_ENABLED:-false}" in
  true)
    check_totp_secret
    ;;
  false)
    warn "admin 2FA is disabled"
    ;;
  *)
    fail "APP_SECURITY_2FA_ENABLED must be true or false"
    ;;
esac

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
