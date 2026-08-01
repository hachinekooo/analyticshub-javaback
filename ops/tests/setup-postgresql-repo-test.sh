#!/usr/bin/env bash
set -euo pipefail

TEST_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TARGET_SCRIPT="$TEST_DIR/../server/setup-postgresql.sh"
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

mkdir -p "$TEST_ROOT/bin" "$TEST_ROOT/repos"

cat >"$TEST_ROOT/bin/rpm" <<'STUB'
#!/usr/bin/env bash
if [[ "${1:-}" == "-q" ]]; then
  exit 1
fi
exit 0
STUB

cat >"$TEST_ROOT/bin/dnf" <<'STUB'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "$STUB_DNF_LOG"
STUB

chmod +x "$TEST_ROOT/bin/rpm" "$TEST_ROOT/bin/dnf"

export PATH="$TEST_ROOT/bin:/usr/bin:/bin"
export STUB_DNF_LOG="$TEST_ROOT/dnf.log"
export PGDG_REPO_FILE="$TEST_ROOT/repos/pgdg.repo"
export PG_VERSION=15

# The script has a direct-execution guard so tests can exercise the repository
# writer without installing packages or touching system paths.
source "$TARGET_SCRIPT"
install_postgresql_el dnf

assert_contains "$PGDG_REPO_FILE" "baseurl=https://"
assert_contains "$PGDG_REPO_FILE" "gpgcheck=1"
assert_contains "$PGDG_REPO_FILE" "gpgkey=https://download.postgresql.org/pub/repos/yum/keys/PGDG-RPM-GPG-KEY-RHEL"
assert_contains "$PGDG_REPO_FILE" "sslverify=1"
if grep -Fq -- "gpgcheck=0" "$PGDG_REPO_FILE"; then
  fail "Repository must never disable RPM signature verification"
fi
assert_contains "$STUB_DNF_LOG" "install -y postgresql15-server postgresql15"

echo "PASS: PostgreSQL RPM repository enforces package signature verification"
