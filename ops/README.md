---
title: AnalyticsHub 运维脚本
type: operations-index
status: current
audience: operator, agent
scope: ops 目录布局、统一入口、部署口径、健康检查和维护命令
agent_notes: 只维护 AnalyticsHub 自身部署；不要加入接入方业务项目脚本
---

# AnalyticsHub 运维脚本

这套运维脚本只维护 AnalyticsHub 自身部署口径。它不维护接入方业务项目的 systemd、数据库、Nginx 路由或密码轮换；接入方项目应在各自仓库或服务器本地 infra 目录中维护自己的运维脚本。

核心口径：

- `ops/server/`：AnalyticsHub 所需的主机基础环境，包括 Java、Nginx、firewalld、PostgreSQL、swap、journald、certbot、`/analyticshub/` 路由和定期维护脚本。
- `ops/apps/analyticshub/`：AnalyticsHub 单实例运行目录、env、systemd 和健康检查。
- 不在 app 脚本里安装/重配整台服务器，避免多个项目互相覆盖主机配置。
- 不默认开机自启 Java 服务，避免 2C/2GiB 小机器重启后启动压力过高。
- systemd 资源限制只使用当前 Alibaba Cloud Linux 3 支持的 `MemoryHigh`/`MemoryMax`；不写 `OOMPolicy`，因为 systemd 239 会把它报告为 unknown lvalue。

```text
ops/analyticshub                    # 统一运维入口
ops/server/                         # 服务器基础环境，项目无关
ops/apps/analyticshub/              # AnalyticsHub 单实例脚本
```

## 统一入口

推荐从统一入口执行常用流程：

```bash
# 主机基础环境、PostgreSQL、swap、journald、AnalyticsHub 数据库。
sudo bash ops/analyticshub bootstrap

# 证书和 /analyticshub/ Nginx 路由片段。
sudo -E env DOMAIN=analytics.example.com CERTBOT_EMAIL=admin@example.com ISSUE_CERT=true bash ops/analyticshub web

# AnalyticsHub app 运行层。
sudo bash ops/analyticshub deploy

# 本地检查。
sudo bash ops/analyticshub check

# 公网检查。
sudo -E env BASE_URL=https://analytics.example.com bash ops/analyticshub check-public

# 定期轮换密钥。
sudo bash ops/analyticshub rotate-secrets

# 备份 AnalyticsHub 数据库。
sudo bash ops/analyticshub backup-db
```

底层脚本仍可单独执行，便于排障和局部重跑。

## 当前口径

| 环境 | systemd | 数据库 | 数据库用户 | Schema | 端口 | Nginx 前缀 |
|---|---|---|---|---|---:|---|
| analytics | `analyticshub.service` | `analytics` | `analytic` | `analytics` | `3001` | `/analyticshub/` |

`3001` 是 AnalyticsHub 单实例入口，由 Nginx 的 `/analyticshub/` 路径访问；不要再拆 `/prod/analyticshub/`、`/test/analyticshub/`。

## 重建顺序

```bash
# 1. 基础包、Nginx、firewalld 等主机依赖。
sudo bash ops/analyticshub bootstrap

# 2. 证书和 Nginx /analyticshub/ 页面/API 路由片段。
sudo -E env DOMAIN=analytics.example.com CERTBOT_EMAIL=admin@example.com ISSUE_CERT=true bash ops/analyticshub web

# 3. 创建 AnalyticsHub app 运行层。
sudo bash ops/analyticshub deploy

# 4. 上传 jar，编辑 /etc/analyticshub/analyticshub.env 后再启动。
sudo systemctl restart analyticshub
```

## 外部前置条件

这些配置不适合直接写进仓库脚本，但重建服务器时必须确认：

- DNS：你的域名必须解析到当前服务器公网 IP。
- HTTPS 证书：`setup-certbot.sh` 可安装 certbot；首次签发需要 DNS 已解析，并显式传 `ISSUE_CERT=true CERTBOT_EMAIL=...`。
- PostgreSQL：`setup-postgresql.sh` 安装本机 PostgreSQL；`create-analytics-database.sh` 创建 `analytics` 数据库、`analytic` 用户和 `analytics` schema。
- Nginx：`install-nginx-routes.sh` 写入 `/etc/nginx/conf.d/analyticshub.conf`。该文件只包含 `location` 片段，不会创建完整站点；必须先由目标域名的 HTTPS `server` 块显式 include。不要在 `http` 级别保留 `include /etc/nginx/conf.d/*.conf;`。脚本会在写入前检查这两个条件，避免留下会破坏 `nginx -t` 的半配置。
- Nginx 路由：`/analyticshub/` 页面请求指向前端 dist，所有 API 统一走 `/analyticshub/api/`：
  - `/analyticshub/api/health` -> `127.0.0.1:3001/api/health`
  - `/analyticshub/api/v1/` -> `127.0.0.1:3001/api/v1/`
  - `/analyticshub/api/admin/` -> `127.0.0.1:3001/api/admin/`
  - `/analyticshub/api/public/` -> `127.0.0.1:3001/api/public/`
- 真实密钥：数据库密码、SMTP 密码、管理端 token、2FA secret 只写入服务器 root-only env，不提交到 Git。
- 前端 dist：当前推荐路径为 `/usr/share/nginx/html/analyticshub-frontend/dist`，前端产物需要单独上传或部署。

## 目录约定

```text
/opt/<service>/app.jar                # JAR
/etc/<service>/<service>.env          # root-only env
/var/log/<service>/                   # 日志目录
```

## 健康检查

```bash
sudo bash ops/analyticshub check
sudo -E env BASE_URL=https://analytics.example.com bash ops/analyticshub check-public
```

公网健康检查：

```text
https://analytics.example.com/analyticshub/api/health
```

## 定期维护

备份 AnalyticsHub 数据库：

```bash
sudo bash ops/analyticshub backup-db
```

默认写入 `/var/backups/analyticshub`，保留 14 天。可通过环境变量覆盖：

```bash
sudo -E env BACKUP_DIR=/secure/backups/analyticshub RETENTION_DAYS=30 bash ops/analyticshub backup-db
```

轮换 AnalyticsHub 自身数据库密码和管理端 token：

```bash
sudo bash ops/analyticshub rotate-secrets
```

默认行为：

- 生成新的 `analytic` 数据库密码，并更新 PostgreSQL role。
- 更新 `/etc/analyticshub/analyticshub.env`。
- 更新 `/root/analyticshub-db-credentials.txt`，权限保持 `root:root 600`。
- 生成新的 `ADMIN_TOKEN`。
- 重启 `analyticshub` 并检查本地 `/api/health`。

可选参数：

```bash
# 只更新文件和数据库，不重启服务。
sudo -E env RESTART_SERVICE=false bash ops/analyticshub rotate-secrets

# 同时轮换 2FA secret。执行后需要重新绑定认证器。
sudo -E env ROTATE_2FA_SECRET=true bash ops/analyticshub rotate-secrets

# 只轮换 admin token，不改数据库密码。
sudo -E env ROTATE_DB_PASSWORD=false bash ops/analyticshub rotate-secrets
```
