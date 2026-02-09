#!/usr/bin/env bash
set -euo pipefail

# ============================================================
# AnalyticsHub - Alibaba Cloud Linux 3 初始化 & 部署脚本
# 适配：PostgreSQL 15 + Nginx + systemd + Spring Boot(后端jar)
# ============================================================

# -----------------------------
# 你需要改的变量（建议全部改掉）
# -----------------------------
APP_NAME="analyticshub"
APP_USER="analyticshub"
APP_GROUP="analyticshub"
APP_PORT="3001"

# Jar 放置路径（你用 scp 上传到这里，或自己下载）
APP_DIR="/opt/analyticshub"
APP_JAR="${APP_DIR}/app.jar"
APP_ENV_DIR="/etc/analyticshub"
APP_ENV_FILE="${APP_ENV_DIR}/analyticshub.env"
APP_LOG_DIR="/var/log/analyticshub"

# Spring Profile
SPRING_PROFILE="prod"

# 管理端 Token（用于 /actuator/**，生产强制）
ADMIN_TOKEN="${ADMIN_TOKEN:-}"

# PostgreSQL
PG_VERSION="15"
PG_DB="analytics"
PG_USER="analytic"
PG_PASSWORD="${PG_PASSWORD:-}"     # 留空则自动生成
PG_HOST="127.0.0.1"
PG_PORT="5432"

# Nginx - 前端部署路径配置（使用不同端口避免冲突）
NGINX_SERVER_NAME="${NGINX_SERVER_NAME:-_}"  # 可填域名或公网IP
VUE_DIST_DIR="${VUE_DIST_DIR:-/usr/share/nginx/html/analyticshub-frontend/dist}"  # Vue 前端部署路径
NGINX_PORT="${NGINX_PORT:-3000}"  # 使用3000端口，避免与现有80端口冲突
NGINX_CONF="/etc/nginx/conf.d/${APP_NAME}.conf"

# 安全开关（默认不动 SSH，避免锁死）
HARDEN_SSH="${HARDEN_SSH:-false}"            # true/false
DISABLE_SSH_PASSWORD="${DISABLE_SSH_PASSWORD:-false}"  # true/false（非常危险，确保已有密钥登录）

# 是否开放 PostgreSQL 到公网（强烈不建议）
EXPOSE_POSTGRES_PUBLICLY="${EXPOSE_POSTGRES_PUBLICLY:-false}"  # true/false

# 凭据输出（仅 root 可读）
CRED_FILE="/root/${APP_NAME}-credentials.txt"

# -----------------------------
# Helpers - 增强版（支持环境检测和跳过）
# -----------------------------
command_exists() { command -v "$1" >/dev/null 2>&1; }

ensure_dir() {
  local d="$1"
  sudo mkdir -p "$d"
}

write_root_600() {
  local f="$1"
  sudo touch "$f"
  sudo chown root:root "$f"
  sudo chmod 600 "$f"
}

random_password() {
  if command_exists pwgen; then
    pwgen -s 64 1
  else
    openssl rand -base64 48 | tr -d '=+/ ' | cut -c1-64
  fi
}

is_alicloud_linux_3() {
  grep -qi "Alibaba Cloud Linux 3" /etc/os-release 2>/dev/null
}

# 环境检测函数
nginx_installed() {
  command_exists nginx && sudo systemctl is-active --quiet nginx 2>/dev/null
}

nginx_configured() {
  # 检查是否已有当前应用的配置（在 nginx.conf 或 conf.d/*.conf 中）
  if [ -f "/etc/nginx/nginx.conf" ] && sudo grep -q "${APP_NAME}" /etc/nginx/nginx.conf 2>/dev/null; then
    return 0
  fi
  
  # 检查 conf.d 目录中的配置文件
  if [ -d "/etc/nginx/conf.d" ]; then
    for conf_file in /etc/nginx/conf.d/*.conf; do
      if [ -f "$conf_file" ] && sudo grep -q "${APP_NAME}" "$conf_file" 2>/dev/null; then
        return 0
      fi
    done
  fi
  
  return 1
}

postgresql_installed() {
  command_exists psql && sudo systemctl is-active --quiet "postgresql-${PG_VERSION}" 2>/dev/null
}

postgresql_db_exists() {
  sudo -u postgres psql -tAc "SELECT 1 FROM pg_database WHERE datname='${PG_DB}'" 2>/dev/null | grep -q 1
}

postgresql_user_exists() {
  sudo -u postgres psql -tAc "SELECT 1 FROM pg_roles WHERE rolname='${PG_USER}'" 2>/dev/null | grep -q 1
}

app_service_exists() {
  [ -f "/etc/systemd/system/${APP_NAME}.service" ]
}

skip_if() {
  local check_name="$1"
  local check_func="$2"
  local message="$3"
  
  if $check_func; then
    echo "✅ [SKIP] ${check_name} 已存在 - ${message}"
    return 0
  else
    echo "🔄 [SETUP] ${check_name} 需要安装配置"
    return 1
  fi
}

# -----------------------------
# 0. 基础检查
# -----------------------------
if ! is_alicloud_linux_3; then
  echo ">>> WARN: 当前系统不是 Alibaba Cloud Linux 3，仍继续执行，但你需自行确认兼容性。"
fi

echo ">>> Updating system packages..."
sudo dnf update -y

echo ">>> Setting timezone to Asia/Shanghai..."
sudo timedatectl set-timezone Asia/Shanghai || true

echo ">>> Installing essential tools..."
sudo dnf install -y wget curl vim git net-tools unzip htop lsof firewalld openssl

# pwgen 可选（用于生成密码）
sudo dnf install -y pwgen || true

# JDK25 安装（AnalyticsHub 要求）
echo ">>> 检查并安装 JDK25..."
if ! command_exists java || ! java -version 2>&1 | grep -q "25"; then
    sudo dnf install -y java-latest-openjdk-devel
    echo "✅ JDK25 安装完成"
else
    echo "✅ JDK25 已安装"
fi

# -----------------------------
# 1. 防火墙
# -----------------------------
echo ">>> Enabling firewalld..."
sudo systemctl enable --now firewalld

echo ">>> Opening port ${APP_PORT}/tcp..."
sudo firewall-cmd --add-port="${APP_PORT}/tcp" --permanent
sudo firewall-cmd --reload

# -----------------------------
# 2. 安装 & 配置 Nginx（支持跳过）
# -----------------------------
if skip_if "Nginx" "nginx_installed" "Nginx 服务已运行"; then
  echo ">>> Nginx 已安装，跳过安装步骤"
else
  echo ">>> Installing nginx..."
  sudo dnf install -y nginx
  sudo systemctl enable --now nginx
fi

# 检查是否需要配置 Nginx（如果已有当前应用配置则跳过）
if skip_if "Nginx配置" "nginx_configured" "Nginx 已配置 ${APP_NAME}"; then
  echo ">>> Nginx 已包含 ${APP_NAME} 配置，跳过配置步骤"
else
  echo ">>> 检测到 Nginx 未配置 ${APP_NAME}，添加配置..."
  
  echo ">>> Writing nginx config: ${NGINX_CONF} ..."
    sudo tee "${NGINX_CONF}" >/dev/null <<EOF
server {
    listen ${NGINX_PORT};
    server_name ${NGINX_SERVER_NAME};

    # Vue 静态站点（你部署 dist 到 ${VUE_DIST_DIR} 即可）
    location / {
        root ${VUE_DIST_DIR};
        index index.html;
        try_files \$uri \$uri/ /index.html;
    }

    # 后端 API 反代（按需启用：前端请求 /api/* -> 后端）
    location /api/ {
        proxy_pass http://127.0.0.1:${APP_PORT}/;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
    }
}
EOF

  sudo nginx -t
  sudo systemctl restart nginx
fi

# 确保 Vue 目录存在（幂等操作）
echo ">>> Ensuring Vue dist dir: ${VUE_DIST_DIR}..."
ensure_dir "${VUE_DIST_DIR}"
sudo chown -R root:root "${VUE_DIST_DIR}"
sudo chmod -R 755 "${VUE_DIST_DIR}"

if [ ! -f "${VUE_DIST_DIR}/index.html" ]; then
  sudo tee "${VUE_DIST_DIR}/index.html" >/dev/null <<'EOF'
<!doctype html>
<html>
  <head><meta charset="utf-8"/><title>Vue Placeholder</title></head>
  <body><h1>Vue dist not deployed yet.</h1></body>
</html>
EOF
fi

echo ">>> Opening ${NGINX_PORT}/tcp..."
sudo firewall-cmd --add-port=${NGINX_PORT}/tcp --permanent
sudo firewall-cmd --reload

# -----------------------------
# 3. 安装 PostgreSQL 15（支持跳过）
# -----------------------------
if skip_if "PostgreSQL" "postgresql_installed" "PostgreSQL ${PG_VERSION} 已安装"; then
  echo ">>> PostgreSQL ${PG_VERSION} 已安装，跳过安装步骤"
  PG_DATA_DIR="/var/lib/pgsql/${PG_VERSION}/data"
else
  echo ">>> Installing PostgreSQL ${PG_VERSION}..."

  # Alibaba Cloud Linux 3 基于 Anolis/EL8，使用阿里云镜像
  echo ">>> 使用阿里云镜像安装 PostgreSQL..."
  
  # 清理可能存在的冲突仓库配置
  sudo rm -f /etc/yum.repos.d/pgdg-redhat-all.repo /etc/yum.repos.d/pgdg-redhat.repo 2>/dev/null || true
  sudo dnf clean all
  
  # 使用阿里云镜像（Alibaba Cloud Linux 3 使用 rhel-3）
  sudo tee /etc/yum.repos.d/pgdg-alibaba.repo >/dev/null <<'EOF'
[pgdg15]
name=PostgreSQL 15 for Alibaba Cloud Linux 3
baseurl=https://mirrors.aliyun.com/postgresql/repos/yum/15/redhat/rhel-3-x86_64
enabled=1
gpgcheck=0
EOF
  
  # 如果阿里云镜像也失败，使用系统自带的PostgreSQL
  sudo dnf -qy module disable postgresql || true

  # 安装PostgreSQL
  if ! sudo dnf install -y "postgresql${PG_VERSION}-server" "postgresql${PG_VERSION}"; then
    echo "❌ PostgreSQL安装失败，尝试使用系统自带版本..."
    sudo dnf install -y postgresql-server postgresql
    PG_VERSION=""  # 使用系统默认版本
  fi
  
  # 启用并启动服务
  if [ -n "${PG_VERSION}" ]; then
    sudo systemctl enable --now "postgresql-${PG_VERSION}"
    PG_DATA_DIR="/var/lib/pgsql/${PG_VERSION}/data"
  else
    sudo systemctl enable --now postgresql
    PG_DATA_DIR="/var/lib/pgsql/data"
  fi

  # 初始化数据库（只在首次需要）
  if [ ! -f "${PG_DATA_DIR}/PG_VERSION" ]; then
    echo ">>> Initializing PostgreSQL data dir..."
    if [ -n "${PG_VERSION}" ]; then
      sudo "/usr/pgsql-${PG_VERSION}/bin/postgresql-${PG_VERSION}-setup" initdb
    else
      sudo postgresql-setup initdb
    fi
    sudo systemctl restart "postgresql${PG_VERSION:+-}${PG_VERSION}"
  fi
fi

# PostgreSQL 监听（默认仅本机；如你要远程连接，自己开关）
if [ "${EXPOSE_POSTGRES_PUBLICLY}" = "true" ]; then
  echo ">>> WARNING: Exposing PostgreSQL publicly (not recommended)."
  sudo sed -i "s/^#listen_addresses =.*/listen_addresses = '*'/" "${PG_DATA_DIR}/postgresql.conf" || true
  if ! sudo grep -qE '^host\s+all\s+all\s+0\.0\.0\.0/0\s+md5' "${PG_DATA_DIR}/pg_hba.conf"; then
    echo "host all all 0.0.0.0/0 md5" | sudo tee -a "${PG_DATA_DIR}/pg_hba.conf" >/dev/null
  fi
  sudo firewall-cmd --add-port="${PG_PORT}/tcp" --permanent
  sudo firewall-cmd --reload
else
  sudo sed -i "s/^#listen_addresses =.*/listen_addresses = 'localhost'/" "${PG_DATA_DIR}/postgresql.conf" || true
fi

sudo systemctl restart "postgresql-${PG_VERSION}"

# 创建 DB/User（幂等）
if [ -z "${PG_PASSWORD}" ]; then
  PG_PASSWORD="$(random_password)"
fi

# 检查并创建用户（如果不存在）
if skip_if "PostgreSQL用户" "postgresql_user_exists" "PostgreSQL 用户 ${PG_USER} 已存在"; then
  echo ">>> PostgreSQL 用户 ${PG_USER} 已存在，跳过创建"
else
  echo ">>> Creating PostgreSQL user: ${PG_USER}"
  # 使用临时目录避免权限警告
  TEMP_DIR="$(mktemp -d)"
  cd "${TEMP_DIR}" || exit 1
  sudo -u postgres psql -v ON_ERROR_STOP=1 -c "CREATE ROLE ${PG_USER} LOGIN PASSWORD '${PG_PASSWORD}';"
  # 清理临时目录
  cd /tmp && rm -rf "${TEMP_DIR}"
fi

# 检查并创建数据库（如果不存在）
if skip_if "PostgreSQL数据库" "postgresql_db_exists" "PostgreSQL 数据库 ${PG_DB} 已存在"; then
  echo ">>> PostgreSQL 数据库 ${PG_DB} 已存在，跳过创建"
else
  echo ">>> Creating PostgreSQL database: ${PG_DB}"
  # 使用临时目录避免权限警告
  TEMP_DIR="$(mktemp -d)"
  cd "${TEMP_DIR}" || exit 1
  sudo -u postgres psql -v ON_ERROR_STOP=1 -c "CREATE DATABASE ${PG_DB} OWNER ${PG_USER};"
  # 清理临时目录
  cd /tmp && rm -rf "${TEMP_DIR}"
fi

# 确保 schema 权限（有些系统会在 template1 里收紧 public schema 权限，导致 Flyway 建表失败）
echo ">>> Ensuring schema privileges on database: ${PG_DB} ..."
TEMP_DIR="$(mktemp -d)"
cd "${TEMP_DIR}" || exit 1
sudo -u postgres psql -v ON_ERROR_STOP=1 -c "ALTER DATABASE ${PG_DB} OWNER TO ${PG_USER};"
sudo -u postgres psql -v ON_ERROR_STOP=1 -d "${PG_DB}" -c "GRANT USAGE, CREATE ON SCHEMA public TO ${PG_USER};"
sudo -u postgres psql -v ON_ERROR_STOP=1 -d "${PG_DB}" -c "ALTER SCHEMA public OWNER TO ${PG_USER};"
cd /tmp && rm -rf "${TEMP_DIR}"

# -----------------------------
# 4. 部署后端（systemd）- 支持跳过
# -----------------------------
if skip_if "应用用户" "id -u ${APP_USER} >/dev/null 2>&1" "应用用户 ${APP_USER} 已存在"; then
  echo ">>> 应用用户 ${APP_USER} 已存在，跳过创建"
else
  echo ">>> Creating app user/group..."
  if ! getent group "${APP_GROUP}" >/dev/null; then
    sudo groupadd --system "${APP_GROUP}"
  fi
  if ! id -u "${APP_USER}" >/dev/null 2>&1; then
    sudo useradd --system --gid "${APP_GROUP}" --shell /sbin/nologin --home-dir "${APP_DIR}" "${APP_USER}"
  fi
fi

ensure_dir "${APP_DIR}"
ensure_dir "${APP_LOG_DIR}"
ensure_dir "${APP_ENV_DIR}"
sudo chown -R "${APP_USER}:${APP_GROUP}" "${APP_DIR}" "${APP_LOG_DIR}"
sudo chmod 750 "${APP_DIR}" "${APP_LOG_DIR}"

# 环境文件（包含敏感信息，权限严格）
echo ">>> Writing env file: ${APP_ENV_FILE} ..."
# 确保 ADMIN_TOKEN 有值
if [ -z "${ADMIN_TOKEN}" ]; then
  ADMIN_TOKEN="$(openssl rand -base64 48 | tr -d '=+/ ' | cut -c1-64)"
  echo "🔐 自动生成 ADMIN_TOKEN: ${ADMIN_TOKEN}"
fi

sudo tee "${APP_ENV_FILE}" >/dev/null <<EOF
SPRING_PROFILES_ACTIVE=${SPRING_PROFILE}
SERVER_PORT=${APP_PORT}

SPRING_DATASOURCE_URL=jdbc:postgresql://${PG_HOST}:${PG_PORT}/${PG_DB}
SPRING_DATASOURCE_USERNAME=${PG_USER}
SPRING_DATASOURCE_PASSWORD=${PG_PASSWORD}

ADMIN_TOKEN=${ADMIN_TOKEN}
EOF
sudo chown root:root "${APP_ENV_FILE}"
sudo chmod 600 "${APP_ENV_FILE}"

# systemd service（如果不存在则创建）
SERVICE_FILE="/etc/systemd/system/${APP_NAME}.service"
if skip_if "Systemd服务" "app_service_exists" "Systemd 服务 ${APP_NAME} 已存在"; then
  echo ">>> Systemd 服务 ${APP_NAME} 已存在，跳过创建"
else
  echo ">>> Writing systemd service: ${SERVICE_FILE} ..."
  sudo tee "${SERVICE_FILE}" >/dev/null <<EOF
[Unit]
Description=${APP_NAME}
After=network.target postgresql-${PG_VERSION}.service
Wants=postgresql-${PG_VERSION}.service

[Service]
Type=simple
User=${APP_USER}
Group=${APP_GROUP}
WorkingDirectory=${APP_DIR}
EnvironmentFile=${APP_ENV_FILE}
ExecStart=/usr/bin/java -jar ${APP_JAR}
Restart=always
RestartSec=5
LimitNOFILE=65536

[Install]
WantedBy=multi-user.target
EOF

  sudo systemctl daemon-reload
  sudo systemctl enable "${APP_NAME}" || true
fi

# -----------------------------
# 5. SSH 安全（可选）
# -----------------------------
if [ "${HARDEN_SSH}" = "true" ]; then
  echo ">>> Hardening SSH..."
  sudo sed -i "s/^#PermitRootLogin.*/PermitRootLogin prohibit-password/" /etc/ssh/sshd_config || true
  if [ "${DISABLE_SSH_PASSWORD}" = "true" ]; then
    sudo sed -i "s/^#PasswordAuthentication.*/PasswordAuthentication no/" /etc/ssh/sshd_config || true
  fi
  sudo systemctl restart sshd
else
  echo ">>> SSH hardening skipped (HARDEN_SSH=false)."
fi

# -----------------------------
# 6. 输出凭据
# -----------------------------
echo ">>> Writing credentials to ${CRED_FILE} ..."
write_root_600 "${CRED_FILE}"
sudo tee "${CRED_FILE}" >/dev/null <<EOF
[${APP_NAME}]
  AppPort: ${APP_PORT}
  AppJarPath: ${APP_JAR}
  EnvFile: ${APP_ENV_FILE}
  LogsDir: ${APP_LOG_DIR}
  NginxConf: ${NGINX_CONF}
  VueDistDir: ${VUE_DIST_DIR}

[PostgreSQL]
  Version: ${PG_VERSION}
  Host: ${PG_HOST}
  Port: ${PG_PORT}
  Database: ${PG_DB}
  Username: ${PG_USER}
  Password: ${PG_PASSWORD}

[AdminToken]
  ADMIN_TOKEN: ${ADMIN_TOKEN}

[Commands]
  # 上传 jar 后启动：
  #   systemctl start ${APP_NAME}
  # 查看状态：
  #   systemctl status ${APP_NAME} -l
  # 查看日志：
  #   journalctl -u ${APP_NAME} -f
EOF

echo ">>> DONE."
echo ">>> Next step: upload your jar to ${APP_JAR} then run: sudo systemctl start ${APP_NAME}"
