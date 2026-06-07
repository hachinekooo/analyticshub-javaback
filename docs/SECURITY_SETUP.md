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
- 禁止通过 URL query 传递 Admin Token。

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
