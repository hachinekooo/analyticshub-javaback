---
title: AnalyticsHub 安全配置
type: security-guide
status: current
audience: operator, backend, agent
scope: 敏感信息边界、认证口径、生产检查和密钥轮换
agent_notes: 涉及 token、2FA、邮件告警或密钥轮换时阅读
---

# AnalyticsHub 安全配置指南

本文档只记录当前项目仍在使用的安全配置入口。生产部署请以 `ops/README.md` 和 `docs/运维/DEPLOYMENT_GUIDE.md` 为准。

## 敏感信息边界

不要提交真实的：

- 数据库密码
- Admin Token
- API Secret
- TOTP / 2FA Secret
- SMTP 密码
- 生产域名、服务器 IP、个人路径

本地开发可从 `.env.dev.example` 派生自己的 `.env.dev`。生产环境不要使用仓库根目录 `.env`，应使用 ops 脚本创建的 root-only 配置文件：

```text
/etc/analyticshub/analyticshub.env
```

## 当前关键配置

后端从环境变量读取敏感配置：

```bash
DB_HOST=127.0.0.1
DB_PORT=5432
DB_NAME=analytics
DB_SCHEMA=analytics
DB_USER=analytic
DB_PASSWORD=replace-with-database-password
ADMIN_TOKEN=replace-with-at-least-32-random-characters
```

可选安全能力：

```bash
MAIL_ENABLED=true
MAIL_HOST=smtpdm.aliyun.com
MAIL_PORT=465
MAIL_USERNAME=notify@example.com
MAIL_PASSWORD=replace-with-smtp-password
ALERT_EMAIL=admin@example.com

APP_SECURITY_2FA_ENABLED=true
APP_SECURITY_2FA_SECRET=replace-with-totp-secret
```

开启 2FA 时 secret 必须是 16–128 个字符的无 padding Base32 值（字母与 `2-7`，大小写均可），否则应用会在启动阶段 fail-fast（拒绝启动），部署前检查也会失败。

`ADMIN_TOKEN` 可以留空以禁用管理端；一旦配置，则不允许首尾空白且长度必须至少 32 个字符，否则应用会在启动阶段拒绝启动。

相关 `application.yml` 路径：

- `spring.datasource.*`
- `spring.flyway.*`
- `spring.mail.*`
- `app.security.admin-token`
- `app.email.alert-recipient`

## 认证口径

- `/api/health` 是公开健康检查接口。
- `/api/admin/**` 需要 `X-Admin-Token` 或 `Authorization: Bearer <token>`。
- `POST /api/v1/auth/admin-token/verify` 用于管理端 Token 有效性探测。
- 采集端 `/api/v1/**` 按具体接口使用 API Key + HMAC。
- `POST /internal/v1/analytics/actor-links` 只接受专用 service HMAC；凭据同时限制调用方与 Project，不能复用 Admin Token、设备 Secret 或用户 access token。
- 禁止通过 URL query 传递 Admin Token。

### Actor link 服务凭据

一个 AnalyticsHub 实例可以管理多个 Project，也可以接收多个业务后端实例的 actor 绑定。当前契约要求一个 `serviceId` 只写入一个 Project，且一个 Project 只接受一个 actor-link service client；例如 test 与 prod 两个后端即使部署在同一台服务器、调用同一个 Hub origin，也必须配置两组不同凭据。启动校验会拒绝重复的 `serviceId`、`projectId` 或 secret，防止复制配置后意外跨环境写入。

```bash
ACTOR_LINK_ENABLED=true
ACTOR_LINK_REQUIRE_LOOPBACK=true

APP_SECURITY_ACTOR_LINK_CLIENTS_0_SERVICE_ID=backend-test
APP_SECURITY_ACTOR_LINK_CLIENTS_0_PROJECT_ID=project-test
APP_SECURITY_ACTOR_LINK_CLIENTS_0_SECRET=replace-with-test-random-secret-at-least-32-chars

APP_SECURITY_ACTOR_LINK_CLIENTS_1_SERVICE_ID=backend-prod
APP_SECURITY_ACTOR_LINK_CLIENTS_1_PROJECT_ID=project-prod
APP_SECURITY_ACTOR_LINK_CLIENTS_1_SECRET=replace-with-prod-random-secret-at-least-32-chars
```

同机部署优先让业务后端通过 `127.0.0.1` 访问，并在反向代理层拒绝公网 internal route。`require-loopback` 是纵深防御，service HMAC 才是身份与项目授权；未来拆到另一台服务器时，可关闭 loopback 限制并改用私网、防火墙或 mTLS，但不能取消 HMAC。secret 不允许首尾空白且至少 32 个字符，真实值只进入部署 secret。

## 生产检查

```bash
sudo bash ops/analyticshub check
sudo -E env BASE_URL=https://analytics.example.com bash ops/analyticshub check-public
```

## 密钥轮换

优先使用仓库内维护的运维入口：

```bash
sudo bash ops/analyticshub rotate-secrets
```

可选：

```bash
sudo -E env ROTATE_2FA_SECRET=true bash ops/analyticshub rotate-secrets
sudo -E env ROTATE_DB_PASSWORD=false bash ops/analyticshub rotate-secrets
```

如果真实敏感信息曾进入 Git 历史，先立即轮换所有相关密钥，再按仓库维护者确认的历史清理流程处理。
