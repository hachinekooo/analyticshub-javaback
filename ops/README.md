# AnalyticsHub Ops Layout

这套运维脚本按职责分层，适配“一台服务器跑 prod/test 两套后端槽位”的部署方式。
脚本目标是：服务器重置、误删、服务起不来时，能按同一套口径快速重建。

核心口径：

- `ops/server/`：服务器基础环境，只维护一份。负责 Java、Nginx、firewalld、PostgreSQL、Redis、swap、journald 等共享依赖。
- `ops/apps/<app>/`：单个后端项目的运行目录、env、systemd、健康检查。通过 `DEPLOY_ENV=prod|test` 创建环境槽位。
- 不在 app 脚本里安装/重配整台服务器，避免多个项目互相覆盖主机配置。
- 不默认开机自启 4 个 Java 服务，避免 2C/2GiB 小机器重启后同时拉满导致 SSH 进不去。

```text
ops/server/                         # 服务器基础环境，项目无关
ops/apps/analyticshub/              # AnalyticsHub app 槽位脚本
ops/apps/demo_project/                   # Demo App/Inks app 槽位脚本
```

## 当前线上口径

| 环境 | systemd | 数据库 | 数据库用户 | Schema | 端口 | Nginx 前缀 |
|---|---|---|---|---|---:|---|
| prod | `demo_project-prod.service` | `demo_project_prod` | `demo_project_prod` | `app` | `8080` | `/prod/demo_project/` |
| test | `demo_project-test.service` | `demo_project_test` | `demo_project_test` | `app` | `18080` | `/test/demo_project/` |
| prod | `analyticshub-prod.service` | `analytics_prod` | `analytic_prod` | `analytics` | `3001` | `/prod/analyticshub/` |
| test | `analyticshub-test.service` | `analytics_test` | `analytic_test` | `analytics` | `13001` | `/test/analyticshub/` |

## 重建顺序

```bash
# 1. 基础包、Nginx、firewalld 等主机依赖。
sudo bash ops/server/init-env.sh

# 2. 本机数据库、Redis、swap 和日志上限。
sudo bash ops/server/setup-postgresql.sh
sudo bash ops/server/setup-redis.sh
sudo bash ops/server/setup-swap.sh
sudo bash ops/server/setup-journald-limits.sh
sudo bash ops/server/check-env.sh

# 3. 可选：检查旧口径遗留文件；确认后再 APPLY=true 备份移走。
sudo bash ops/server/cleanup-obsolete-state.sh

# 4. 数据库骨架。密码不写入 Git：可以通过环境变量传入；不传则自动生成到 root-only 文件。
sudo bash ops/server/create-postgres-databases.sh

# 5. 证书和 Nginx 路由。默认写入 /etc/nginx/conf.d/analyticshub-backends.conf。
sudo bash ops/server/setup-certbot.sh
sudo bash ops/server/install-nginx-routes.sh

# 6. 创建四个 app 槽位。
sudo -E env DEPLOY_ENV=prod bash ops/apps/demo_project/setup-app.sh
sudo -E env DEPLOY_ENV=test bash ops/apps/demo_project/setup-app.sh
sudo -E env DEPLOY_ENV=prod bash ops/apps/analyticshub/setup-app.sh
sudo -E env DEPLOY_ENV=test bash ops/apps/analyticshub/setup-app.sh

# 7. 上传 jar，编辑 /etc/<service>/<service>.env 后再启动。
sudo systemctl restart demo_project-test analyticshub-test
```

## 需要人工确认的外部配置

这些配置不适合直接写进仓库脚本，但重建服务器时必须确认：

- DNS：`analytics.example.com` 必须解析到当前 ECS 公网 IP。
- HTTPS 证书：`setup-certbot.sh` 负责安装 certbot 和检查证书；全新机器签发证书时需要 DNS 已解析，并显式传 `ISSUE_CERT=true CERTBOT_EMAIL=...`。
- 阿里云告警：内存、磁盘 IO、联系人和通知渠道属于云控制台配置，脚本不保存 AccessKey，也不自动修改云账号资源。
- 真实密钥：数据库密码、Apple 登录/内购密钥、SMTP 密码、2FA secret 只写入服务器 root-only env，不提交到 Git。
- 前端 dist：Nginx root 默认为 `/usr/share/nginx/html/inks-office-web/dist`，前端产物需要单独上传或部署。

## 目录约定

```text
/opt/<service>/app.jar                # JAR
/etc/<service>/<service>.env          # root-only env
/var/log/<service>/                   # 日志目录
/data/<service>/storage/              # Demo App 本地存储
```

## 健康检查

```bash
sudo -E env DEPLOY_ENV=prod bash ops/apps/demo_project/check-app.sh
sudo -E env DEPLOY_ENV=test bash ops/apps/demo_project/check-app.sh
sudo -E env DEPLOY_ENV=prod bash ops/apps/analyticshub/check-app.sh
sudo -E env DEPLOY_ENV=test bash ops/apps/analyticshub/check-app.sh
sudo bash ops/server/check-public-routes.sh
```

公网健康检查：

```text
https://analytics.example.com/prod/demo_project/actuator/health
https://analytics.example.com/test/demo_project/actuator/health
https://analytics.example.com/prod/analyticshub/api/health
https://analytics.example.com/test/analyticshub/api/health
```

`docs/运维/init-env.sh` 是旧的一体化脚本，只适合回看历史部署方式；新部署使用 `ops/`。
