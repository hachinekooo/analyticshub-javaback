#!/usr/bin/env bash
set -euo pipefail

MIN_JAVA_MAJOR="${MIN_JAVA_MAJOR:-25}"
INSTALL_NGINX="${INSTALL_NGINX:-true}"
ENABLE_FIREWALLD="${ENABLE_FIREWALLD:-true}"
OPEN_WEB_PORTS="${OPEN_WEB_PORTS:-true}"

command_exists() { command -v "$1" >/dev/null 2>&1; }

require_root() {
  if [[ "${EUID}" -ne 0 ]]; then
    echo "Please run as root: sudo $0" >&2
    exit 1
  fi
}

detect_pkg_manager() {
  if command_exists dnf; then echo dnf; return; fi
  if command_exists yum; then echo yum; return; fi
  if command_exists apt-get; then echo apt-get; return; fi
  echo ""
}

install_packages() {
  local manager="$1"
  case "$manager" in
    dnf|yum)
      "$manager" install -y curl openssl firewalld postgresql redis vim git lsof net-tools || true
      if [[ "$INSTALL_NGINX" == "true" ]]; then
        "$manager" install -y nginx || true
      fi
      if ! command_exists java; then
        "$manager" install -y java-latest-openjdk-devel || "$manager" install -y java-21-openjdk-devel || true
      fi
      ;;
    apt-get)
      apt-get update
      apt-get install -y curl openssl postgresql-client redis-tools vim git lsof net-tools || true
      if [[ "$INSTALL_NGINX" == "true" ]]; then
        apt-get install -y nginx || true
      fi
      if ! command_exists java; then
        apt-get install -y openjdk-21-jdk-headless || true
      fi
      ;;
    *)
      echo "No supported package manager found." >&2
      exit 1
      ;;
  esac
}

check_java() {
  if ! command_exists java; then
    echo "Java is still missing after package install." >&2
    exit 1
  fi
  local version major
  version="$(java -version 2>&1 | awk -F '"' '/version/ {print $2; exit}')"
  major="${version%%.*}"
  if [[ "$major" == "1" ]]; then
    major="$(echo "$version" | awk -F. '{print $2}')"
  fi
  if ! [[ "$major" =~ ^[0-9]+$ ]] || (( major < MIN_JAVA_MAJOR )); then
    echo "Java version $version is lower than required $MIN_JAVA_MAJOR." >&2
    exit 1
  fi
  echo "Java version OK: $version"
}

enable_services() {
  if [[ "$ENABLE_FIREWALLD" == "true" ]] && command_exists firewall-cmd; then
    systemctl enable --now firewalld || true
    if [[ "$OPEN_WEB_PORTS" == "true" ]]; then
      firewall-cmd --add-service=http --permanent || true
      firewall-cmd --add-service=https --permanent || true
      firewall-cmd --reload || true
    fi
  fi

  if [[ "$INSTALL_NGINX" == "true" ]] && command_exists nginx; then
    systemctl enable --now nginx || true
  fi
}

require_root
manager="$(detect_pkg_manager)"
install_packages "$manager"
check_java
enable_services

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
bash "$SCRIPT_DIR/check-env.sh"
