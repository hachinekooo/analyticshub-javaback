#!/usr/bin/env bash
set -euo pipefail

TEST_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TARGET_SCRIPT="$TEST_DIR/../apps/analyticshub/check-app.sh"
TEST_ROOT="$(mktemp -d)"

cleanup() {
  rm -rf -- "$TEST_ROOT"
}
trap cleanup EXIT

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

prepare_case() {
  local case_dir="$1"
  local rate_window="$2"
  local allow_insecure="$3"
  local server_address="$4"

  mkdir -p "$case_dir/bin" "$case_dir/app" "$case_dir/log"
  : > "$case_dir/app/app.jar"

  cat > "$case_dir/bin/psql" <<'STUB'
#!/usr/bin/env bash
if [[ "$*" == *"information_schema.schemata"* ]]; then
  echo 1
fi
STUB

  cat > "$case_dir/bin/systemctl" <<'STUB'
#!/usr/bin/env bash
if [[ "${1:-}" == "cat" ]]; then
  exit 0
fi
if [[ "${1:-}" == "is-active" ]]; then
  exit 1
fi
exit 0
STUB

  chmod +x "$case_dir/bin/psql" "$case_dir/bin/systemctl"

  cat > "$case_dir/analyticshub.env" <<ENV
SPRING_PROFILES_ACTIVE=prod
SERVER_ADDRESS=$server_address
SERVER_PORT=3001
LOG_PATH=$case_dir/log
DB_HOST=127.0.0.1
DB_PORT=5432
DB_NAME=analytics
DB_SCHEMA=analytics
DB_USER=analytic
DB_PASSWORD=test-database-password
ADMIN_TOKEN=0123456789abcdefghijklmnopqrstuvwxyz
PROJECT_CREDENTIAL_ENCRYPTION_KEY=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=
MAIL_ENABLED=false
APP_SECURITY_2FA_ENABLED=false
APP_RATE_LIMIT_ENABLED=true
APP_RATE_LIMIT_REQUESTS=100
APP_RATE_LIMIT_WINDOW_MS=$rate_window
ALLOW_INSECURE_DEVICE_REREGISTRATION=$allow_insecure
ENV
}

run_case() {
  local case_dir="$1"
  local output_file="$2"
  (
    export PATH="$case_dir/bin:/usr/bin:/bin"
    export ENV_FILE="$case_dir/analyticshub.env"
    export APP_DIR="$case_dir/app"
    bash "$TARGET_SCRIPT"
  ) >"$output_file" 2>&1
}

valid_case="$TEST_ROOT/valid"
prepare_case "$valid_case" 1000 false 127.0.0.1
run_case "$valid_case" "$valid_case/output.log" \
  || fail "Minimum supported rate-limit window and safe re-registration flag should pass"

invalid_window_case="$TEST_ROOT/invalid-window"
prepare_case "$invalid_window_case" 999 false 127.0.0.1
if run_case "$invalid_window_case" "$invalid_window_case/output.log"; then
  fail "Rate-limit window below 1000 milliseconds should fail"
fi
grep -Fq "APP_RATE_LIMIT_WINDOW_MS must be an integer greater than or equal to 1000" \
  "$invalid_window_case/output.log" \
  || fail "Missing rate-limit minimum validation message"

invalid_boolean_case="$TEST_ROOT/invalid-boolean"
prepare_case "$invalid_boolean_case" 60000 yes 127.0.0.1
if run_case "$invalid_boolean_case" "$invalid_boolean_case/output.log"; then
  fail "Non-boolean insecure re-registration flag should fail"
fi
grep -Fq "ALLOW_INSECURE_DEVICE_REREGISTRATION must be true or false" \
  "$invalid_boolean_case/output.log" \
  || fail "Missing insecure re-registration boolean validation message"

public_bind_case="$TEST_ROOT/public-bind"
prepare_case "$public_bind_case" 60000 false 0.0.0.0
if run_case "$public_bind_case" "$public_bind_case/output.log"; then
  fail "A public production application bind address should fail"
fi
grep -Fq "SERVER_ADDRESS must be 127.0.0.1 behind the bundled Nginx proxy" \
  "$public_bind_case/output.log" \
  || fail "Missing loopback-only bind validation message"

whitespace_admin_token_case="$TEST_ROOT/whitespace-admin-token"
prepare_case "$whitespace_admin_token_case" 60000 false 127.0.0.1
printf '%s\n' 'ADMIN_TOKEN=" 0123456789abcdefghijklmnopqrstuvwxyz"' \
  >> "$whitespace_admin_token_case/analyticshub.env"
if run_case "$whitespace_admin_token_case" "$whitespace_admin_token_case/output.log"; then
  fail "Admin Token with surrounding whitespace should fail"
fi
grep -Fq "ADMIN_TOKEN must not contain surrounding whitespace" \
  "$whitespace_admin_token_case/output.log" \
  || fail "Missing Admin Token surrounding-whitespace validation message"

blank_2fa_secret_case="$TEST_ROOT/blank-2fa-secret"
prepare_case "$blank_2fa_secret_case" 60000 false 127.0.0.1
printf '%s\n' \
  'APP_SECURITY_2FA_ENABLED=true' \
  'APP_SECURITY_2FA_SECRET="   "' \
  >> "$blank_2fa_secret_case/analyticshub.env"
if run_case "$blank_2fa_secret_case" "$blank_2fa_secret_case/output.log"; then
  fail "Enabled 2FA with a blank secret should fail"
fi
grep -Fq "APP_SECURITY_2FA_SECRET must be replaced with a real value" \
  "$blank_2fa_secret_case/output.log" \
  || fail "Missing blank 2FA secret validation message"

valid_2fa_secret_case="$TEST_ROOT/valid-2fa-secret"
prepare_case "$valid_2fa_secret_case" 60000 false 127.0.0.1
printf '%s\n' \
  'APP_SECURITY_2FA_ENABLED=true' \
  'APP_SECURITY_2FA_SECRET=JBSWY3DPEHPK3PXP' \
  >> "$valid_2fa_secret_case/analyticshub.env"
run_case "$valid_2fa_secret_case" "$valid_2fa_secret_case/output.log" \
  || fail "Enabled 2FA with a valid Base32 secret should pass"

invalid_2fa_secret_index=0
for invalid_2fa_secret in \
  test-totp-secret \
  JBSWY3DPEHPK3PX1 \
  JBSWY3DPEHPK3PXP= \
  JBSWY3DPEHPK3PXPA; do
  invalid_2fa_secret_index=$((invalid_2fa_secret_index + 1))
  invalid_2fa_secret_case="$TEST_ROOT/invalid-2fa-secret-$invalid_2fa_secret_index"
  prepare_case "$invalid_2fa_secret_case" 60000 false 127.0.0.1
  printf '%s\n' \
    'APP_SECURITY_2FA_ENABLED=true' \
    "APP_SECURITY_2FA_SECRET=$invalid_2fa_secret" \
    >> "$invalid_2fa_secret_case/analyticshub.env"
  if run_case "$invalid_2fa_secret_case" "$invalid_2fa_secret_case/output.log"; then
    fail "Enabled 2FA with an invalid Base32 secret should fail"
  fi
  grep -Fq "APP_SECURITY_2FA_SECRET must be a valid unpadded Base32 TOTP secret" \
    "$invalid_2fa_secret_case/output.log" \
    || fail "Missing invalid Base32 2FA secret validation message"
done

invalid_2fa_flag_case="$TEST_ROOT/invalid-2fa-flag"
prepare_case "$invalid_2fa_flag_case" 60000 false 127.0.0.1
printf '%s\n' 'APP_SECURITY_2FA_ENABLED=yes' \
  >> "$invalid_2fa_flag_case/analyticshub.env"
if run_case "$invalid_2fa_flag_case" "$invalid_2fa_flag_case/output.log"; then
  fail "Non-boolean 2FA flag should fail"
fi
grep -Fq "APP_SECURITY_2FA_ENABLED must be true or false" \
  "$invalid_2fa_flag_case/output.log" \
  || fail "Missing 2FA boolean validation message"

echo "PASS: application config preflight boundaries"
