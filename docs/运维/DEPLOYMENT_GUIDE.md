# AnalyticsHub 部署指南（环境准备 + JAR 部署）

基本布置：
- 自动化环境准备（`docs/运维/init-env.sh`）
- 手动上传并部署 JAR（适合环境已准备好的情况）


## 为项目创建数据库与用户（管理端项目配置前置条件）

管理端创建项目**不会自动创建数据库/用户**，只会保存连接信息。为某个项目配置了 `dbName/dbUser/dbPassword` 后，需要你提前在 PostgreSQL 里创建对应的数据库与用户。

- Docker 安装的 PostgreSQL 操作示例见：[Docker_PostgreSQL_Guild.md 的 3.3 小节](../Docker_PostgreSQL_Guild.md#33-为项目创建数据库与用户管理端项目配置前置条件)

## 自动化环境准备（init-env.sh）

### 文件位置
- 主脚本: `docs/运维/init-env.sh`
- 本文档: `docs/运维/DEPLOYMENT_GUIDE.md`

### 主要功能
- 自动检测和跳过已安装的组件
- 支持环境变量自定义配置
- 幂等执行（可安全重复运行）
- 适配多种部署场景

### 环境变量配置
部署脚本支持通过环境变量自定义所有路径和配置（以下变量由 `docs/运维/init-env.sh` 读取，用于生成/应用 Nginx 配置并准备前端静态目录；后端应用本身不读取 `VUE_DIST_DIR` / `NGINX_SERVER_NAME`）：

```bash
# 应用配置
export APP_NAME="analyticshub"
export APP_PORT="3001"

# 前端 dist 部署路径（Nginx 静态站点 root）
export VUE_DIST_DIR="/usr/share/nginx/html/your-custom-path"

# 数据库配置
export PG_DB="your_database_name"
export PG_USER="your_database_user"
export PG_PASSWORD="your_secure_password"

# Nginx server_name（域名/公网IP，用于虚拟主机匹配）
export NGINX_SERVER_NAME="your-domain.com"

# 管理令牌
export ADMIN_TOKEN="$(openssl rand -hex 32)"
```

### 快速自定义示例

```bash
# 自定义前端部署路径
export VUE_DIST_DIR="/usr/share/nginx/html/analyticshub-frontend/dist"

# 自定义应用名称和端口
export APP_NAME="my-analytics"
export APP_PORT="8080"

# 执行部署
bash docs/运维/init-env.sh
```

### 使用场景

1. 全新部署
```bash
# 使用默认配置部署
bash docs/运维/init-env.sh
```

2. 自定义部署
```bash
# 自定义配置部署
export VUE_DIST_DIR="/opt/my-app/frontend"
export APP_PORT="3002"
export ADMIN_TOKEN="$(openssl rand -hex 32)"

bash docs/运维/init-env.sh
```

3. 已有环境更新
```bash
# 脚本会自动检测并跳过已安装的组件
bash docs/运维/init-env.sh
```

### 目录结构说明

默认路径
```
/opt/analyticshub/          # 后端应用目录
/usr/share/nginx/html/analyticshub-frontend/dist/  # 前端静态文件目录
/var/log/analyticshub/      # 应用日志目录
/etc/analyticshub/          # 应用配置目录
```

自定义路径示例
```
/opt/my-app/                # 自定义后端目录
/usr/share/nginx/html/my-app/  # 自定义前端目录
/var/log/my-app/            # 自定义日志目录
```

### 安全建议

1. 密码生成
```bash
# 生成随机数据库密码
openssl rand -hex 3 | tr -d '=+/ ' | cut -c1-24

# 生成管理令牌
openssl rand -hex 32
```

2. 文件权限
```bash
# 保护配置文件
chmod 600 .env
chown root:root .env
```

3. 网络安全
```bash
# 仅开放必要端口
sudo firewall-cmd --add-service=http --permanent
sudo firewall-cmd --add-service=https --permanent
sudo firewall-cmd --reload
```

### 自动化部署步骤

1. 准备环境变量
```bash
# 示例：按需自定义
export APP_PORT="3001"
export NGINX_PORT="3000"
export VUE_DIST_DIR="/usr/share/nginx/html/analyticshub-frontend/dist"
export ADMIN_TOKEN="$(openssl rand -hex 32)"
```

2. 执行部署
```bash
# 执行部署脚本
bash docs/运维/init-env.sh

# 或者带自定义配置
export VUE_DIST_DIR="/your/custom/path"
bash docs/运维/init-env.sh
```

3. 部署前端
```bash
# 将 Vue 构建的 dist 目录内容复制到指定路径
cp -r dist/* "${VUE_DIST_DIR}/"

# 设置权限
chown -R nginx:nginx "${VUE_DIST_DIR}"
chmod -R 755 "${VUE_DIST_DIR}"
```

4. 启动服务
```bash
# 启动后端服务
sudo systemctl start analyticshub
sudo systemctl enable analyticshub

# 重新加载 Nginx 配置（优先用 reload）
sudo nginx -t
sudo systemctl reload nginx
```

## 部署 JAR 包

适用于环境已经配置好的情况，不依赖自动化脚本。

### 1. 打包

在本地执行：

```bash
mvn clean package -DskipTests
```

产物：`target/analyticshub-0.0.1-SNAPSHOT.jar`

### 2. 上传 JAR 到服务器

示例（按实际路径替换）：

```bash
scp target/analyticshub-0.0.1-SNAPSHOT.jar user@server:/opt/analyticshub/app.jar
```

### 3. 配置环境变量

在服务器上创建环境文件，例如 `/etc/analyticshub/analyticshub.env`：

```
DB_HOST=your_db_host
DB_PORT=5432
DB_NAME=your_db_name
DB_USER=your_db_user
DB_PASSWORD=your_db_password
ADMIN_TOKEN=your_admin_token
SPRING_PROFILES_ACTIVE=prod
SERVER_PORT=3001
LOG_PATH=/var/log/analyticshub
LOG_FILE=analyticshub
```

### 4. 启动服务

```bash
java -jar /opt/analyticshub/app.jar
```

后台启动（常用）

```bash
set -a
source /etc/analyticshub/analyticshub.env
set +a
nohup java -jar /opt/analyticshub/app.jar
```

说明：应用已配置 Logback 文件滚动日志，默认写入 `LOG_PATH/LOG_FILE.log`。

### 5. 验证

```bash
curl http://localhost:3001/api/health
```

### 常见查看命令

- 查看进程：
```bash
pgrep -af analyticshub
```

- 查看端口占用：
```bash
ss -tlnp | rg 3001
```

- 查看 Logback 文件日志：
```bash
tail -f /var/log/analyticshub/analyticshub.log
```

- 查看 JAR 内容（确认打包是否包含资源）：
```bash
jar tf /opt/analyticshub/app.jar | head
```

### 关于 `docs/运维/init-env.sh`

`init-env.sh` 只负责**初始化服务器环境**（安装依赖、创建目录、生成 systemd、Nginx 配置等），**不参与 JAR 打包**，也**不会替你上传 JAR**。

## 部署检查命令

### 服务状态
```bash
sudo systemctl status analyticshub
sudo systemctl status nginx
sudo systemctl status postgresql-15
```

### Nginx 常用指令
```bash
sudo nginx -t
sudo systemctl reload nginx
sudo systemctl restart nginx

tail -f /var/log/nginx/error.log
tail -f /var/log/nginx/access.log
```

### 端口与防火墙
```bash
sudo ss -tlnp | grep -E "(3000|3001|5432)"
sudo firewall-cmd --list-ports
sudo firewall-cmd --list-all
```

### 后端与数据库连通性
```bash
curl -s http://127.0.0.1:3001/api/health
sudo -u postgres psql -c "\\l" | grep analytics
sudo -u postgres psql -c "\\du" | grep analytic
```

## 凭据与配置位置

```bash
/root/analyticshub-credentials.txt       # 部署脚本生成（仅 root 可读）
/etc/analyticshub/analyticshub.env       # 后端运行环境变量（仅 root 可读）
/opt/analyticshub/                       # 后端目录
/usr/share/nginx/html/analyticshub-frontend/dist/  # 前端 dist 部署目录
```

### Admin Token 校验接口
```bash
curl -i -X POST http://127.0.0.1:3001/api/v1/auth/admin-token/verify -H "X-Admin-Token: <你的token>"
```

## 故障排除

### 常见问题

1. 权限问题
```bash
sudo chown -R nginx:nginx "${VUE_DIST_DIR}"
sudo chmod -R 755 "${VUE_DIST_DIR}"
```

2. 端口冲突
```bash
# 检查端口占用
sudo netstat -tlnp | grep :3001

# 修改应用端口
export APP_PORT="3002"
```

3. 服务启动失败
```bash
# 查看日志
sudo journalctl -u analyticshub -f
```

## 支持

如有部署问题，请检查：
- 环境变量配置是否正确
- 端口是否被占用
- 文件权限是否足够
- 防火墙设置是否正确

查看详细日志：
```bash
sudo journalctl -u analyticshub -f
sudo tail -f /var/log/nginx/error.log
```
