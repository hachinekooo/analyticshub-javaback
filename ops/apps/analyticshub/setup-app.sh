#!/usr/bin/env bash
set -euo pipefail

APP_NAME="analyticshub"
APP_USER="analyticshub"
APP_GROUP="analyticshub"
APP_DIR="/opt/analyticshub"
ENV_DIR="/etc/analyticshub"
ENV_FILE="$ENV_DIR/analyticshub.env"
LOG_DIR="/var/log/analyticshub"
SERVICE_FILE="/etc/systemd/system/analyticshub.service"
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
  install -m 644 -o root -g root "$SCRIPT_DIR/analyticshub.service.template" "$SERVICE_FILE"
  systemctl daemon-reload
  systemctl enable analyticshub
  echo "Installed service: $SERVICE_FILE"
}

require_root
ensure_user
prepare_dirs
install_env
install_service

echo "App setup done. Put jar at $APP_DIR/app.jar, edit $ENV_FILE, then run systemctl restart analyticshub."
