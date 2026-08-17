---
title: AnalyticsHub 1.1.0 升级说明
type: release-guide
status: current
audience: maintainer, operator
scope: 1.0.1 到 1.1.0 的身份归并、运营指标、项目迁移、配置和发布顺序
agent_notes: 发布或升级 1.1.0 前阅读；真实凭据和私有项目名不得写入本文
---

# AnalyticsHub 1.1.0 升级说明

1.1.0 增加 anonymous actor → cloud actor 的可靠归并、按事件发生时间统计的活跃版本分布，以及对 actor closure（身份闭包）一致执行的隐私导出与去标识化。管理端前端 `1.1.0` 与本版本配套部署。

## 兼容边界

- 现有采集、设备凭据、管理端和公开 API 保持兼容；旧客户端无需发送 actor-link 请求。
- actor-link 是仅限受信服务调用的 internal API，不对客户端或公网开放。
- 单个 AnalyticsHub 实例可以服务多个 Project，但每个 actor-link client 只能绑定一个 Project；test/prod 的 Project、service ID、secret 必须分别唯一。
- 1.1.0 的项目迁移是 forward-only（只向前）；升级前必须备份 system database 和所有接入项目数据库。

## 数据库迁移

system database 仍为 V5，本轮没有新增 system migration。

每个 project database 从 V6 升级到 V7：

- `actor_identity_links`：保存一跳 source actor → canonical actor 关系；
- `actor_suppressions`：隐私删除完成后保存不可逆 actor 摘要，避免迟到绑定恢复已删除关系；
- 事件、漏斗、留存、活跃用户和版本分布通过同一 resolver 使用 canonical actor；
- 运营时间窗口使用客户端 `event_timestamp`，服务端 `created_at` 只保留为接收与补发诊断时间。

逐个 Project 调用 health/init 后，确认 `schemaVersion=7`、`pendingMigrations=0`、`migrationHistoryValid=true`。

## 新增服务凭据

actor-link 使用 service HMAC（服务间消息认证），不是用户密码、iOS Key 或 Admin Token。每个业务后端槽位生成一把独立 secret：

```bash
openssl rand -hex 32
```

该命令输出 64 个十六进制字符，提供 32 bytes 随机材料。分别生成 test 和 prod 两次，不要复制同一个值。service ID 是可读的稳定名称，不需要随机；Project ID 使用 AnalyticsHub 中已经隔离的对应 Project。

AnalyticsHub env 示例：

```env
ACTOR_LINK_ENABLED=true
ACTOR_LINK_REQUIRE_LOOPBACK=true
APP_SECURITY_ACTOR_LINK_CLIENTS_0_SERVICE_ID=replace-with-test-service-id
APP_SECURITY_ACTOR_LINK_CLIENTS_0_PROJECT_ID=replace-with-test-project-id
APP_SECURITY_ACTOR_LINK_CLIENTS_0_SECRET=replace-with-test-secret
APP_SECURITY_ACTOR_LINK_CLIENTS_1_SERVICE_ID=replace-with-prod-service-id
APP_SECURITY_ACTOR_LINK_CLIENTS_1_PROJECT_ID=replace-with-prod-project-id
APP_SECURITY_ACTOR_LINK_CLIENTS_1_SECRET=replace-with-prod-secret
```

同一槽位的 `service ID + Project ID + secret` 必须原样写入对应业务后端；secret 只存 root-only env，不进入 Git、日志、URL 或请求 body。iOS `ownershipProof` 由设备自动生成，不是部署 secret，也不能预先写入服务器。

## 推荐发布顺序

1. 备份 AnalyticsHub system database 和全部 Project database。
2. 准备 test/prod 两组 actor-link client 配置并运行部署检查。
3. 部署 AnalyticsHub backend `1.1.0`，逐 Project 完成 V7 migration 与 health 检查。
4. 部署配套 frontend `1.1.0`，确认活跃版本、设备明细和隐私工单页面可读。
5. 先升级业务后端 test 槽位，验证匿名事件 → 登录 → outbox → actor-link → canonical 报表。
6. 测试稳定后，把同一 checksum 的业务后端 artifact 提升到 prod，再放行依赖新登录响应合同的客户端。

## 最小验收

- 旧客户端仍能注册/轮换分析凭据并上报事件；
- test/prod 事件和 actor 关系只进入对应 Project；
- AnalyticsHub 暂停后，业务登录不失败，恢复后 outbox 最终投递；
- 同一用户登录前后的事件在活跃用户、漏斗、留存中只按 canonical actor 计算；
- 活跃版本按周期内每台设备最后发生事件携带的 `app_version + build_number` 统计；
- 隐私工单只能由管理流程执行，导出/删除覆盖已绑定 actor closure，且不会扩展到共享设备上的其他用户；
- HTTP body logging 即使临时启用，也必须脱敏 `ownershipProof`。

## 回滚边界

不要修改已应用的 V7 checksum。新表不会破坏 1.0.1 的旧查询，但 1.0.1 不理解身份归并与 suppression；发生故障时优先以前向修复恢复。需要回退应用时，应先停止 actor-link 写入，并依据升级前备份和实际 migration 状态制定回滚，不能只替换旧 JAR 后继续处理身份或隐私写操作。
