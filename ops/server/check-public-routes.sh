#!/usr/bin/env bash
set -euo pipefail

# Check public AnalyticsHub routes.

BASE_URL="${BASE_URL:-https://analytics.example.com}"
TIMEOUT_SECONDS="${TIMEOUT_SECONDS:-10}"
failures=0

ok() { echo "OK    $1"; }
fail() { echo "FAIL  $1"; failures=$((failures + 1)); }

check_url() {
  local path="$1"
  local url="${BASE_URL}${path}"
  if curl -fsS --max-time "$TIMEOUT_SECONDS" "$url" >/dev/null; then
    ok "$url"
  else
    fail "$url"
  fi
}

check_url "/analyticshub/api/health"

echo
if (( failures > 0 )); then
  echo "Public route check failed: $failures issue(s)."
  exit 1
fi

echo "Public route check passed."
