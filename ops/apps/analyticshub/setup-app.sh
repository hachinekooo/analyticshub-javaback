#!/usr/bin/env bash
set -euo pipefail

# Project-level setup for one backend app on a shared server.
#
# This script intentionally manages only AnalyticsHub runtime resources:
#   - system user/group
#   - /opt/<app> app directory
#   - /etc/<app>/<app>.env
#   - /var/log/<app>
#   - systemd unit
#
# Server-wide packages, nginx, firewall, PostgreSQL, Redis, and other shared
# services are managed by ops/server in exactly one repo. This keeps one server
# running multiple backends without each project trying to own the host.
APP_NAME="${APP_NAME:-analyticshub}"
APP_USER="${APP_USER:-$APP_NAME}"
APP_GROUP="${APP_GROUP:-$APP_USER}"
APP_DIR="${APP_DIR:-/opt/$APP_NAME}"
ENV_DIR="${ENV_DIR:-/etc/$APP_NAME}"
ENV_FILE="${ENV_FILE:-$ENV_DIR/$APP_NAME.env}"
LOG_DIR="${LOG_DIR:-/var/log/$APP_NAME}"
SERVICE_FILE="${SERVICE_FILE:-/etc/systemd/system/$APP_NAME.service}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

require_root() {
  if [[ "${EUID}" -ne 0 ]]; then
    echo "Please run as root: sudo $0" >&2
    exit 1
  fi
}

random_secret() {
  openssl rand -hex 32
}

ensure_user() {
  getent group "$APP_GROUP" >/dev/null || groupadd --system "$APP_GROUP"
  id "$APP_USER" >/dev/null 2>&1 || useradd --system --gid "$APP_GROUP" --home-dir "$APP_DIR" --shell /usr/sbin/nologin "$APP_USER"
}

prepare_dirs() {
  mkdir -p "$APP_DIR" "$ENV_DIR" "$LOG_DIR"
  chown -R "$APP_USER:$APP_GROUP" "$APP_DIR" "$LOG_DIR"
  chmod 750 "$APP_DIR" "$LOG_DIR"
}

install_env() {
  if [[ -f "$ENV_FILE" ]]; then
    echo "Kept existing env file: $ENV_FILE"
    chmod 600 "$ENV_FILE"
    chown root:root "$ENV_FILE"
    return
  fi

  install -m 600 -o root -g root "$SCRIPT_DIR/analyticshub.env.example" "$ENV_FILE"
  sed -i "s|ADMIN_TOKEN=replace-with-at-least-32-random-characters|ADMIN_TOKEN=$(random_secret)|" "$ENV_FILE"
  echo "Created env file: $ENV_FILE"
}

install_service() {
  local tmp_service
  tmp_service="$(mktemp)"
  sed \
    -e "s|@APP_NAME@|$APP_NAME|g" \
    -e "s|@APP_USER@|$APP_USER|g" \
    -e "s|@APP_GROUP@|$APP_GROUP|g" \
    -e "s|@APP_DIR@|$APP_DIR|g" \
    -e "s|@ENV_FILE@|$ENV_FILE|g" \
    "$SCRIPT_DIR/analyticshub.service.template" > "$tmp_service"
  install -m 644 -o root -g root "$tmp_service" "$SERVICE_FILE"
  rm -f "$tmp_service"
  systemctl daemon-reload
  systemctl enable "$APP_NAME"
  echo "Installed service: $SERVICE_FILE"
}

require_root
ensure_user
prepare_dirs
install_env
install_service

echo "App setup done."
echo "  Jar: $APP_DIR/app.jar"
echo "  Env: $ENV_FILE"
echo "  Logs: $LOG_DIR"
echo "Next: edit env, upload jar, then run: systemctl restart $APP_NAME"
