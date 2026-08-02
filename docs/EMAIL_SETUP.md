---
title: 邮件与工单通知配置
type: configuration
status: current
audience: operator, backend
scope: 安全告警、隐私工单提醒和用户通知的 SMTP 配置与验证
agent_notes: 只覆盖邮件配置；生产部署流程见 docs/运维/DEPLOYMENT_GUIDE.md
---

# 邮件与工单通知配置

AnalyticsHub 使用同一套 Spring Mail 配置处理三类邮件：安全告警、隐私工单提交提醒，以及 transactional outbox（事务发件箱）中的用户通知。

邮件能力是可选项。不配置时，安全告警只写日志，工单用户通知保留在 outbox 中等待后续投递，不会被当作已发送。

## 配置项

生产环境建议写入 `/etc/analyticshub/analyticshub.env`：

```env
MAIL_ENABLED=true
MAIL_HOST=smtp.example.com
MAIL_PORT=465
MAIL_USERNAME=notify@example.com
MAIL_PASSWORD=replace-with-smtp-password
MAIL_CONNECTION_TIMEOUT_MS=10000
MAIL_READ_TIMEOUT_MS=10000
MAIL_WRITE_TIMEOUT_MS=10000
ALERT_EMAIL=admin@example.com
```

说明：

- `MAIL_ENABLED`：是否启用邮件发送。
- `MAIL_HOST` / `MAIL_PORT`：SMTP 服务地址和端口。
- `MAIL_USERNAME` / `MAIL_PASSWORD`：SMTP 账号和密码。
- `MAIL_CONNECTION_TIMEOUT_MS` / `MAIL_READ_TIMEOUT_MS` / `MAIL_WRITE_TIMEOUT_MS`：连接、读取和写入的超时毫秒数，默认均为 10000。
- `ALERT_EMAIL`：安全告警接收地址。

三个 SMTP timeout 应保持为正整数，并明显小于 `WORK_ORDER_OUTBOX_CLAIM_TIMEOUT_SECONDS`。默认每项 10 秒，outbox claim 默认 300 秒，可避免邮件网络阻塞接近任务回收窗口。

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

3. 触发管理端 Token 失败保护后，确认 `ALERT_EMAIL` 收到安全告警。
4. 创建测试隐私工单，确认管理员收到建单提醒。
5. 从后台发送测试通知，确认 outbox 最终产生 `NOTIFICATION_SENT` 活动；接口返回 `QUEUED` 本身不代表已送达。

## 安全要求

- 不要把真实 SMTP 密码提交到仓库。
- 不要在日志、文档或 issue 中暴露 `MAIL_PASSWORD`。
- 轮换 SMTP 密码后，同步更新服务器 env 并重启 `analyticshub`。
