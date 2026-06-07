---
title: AnalyticsHub 部署指南
type: operations-guide
status: current
audience: operator
scope: Linux 服务器部署、Nginx 路由、systemd、前端 dist、检查和定期维护
agent_notes: 只维护 AnalyticsHub 自身部署；不要加入接入方业务项目脚本
---

# AnalyticsHub 部署指南

本文只说明如何把 AnalyticsHub 后端部署到一台 Linux 服务器。运维脚本的统一入口是：

```bash
sudo bash ops/analyticshub <command>
```

完整脚本清单和参数见 [ops/README.md](../../ops/README.md)。

## 部署边界

本仓库只维护 AnalyticsHub 自身部署：

- AnalyticsHub 后端 systemd、env、日志目录和健康检查。
- AnalyticsHub 所需的 PostgreSQL 数据库、用户和 schema。
- `/analyticshub/` 页面路径和 API 反代。
- AnalyticsHub 自身的备份和密钥轮换。

接入方业务项目的 systemd、数据库、Nginx 路由和密码轮换应由业务项目仓库或服务器本地 infra 目录维护。

## 推荐流程

### 1. 准备服务器

服务器需要能通过 SSH 登录，并且域名已经解析到服务器公网 IP。首次签发证书前，80/443 端口需要能从公网访问。

### 2. 初始化基础环境

```bash
sudo bash ops/analyticshub bootstrap
```

这个命令会准备基础包、Java、Nginx、firewalld、PostgreSQL、swap、journald，以及 AnalyticsHub 的 `analytics` 数据库、`analytic` 用户和 `analytics` schema。

### 3. 配置证书和 Nginx

```bash
sudo -E env DOMAIN=analytics.example.com CERTBOT_EMAIL=admin@example.com ISSUE_CERT=true bash ops/analyticshub web
```

如果证书已经存在，可以不传 `ISSUE_CERT=true`。Nginx 会把：

```text
/analyticshub/         -> 前端 dist
/analyticshub/api/     -> 127.0.0.1:3001/api/
/analyticshub/v1/      -> 127.0.0.1:3001/api/v1/
/analyticshub/admin/   -> 127.0.0.1:3001/api/admin/
/analyticshub/public/  -> 127.0.0.1:3001/api/public/
```

### 4. 创建应用运行层

```bash
sudo bash ops/analyticshub deploy
```

该命令会创建：

```text
/opt/analyticshub/app.jar
/etc/analyticshub/analyticshub.env
/var/log/analyticshub/
analyticshub.service
```

如果 env 文件已存在，脚本会保留原文件，不覆盖真实密钥。

### 5. 上传后端 JAR

本地构建：

```bash
mvn clean package -DskipTests
```

上传：

```bash
scp target/analyticshub-0.0.1-SNAPSHOT.jar user@server:/opt/analyticshub/app.jar
```

服务器上修正权限：

```bash
sudo chown analyticshub:analyticshub /opt/analyticshub/app.jar
sudo chmod 640 /opt/analyticshub/app.jar
```

### 6. 编辑生产环境变量

编辑：

```bash
sudo vim /etc/analyticshub/analyticshub.env
```

至少确认：

```env
SPRING_PROFILES_ACTIVE=prod
SERVER_PORT=3001
DB_HOST=127.0.0.1
DB_PORT=5432
DB_NAME=analytics
DB_SCHEMA=analytics
DB_USER=analytic
DB_PASSWORD=replace-with-real-password
ADMIN_TOKEN=replace-with-at-least-32-random-characters
```

需要邮件或 2FA 时，再配置：

```env
MAIL_ENABLED=true
MAIL_HOST=smtpdm.aliyun.com
MAIL_USERNAME=notify@example.com
MAIL_PASSWORD=replace-with-smtp-password
ALERT_EMAIL=admin@example.com

APP_SECURITY_2FA_ENABLED=true
APP_SECURITY_2FA_SECRET=replace-with-totp-secret
```

### 7. 部署前端 dist

默认前端路径：

```text
/usr/share/nginx/html/analyticshub-frontend/dist
```

把前端构建产物上传到该目录，并确保 Nginx 可读：

```bash
sudo chown -R nginx:nginx /usr/share/nginx/html/analyticshub-frontend/dist
sudo chmod -R 755 /usr/share/nginx/html/analyticshub-frontend/dist
```

### 8. 启动和检查

```bash
sudo systemctl restart analyticshub
sudo bash ops/analyticshub check
sudo -E env BASE_URL=https://analytics.example.com bash ops/analyticshub check-public
```

手动检查：

```bash
curl -fsS http://127.0.0.1:3001/api/health
curl -fsS https://analytics.example.com/analyticshub/api/health
```

## 接入项目数据库

管理端创建项目不会自动创建数据库或用户。为某个接入项目配置 `dbName/dbSchema/dbUser/dbPassword` 前，需要先创建对应数据库和账号：

```sql
CREATE ROLE your_project_user LOGIN PASSWORD 'replace-with-project-password';
CREATE DATABASE your_project OWNER your_project_user;
\c your_project
CREATE SCHEMA IF NOT EXISTS analytics AUTHORIZATION your_project_user;
GRANT USAGE, CREATE ON SCHEMA analytics TO your_project_user;
```

## 定期维护

备份 AnalyticsHub 数据库：

```bash
sudo bash ops/analyticshub backup-db
```

轮换 AnalyticsHub 数据库密码和管理端 token：

```bash
sudo bash ops/analyticshub rotate-secrets
```

常用选项：

```bash
sudo -E env RESTART_SERVICE=false bash ops/analyticshub rotate-secrets
sudo -E env ROTATE_2FA_SECRET=true bash ops/analyticshub rotate-secrets
sudo -E env BACKUP_DIR=/secure/backups/analyticshub RETENTION_DAYS=30 bash ops/analyticshub backup-db
```

## 排障

查看服务：

```bash
sudo systemctl status analyticshub
sudo journalctl -u analyticshub -f
```

检查 Nginx：

```bash
sudo nginx -t
sudo systemctl reload nginx
sudo tail -f /var/log/nginx/error.log
```

检查端口：

```bash
sudo ss -tlnp | grep -E '(3001|5432|80|443)'
```

校验管理端 token：

```bash
curl -i -X POST http://127.0.0.1:3001/api/v1/auth/admin-token/verify \
  -H "X-Admin-Token: <your-admin-token>"
```
