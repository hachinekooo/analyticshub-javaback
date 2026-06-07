---
title: 告警邮件 SMTP 配置
type: configuration
status: current
audience: operator, backend
scope: 安全告警邮件的 SMTP 环境变量、TLS 端口和验证方式
agent_notes: 只覆盖邮件配置；生产部署流程见 docs/运维/DEPLOYMENT_GUIDE.md
---

# 告警邮件 SMTP 配置

AnalyticsHub 使用 Spring Mail 发送安全告警邮件。邮件能力是可选项；不配置时，安全告警只会写入服务日志。

## 配置项

生产环境建议写入 `/etc/analyticshub/analyticshub.env`：

```env
MAIL_ENABLED=true
MAIL_HOST=smtp.example.com
MAIL_PORT=465
MAIL_USERNAME=notify@example.com
MAIL_PASSWORD=replace-with-smtp-password
ALERT_EMAIL=admin@example.com
```

说明：

- `MAIL_ENABLED`：是否启用邮件发送。
- `MAIL_HOST` / `MAIL_PORT`：SMTP 服务地址和端口。
- `MAIL_USERNAME` / `MAIL_PASSWORD`：SMTP 账号和密码。
- `ALERT_EMAIL`：安全告警接收地址。

## 端口与加密

当前 `application.yml` 默认启用 SMTP auth、STARTTLS 和 SSL。常见配置：

- SSL SMTP：通常使用 `465`。
- STARTTLS SMTP：通常使用 `587`。

如果服务商要求不同 TLS 策略，请按实际服务商要求调整 `spring.mail.properties.mail.smtp.*`。

## 验证

1. 写入环境变量并重启服务。
2. 执行生产检查：

```bash
sudo bash ops/analyticshub check
```

3. 触发管理端 Token 失败保护后，确认日志中没有 SMTP 认证或连接错误，并确认 `ALERT_EMAIL` 收到邮件。

## 安全要求

- 不要把真实 SMTP 密码提交到仓库。
- 不要在日志、文档或 issue 中暴露 `MAIL_PASSWORD`。
- 轮换 SMTP 密码后，同步更新服务器 env 并重启 `analyticshub`。
