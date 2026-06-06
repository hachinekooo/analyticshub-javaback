#!/usr/bin/env bash
set -euo pipefail

# AnalyticsHub app 槽位初始化。
# 用法：
#   sudo -E env DEPLOY_ENV=prod bash ops/apps/analyticshub/setup-app.sh
#   sudo -E env DEPLOY_ENV=test bash ops/apps/analyticshub/setup-app.sh

BASE_NAME="${BASE_NAME:-analyticshub}"
DEPLOY_ENV="${DEPLOY_ENV:-prod}"
APP_NAME="${APP_NAME:-${BASE_NAME}-${DEPLOY_ENV}}"
APP_USER="${APP_USER:-$APP_NAME}"
APP_GROUP="${APP_GROUP:-$APP_USER}"
APP_DIR="${APP_DIR:-/opt/$APP_NAME}"
ENV_DIR="${ENV_DIR:-/etc/$APP_NAME}"
ENV_FILE="${ENV_FILE:-$ENV_DIR/$APP_NAME.env}"
LOG_DIR="${LOG_DIR:-/var/log/$APP_NAME}"
SERVICE_FILE="${SERVICE_FILE:-/etc/systemd/system/$APP_NAME.service}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

case "$DEPLOY_ENV" in
  prod)
    SERVER_PORT="${SERVER_PORT:-3001}"
    DB_NAME="${DB_NAME:-analytics_prod}"
    DB_USER="${DB_USER:-analytic_prod}"
    ;;
  test)
    SERVER_PORT="${SERVER_PORT:-13001}"
    DB_NAME="${DB_NAME:-analytics_test}"
    DB_USER="${DB_USER:-analytic_test}"
    ;;
  *)
    echo "DEPLOY_ENV must be prod or test, got: $DEPLOY_ENV" >&2
    exit 1
    ;;
esac

DB_SCHEMA="${DB_SCHEMA:-analytics}"
SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-prod}"
JAVA_OPTS="${JAVA_OPTS:--Xms96m -Xmx256m -XX:MaxMetaspaceSize=128m -XX:ReservedCodeCacheSize=48m -XX:MaxDirectMemorySize=48m}"
MEMORY_HIGH="${MEMORY_HIGH:-400M}"
MEMORY_MAX="${MEMORY_MAX:-520M}"
ENABLE_SERVICE="${ENABLE_SERVICE:-false}"

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
  sed -i \
    -e "s|SERVER_PORT=3001|SERVER_PORT=${SERVER_PORT}|" \
    -e "s|DB_NAME=analytics_prod|DB_NAME=${DB_NAME}|" \
    -e "s|DB_SCHEMA=analytics|DB_SCHEMA=${DB_SCHEMA}|" \
    -e "s|DB_USER=analytic_prod|DB_USER=${DB_USER}|" \
    -e "s|SPRING_PROFILES_ACTIVE=prod|SPRING_PROFILES_ACTIVE=${SPRING_PROFILES_ACTIVE}|" \
    -e "s|LOG_PATH=/var/log/analyticshub-prod|LOG_PATH=${LOG_DIR}|" \
    -e "s|LOG_FILE=analyticshub-prod|LOG_FILE=${APP_NAME}|" \
    -e "s|ADMIN_TOKEN=replace-with-at-least-32-random-characters|ADMIN_TOKEN=$(random_secret)|" \
    "$ENV_FILE"
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

  install -d -m 755 -o root -g root "/etc/systemd/system/$APP_NAME.service.d"
  cat >"/etc/systemd/system/$APP_NAME.service.d/10-jvm-memory.conf" <<EOF
[Service]
ExecStart=
ExecStart=/usr/bin/java ${JAVA_OPTS} -jar ${APP_DIR}/app.jar
EOF
  cat >"/etc/systemd/system/$APP_NAME.service.d/20-resource-guard.conf" <<EOF
[Service]
MemoryHigh=${MEMORY_HIGH}
MemoryMax=${MEMORY_MAX}
OOMPolicy=stop
Restart=on-failure
RestartSec=10
EOF

  systemctl daemon-reload
  if [[ "$ENABLE_SERVICE" == "true" ]]; then
    systemctl enable "$APP_NAME"
  else
    systemctl disable "$APP_NAME" >/dev/null 2>&1 || true
  fi
  echo "Installed service: $SERVICE_FILE"
}

require_root
ensure_user
prepare_dirs
install_env
install_service

echo "AnalyticsHub slot setup done."
echo "  Service: $APP_NAME"
echo "  Jar: $APP_DIR/app.jar"
echo "  Env: $ENV_FILE"
echo "  Logs: $LOG_DIR"
echo "Next: edit env, upload jar, then run: systemctl restart $APP_NAME"
