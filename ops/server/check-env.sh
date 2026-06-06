#!/usr/bin/env bash
set -euo pipefail

MIN_JAVA_MAJOR="${MIN_JAVA_MAJOR:-25}"
failures=0

ok() { echo "OK    $1"; }
warn() { echo "WARN  $1"; }
fail() { echo "FAIL  $1"; failures=$((failures + 1)); }
command_exists() { command -v "$1" >/dev/null 2>&1; }

check_os() {
  if [[ -f /etc/os-release ]]; then
    . /etc/os-release
    ok "os: ${PRETTY_NAME:-unknown}"
  else
    warn "cannot read /etc/os-release"
  fi
}

check_command() {
  local cmd="$1"
  if command_exists "$cmd"; then
    ok "command exists: $cmd"
  else
    fail "command missing: $cmd"
  fi
}

check_java() {
  if ! command_exists java; then
    fail "java command missing"
    return
  fi
  local version major
  version="$(java -version 2>&1 | awk -F '"' '/version/ {print $2; exit}')"
  major="${version%%.*}"
  if [[ "$major" == "1" ]]; then
    major="$(echo "$version" | awk -F. '{print $2}')"
  fi
  if [[ "$major" =~ ^[0-9]+$ ]] && (( major >= MIN_JAVA_MAJOR )); then
    ok "java version $version"
  else
    fail "java version $version is lower than $MIN_JAVA_MAJOR"
  fi
}

check_service_optional() {
  local service="$1"
  if systemctl list-unit-files "$service" >/dev/null 2>&1; then
    if systemctl is-active --quiet "$service"; then
      ok "service active: $service"
    else
      warn "service installed but inactive: $service"
    fi
  else
    warn "service not installed: $service"
  fi
}

check_os
check_java
check_command curl
check_command openssl
check_command systemctl
check_command nginx
check_command psql
check_command redis-cli
check_service_optional nginx.service
check_service_optional firewalld.service
check_service_optional postgresql.service
check_service_optional postgresql-15.service
check_service_optional redis.service
check_service_optional redis-server.service

echo
if (( failures > 0 )); then
  echo "Server environment check failed: $failures issue(s)."
  exit 1
fi

echo "Server environment check passed."
