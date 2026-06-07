#!/usr/bin/env bash
set -euo pipefail

# Install and initialize local PostgreSQL for AnalyticsHub.
# The database is kept localhost-only by default.

PG_VERSION="${PG_VERSION:-15}"
PG_PORT="${PG_PORT:-5432}"
EXPOSE_POSTGRES_PUBLICLY="${EXPOSE_POSTGRES_PUBLICLY:-false}"

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

install_postgresql_el() {
  local manager="$1"
  if ! rpm -q "postgresql${PG_VERSION}-server" >/dev/null 2>&1; then
    "$manager" -qy module disable postgresql || true
    cat >/etc/yum.repos.d/pgdg-alibaba.repo <<EOF
[pgdg${PG_VERSION}]
name=PostgreSQL ${PG_VERSION}
baseurl=https://mirrors.aliyun.com/postgresql/repos/yum/${PG_VERSION}/redhat/rhel-3-x86_64
enabled=1
gpgcheck=0
EOF
    "$manager" install -y "postgresql${PG_VERSION}-server" "postgresql${PG_VERSION}"
  fi
}

install_postgresql_apt() {
  apt-get update
  apt-get install -y "postgresql-${PG_VERSION}" "postgresql-client-${PG_VERSION}"
}

service_name() {
  if systemctl list-unit-files "postgresql-${PG_VERSION}.service" >/dev/null 2>&1; then
    echo "postgresql-${PG_VERSION}"
  else
    echo "postgresql"
  fi
}

data_dir() {
  if [[ -d "/var/lib/pgsql/${PG_VERSION}/data" ]]; then
    echo "/var/lib/pgsql/${PG_VERSION}/data"
  elif [[ -d "/var/lib/postgresql/${PG_VERSION}/main" ]]; then
    echo "/var/lib/postgresql/${PG_VERSION}/main"
  else
    echo "/var/lib/pgsql/${PG_VERSION}/data"
  fi
}

config_dir() {
  if [[ -f "/var/lib/pgsql/${PG_VERSION}/data/postgresql.conf" ]]; then
    echo "/var/lib/pgsql/${PG_VERSION}/data"
  elif [[ -f "/etc/postgresql/${PG_VERSION}/main/postgresql.conf" ]]; then
    echo "/etc/postgresql/${PG_VERSION}/main"
  else
    echo "$(data_dir)"
  fi
}

init_database_if_needed() {
  local dir="$1"
  if [[ -f "$dir/PG_VERSION" ]]; then
    return
  fi

  if command_exists "postgresql-${PG_VERSION}-setup"; then
    "postgresql-${PG_VERSION}-setup" initdb
  elif [[ -x "/usr/pgsql-${PG_VERSION}/bin/postgresql-${PG_VERSION}-setup" ]]; then
    "/usr/pgsql-${PG_VERSION}/bin/postgresql-${PG_VERSION}-setup" initdb
  elif command_exists postgresql-setup; then
    postgresql-setup initdb
  else
    echo "PostgreSQL data dir is not initialized and no initdb helper was found: $dir" >&2
    exit 1
  fi
}

configure_listen() {
  local dir="$1"
  local conf="$dir/postgresql.conf"
  local hba="$dir/pg_hba.conf"

  [[ -f "$conf" ]] || return

  if [[ "$EXPOSE_POSTGRES_PUBLICLY" == "true" ]]; then
    sed -i "s/^[#[:space:]]*listen_addresses[[:space:]]*=.*/listen_addresses = '*'/" "$conf" || true
    grep -q "listen_addresses" "$conf" || echo "listen_addresses = '*'" >> "$conf"
    if [[ -f "$hba" ]] && ! grep -qE '^host\s+all\s+all\s+0\.0\.0\.0/0\s+scram-sha-256' "$hba"; then
      echo "host all all 0.0.0.0/0 scram-sha-256" >> "$hba"
    fi
    if command_exists firewall-cmd; then
      firewall-cmd --add-port="${PG_PORT}/tcp" --permanent || true
      firewall-cmd --reload || true
    fi
  else
    sed -i "s/^[#[:space:]]*listen_addresses[[:space:]]*=.*/listen_addresses = 'localhost'/" "$conf" || true
    grep -q "listen_addresses" "$conf" || echo "listen_addresses = 'localhost'" >> "$conf"
  fi
}

require_root
manager="$(detect_pkg_manager)"
case "$manager" in
  dnf|yum) install_postgresql_el "$manager" ;;
  apt-get) install_postgresql_apt ;;
  *) echo "No supported package manager found." >&2; exit 1 ;;
esac

PG_SERVICE="$(service_name)"
PG_DATA_DIR="$(data_dir)"
PG_CONFIG_DIR="$(config_dir)"
init_database_if_needed "$PG_DATA_DIR"
configure_listen "$PG_CONFIG_DIR"

systemctl enable --now "$PG_SERVICE"
systemctl restart "$PG_SERVICE"

echo "PostgreSQL is ready."
echo "  Service: $PG_SERVICE"
echo "  Data dir: $PG_DATA_DIR"
echo "  Config dir: $PG_CONFIG_DIR"
