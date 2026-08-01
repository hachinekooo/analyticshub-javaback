#!/usr/bin/env bash
set -euo pipefail

TEST_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TARGET_SCRIPT="$TEST_DIR/../server/create-analytics-database.sh"
TEST_ROOT="$(mktemp -d)"

cleanup() {
  rm -rf -- "$TEST_ROOT"
}
trap cleanup EXIT

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

assert_contains() {
  local file="$1"
  local expected="$2"
  grep -Fq -- "$expected" "$file" || fail "Expected $file to contain: $expected"
}

assert_not_contains() {
  local file="$1"
  local unexpected="$2"
  if grep -Fq -- "$unexpected" "$file"; then
    fail "Expected $file not to contain: $unexpected"
  fi
}

assert_equals() {
  local expected="$1"
  local actual="$2"
  local message="$3"
  [[ "$actual" == "$expected" ]] || fail "$message (expected '$expected', got '$actual')"
}

prepare_case() {
  local case_dir="$1"
  mkdir -p "$case_dir/bin"
  : > "$case_dir/sql.log"

  cat > "$case_dir/bin/id" <<'STUB'
#!/usr/bin/env bash
if [[ "${1:-}" == "-un" ]]; then
  echo postgres
  exit 0
fi
exec /usr/bin/id "$@"
STUB

  cat > "$case_dir/bin/openssl" <<'STUB'
#!/usr/bin/env bash
count=0
if [[ -f "$STUB_OPENSSL_COUNT" ]]; then
  count="$(cat "$STUB_OPENSSL_COUNT")"
fi
echo "$((count + 1))" > "$STUB_OPENSSL_COUNT"
echo 'abcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ'
STUB

  cat > "$case_dir/bin/install" <<'STUB'
#!/usr/bin/env bash
target="${!#}"
: > "$target"
STUB

  cat > "$case_dir/bin/psql" <<'STUB'
#!/usr/bin/env bash
printf 'ARGS %s\n' "$*" >> "$STUB_SQL_LOG"

if [[ "$*" == *"SELECT 1 FROM pg_roles"* ]]; then
  if [[ -f "$STUB_ROLE_STATE" ]]; then
    echo 1
  fi
  exit 0
fi

payload="$(cat)"
printf '%s\n' "$payload" >> "$STUB_SQL_LOG"
if [[ "$payload" == *"CREATE ROLE analytic LOGIN PASSWORD"* ]]; then
  : > "$STUB_ROLE_STATE"
fi
STUB

  chmod +x "$case_dir/bin/id" "$case_dir/bin/install" "$case_dir/bin/openssl" "$case_dir/bin/psql"
}

run_provision() {
  local case_dir="$1"
  local output_file="$2"
  local supplied_password="${3:-}"

  (
    export PATH="$case_dir/bin:/usr/bin:/bin"
    export STUB_ROLE_STATE="$case_dir/role.exists"
    export STUB_SQL_LOG="$case_dir/sql.log"
    export STUB_OPENSSL_COUNT="$case_dir/openssl.count"
    export CREDENTIAL_FILE="$case_dir/credentials.env"
    export ENV_FILE="$case_dir/analyticshub.env"
    export PG_SUPERUSER=postgres
    export DB_NAME=analytics
    export DB_USER=analytic
    export DB_SCHEMA=analytics
    if [[ -n "$supplied_password" ]]; then
      export ANALYTICS_DB_PASSWORD="$supplied_password"
    else
      unset ANALYTICS_DB_PASSWORD || true
    fi

    # Source the script so the test can replace only the root guard. All
    # provisioning commands still go through the command stubs above.
    source "$TARGET_SCRIPT"
    require_root() { :; }
    main
  ) > "$output_file"
}

test_first_install_and_rerun() {
  local case_dir="$TEST_ROOT/first-install"
  local credentials_before
  prepare_case "$case_dir"

  run_provision "$case_dir" "$case_dir/first.out"

  [[ -f "$case_dir/role.exists" ]] || fail "First install did not create the database role"
  [[ -f "$case_dir/credentials.env" ]] || fail "First install did not write credentials"
  assert_contains "$case_dir/credentials.env" "ANALYTICS_DB_PASSWORD=abcdefghijklmnopqrstuvwxyz012345"
  assert_contains "$case_dir/sql.log" "CREATE ROLE analytic LOGIN PASSWORD"
  assert_not_contains "$case_dir/sql.log" "ALTER ROLE"
  assert_equals "1" "$(cat "$case_dir/openssl.count")" "First install should generate one password"

  credentials_before="$(cat "$case_dir/credentials.env")"
  run_provision "$case_dir" "$case_dir/rerun.out" "must-not-replace-existing-password"

  assert_equals "$credentials_before" "$(cat "$case_dir/credentials.env")" "Rerun changed the credential file"
  assert_equals "1" "$(cat "$case_dir/openssl.count")" "Rerun generated another password"
  assert_equals "1" "$(grep -c "CREATE ROLE analytic LOGIN PASSWORD" "$case_dir/sql.log")" "Rerun attempted to recreate the role"
  assert_not_contains "$case_dir/sql.log" "ALTER ROLE"
  assert_contains "$case_dir/rerun.out" "password and secret files were kept unchanged"
}

test_existing_env_is_untouched() {
  local case_dir="$TEST_ROOT/existing-env"
  local env_before credentials_before
  prepare_case "$case_dir"
  : > "$case_dir/role.exists"

  cat > "$case_dir/analyticshub.env" <<'ENV'
DB_NAME=analytics
DB_SCHEMA=analytics
DB_USER=analytic
DB_PASSWORD=existing-env-password
ENV
  cat > "$case_dir/credentials.env" <<'ENV'
ANALYTICS_DB_USER=analytic
ANALYTICS_DB_PASSWORD=existing-credential-password
ANALYTICS_DATABASE=analytics
ANALYTICS_SCHEMA=analytics
ENV
  env_before="$(cat "$case_dir/analyticshub.env")"
  credentials_before="$(cat "$case_dir/credentials.env")"

  run_provision "$case_dir" "$case_dir/existing.out" "must-not-rotate-password"

  assert_equals "$env_before" "$(cat "$case_dir/analyticshub.env")" "Bootstrap changed the existing app env"
  assert_equals "$credentials_before" "$(cat "$case_dir/credentials.env")" "Bootstrap changed existing credentials"
  [[ ! -f "$case_dir/openssl.count" ]] || fail "Bootstrap generated a password for an existing role"
  assert_not_contains "$case_dir/sql.log" "CREATE ROLE"
  assert_not_contains "$case_dir/sql.log" "ALTER ROLE"
  assert_contains "$case_dir/existing.out" "Use ops/analyticshub rotate-secrets for intentional password changes."
}

test_first_install_and_rerun
test_existing_env_is_untouched

echo "PASS: create-analytics-database bootstrap idempotency"
