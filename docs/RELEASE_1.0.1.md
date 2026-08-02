---
title: AnalyticsHub 1.0.1 升级说明
type: release-guide
status: current
audience: maintainer, operator
scope: 1.0.0 到 1.0.1 的功能、数据库迁移、兼容性和回滚边界
agent_notes: 发布或升级 1.0.1 前读取；真实凭据和私有项目名不得写入本文
---

# AnalyticsHub 1.0.1 升级说明

1.0.1 完善多项目运营底座：项目库 Flyway、事件幂等、Counter 历史回算、语义字典、项目 Dashboard、可靠隐私工单、管理端安全与本地/生产配置校验。本仓库只提供通用能力，不包含任何下游私有项目的事件命名、业务指标或 Dashboard。

## Runtime baseline

- Spring Boot 从 4.0.1 更新到 4.0.7 maintenance release（维护版本），仍在 4.0 兼容线内。
- Flyway 固定为 12.11.0，使应用与 migration runtime 都使用 Jackson 3 namespace；本项目未使用 Flyway 12 移除的 Code Analysis、旧 plugin namespace 或 MongoDB JDBC 能力。
- Jackson 使用 3.1 LTS 的 3.1.5 security patch。应用、Spring MVC converter 和 Flyway 不再加载 Jackson 2 databind；`com.fasterxml.jackson.annotation` 仍是 Jackson 3 官方共享的 annotation artifact，不代表存在第二套 mapper。

升级组合已覆盖空 system schema 的 V1–V6、空 project schema 的 V1–V5，以及模拟 1.0.0 project V1 fingerprint 后继续执行 V2–V5。生产升级仍必须按本文的备份、单实例迁移和逐项目 health 流程执行。

## 数据库版本

system database：

- V3：project-scoped semantic definitions 与多对一 aliases。
- V4：project-scoped Dashboard definitions、default uniqueness 和 revision。
- V5：项目 `analysis_template`，历史项目默认迁移为 `app`。
- V6：把旧 Dashboard 的 `operations` / `technical` key 迁移为分析模板对应工作区和 `details`；保留已保存的 widget definition。

已经发布的 system V2 曾创建 `analytics_idempotency_keys`。1.0.1 不再读写这张 legacy table（遗留表）；事件幂等状态由每个 project database 的 V2 表负责。升级过程不会自动 `DROP` 该表，以免在没有独立备份和数据保留决策时执行破坏性清理。它的保留不代表业务采集数据仍会写入 system database。

历史部署如果违反隔离约定，让某个 project 指向 system database 的同一个 schema，并且使用会生成同名 `analytics_idempotency_keys` 的 `analytics_` 前缀，不要直接执行项目初始化。应先备份并把该项目迁移到独立 database/schema，或制定人工表迁移方案；否则 system V2 遗留表会与 project V2 的目标表发生名称冲突。正常的独立 project database/schema 不受影响。

每个 project database：

- V2：event idempotency，保存 request fingerprint，拒绝同 key 不同 payload。
- V3：Counter 历史回算 metadata 与查询索引。
- V4：工单 version、不可变 activity log、transactional notification outbox。
- V5：设备凭据轮换 grace window（宽限窗口），支持响应丢失后的安全幂等重试。

已投产的 V1（1.0.0）项目 schema 会先做严格 fingerprint 检查，再 baseline 到 V1 并只执行 V2–V5；不会删除或重建已有事件数据。部分缺表、列类型漂移、缺关键 constraint/index 或用 view 冒充 table 时会 fail closed（拒绝猜测修复）。

## 升级步骤

1. 停止 1.0.0 application。不要让 1.0.0 与 1.0.1 实例同时连接同一个 system database。
2. 备份 system database、每个接入项目数据库，以及当前 root-only application env 文件。
3. 生成并单独备份项目数据库凭据加密密钥：

   ```bash
   openssl rand -base64 32
   ```

   将输出写入 root-only env 的 `PROJECT_CREDENTIAL_ENCRYPTION_KEY`。它必须是 Base64 编码的 32 bytes；丢失后，已保存的项目数据库密码无法恢复。不要把密钥提交到仓库或与数据库备份放在同一位置。
4. 部署 1.0.1 application artifact。启动时 system Flyway 会先执行 V3–V6；随后 startup migration 会在一个 system database transaction 中校验所有项目凭据，并把 1.0.0 的 legacy Base64 值升级为带认证的 AES-256-GCM envelope。任一凭据或密钥错误都会 fail closed，且不会留下部分凭据升级状态。
5. 对每个项目调用 `GET /api/admin/projects/{id}/health`。
6. 对 `schemaCurrent=false` 且数据库可连接的项目调用 `POST /api/admin/projects/{id}/init`。
7. 再次检查 `schemaVersion=5`、`pendingMigrations=0`、`migrationHistoryValid=true`。
8. 验证采集、Counter 回算、语义目录、Dashboard 保存和工单状态更新。

本地开发库允许重建时，可以清空本地 system schema 后从 V1–V6、project schema 后从 V1–V5 全新创建；生产环境不要使用 Flyway clean。

## 客户端认证兼容性

公共设备注册默认不再覆盖已存在设备的 API key/secret，防止仅知道 device UUID 的调用方接管凭据。客户端必须持久化首次注册凭据；凭据轮换使用已认证的 HMAC rotate endpoint。

轮换后，旧凭据默认保留 600 秒的认证 grace window。在窗口内，旧凭据仍可认证所有 HMAC API，便于并发中的旧请求平滑完成；若客户端已提交轮换但响应丢失，还可用旧凭据重试 rotate endpoint，服务端会返回已生效的新凭据而不是再次轮换。窗口由 `CREDENTIAL_ROTATION_GRACE_SECONDS` 配置（1–86400 秒）；客户端成功持久化新凭据后应立即改用新凭据。

升级前必须检查现有 client/SDK 的错误处理：不要因为任意 HTTP 401 就删除本地凭据并重新调用公共注册。至少应按结构化 `error.code` 区分请求格式、凭据无效、项目不可用和服务暂时不可用；已存在设备再次注册会返回 HTTP 409 `DEVICE_ALREADY_REGISTERED`。若客户端已经丢失全部凭据，默认安全模式不会通过公共接口重新发放；管理员可调用 `POST /api/admin/projects/{projectId}/devices/{deviceId}/credentials/reset` 受控重置。该响应只返回一次新 secret，并立即撤销 reset 前的 current/previous 凭据。

`ALLOW_INSECURE_DEVICE_REREGISTRATION=true` 只用于短期兼容排障，它会恢复不安全的覆盖行为，生产环境不建议启用。

## Counter 与语义字典

Counter 的 `eventTrigger` 只引用稳定语义 Key，支持三种互斥形态：

- `semantic_key`：匹配一个语义 Key。
- `semantic_keys`：多个语义 Key 共享一组可选条件。
- `any_of`：1–100 个 typed clauses（类型化分支），每个分支包含一个 `semantic_key` 和可选 `conditions`。

实时投影与历史 rebuild 共用同一份 allow-listed rule model（白名单规则模型）。`conditions` 对嵌套深度、容器大小、字段名、字符串值和总节点数设有边界；非法或混合形态返回 `INVALID_COUNTER_EVENT_TRIGGER`。该能力沿用现有 `event_trigger JSONB`，无需新增或改写项目库迁移。

语义字典负责 raw event 到 semantic key 的 many-to-one mapping（多对一映射）。`app` / `webapp` 模板自动初始化四个 `core.*` 官方语义，新建自定义语义必须使用 `custom.*`。调整 aliases 后执行 rebuild，即可按新映射重算存量事件；事件事实不会被重写。完整 contract 见 [管理端 API](API_MANAGEMENT.md#9-运营累计统计counters配置与管理)。

## Dashboard 定制边界

1.0.1 的运行时 Dashboard 使用受校验的 declarative JSON（声明式配置），适合管理端拖拽布局和安全存储。更自由的项目专用页面或组件应在可信源码中开发、编译和部署，再通过公开 extension contract（扩展契约）接入；数据库中不执行任意 HTML/JavaScript。

定制 widget 需要后端 `DashboardWidgetExtension` Spring bean 与前端 `dashboardWidgetExtensions` 使用同一个 `custom.*` type，并成对部署。完整 props、config allow-list、刷新机制和兼容规则见 [Dashboard 与项目定制](DASHBOARD_CUSTOMIZATION.md)。独立定制页面仍使用下游静态 Vue route，不支持从数据库动态加载页面脚本。

## 工单通知

状态更新与通知入队在同一个项目数据库 transaction 中提交。响应 `QUEUED` 只代表入队；最终 `SENT / RETRY / DEAD` 记录在 outbox 和 immutable activities。

内置 scheduler 默认开启，但只有 `MAIL_ENABLED=true`、sender 与 JavaMailSender 都可用时才读取项目/outbox，避免邮件未配置时耗尽重试。可用环境变量：

- `WORK_ORDER_OUTBOX_SCHEDULER_ENABLED`
- `WORK_ORDER_OUTBOX_SCHEDULER_INITIAL_DELAY_MS`
- `WORK_ORDER_OUTBOX_SCHEDULER_FIXED_DELAY_MS`
- `WORK_ORDER_OUTBOX_SCHEDULER_BATCH_SIZE`
- `WORK_ORDER_OUTBOX_CLAIM_TIMEOUT_SECONDS`
- `WORK_ORDER_OUTBOX_RETRY_DELAY_SECONDS`
- `MAIL_CONNECTION_TIMEOUT_MS`
- `MAIL_READ_TIMEOUT_MS`
- `MAIL_WRITE_TIMEOUT_MS`

## 网络与公开采集

- `prod` profile 默认把应用绑定到 `127.0.0.1`，由 Nginx 对外提供服务；不要把 3001 直接绑定公网地址。
- 只有来自 `APP_SECURITY_TRUSTED_PROXIES` 的直接连接才会信任 `X-Forwarded-For`；部署脚本默认适配 loopback Nginx。修改代理拓扑时必须同步更新该 allow-list。
- 匿名 `/api/public/**`、设备注册与管理 Token 校验使用 `APP_RATE_LIMIT_*` 做单实例基础限流；`APP_RATE_LIMIT_WINDOW_MS` 最小为 1000 毫秒。多实例部署还应在共享 gateway/reverse proxy 配置统一限流。
- Spring Boot 的默认内存用户与随机开发密码已禁用；管理端与 Actuator 只使用 AnalyticsHub 显式配置的 Token 认证，生产环境不要额外开启默认 form login 或 HTTP Basic。
- API 与 Actuator 只接受 canonical path（规范路径）；包含 matrix parameter、percent encoding、重复斜杠、反斜杠或 `.` / `..` segment 的歧义路径会返回 `INVALID_REQUEST_PATH`，客户端不应依赖容器的隐式路径归一化。
- 网站流量同源接入可使用 first-party `ah_did` Cookie；跨域接入应由站点 SDK 在自己的 `localStorage` 保存 canonical UUID，并通过 `X-Device-ID` 传递，不能依赖第三方 Cookie 统计 UV。
- bundled Nginx route 默认拒绝超过 1 MiB 的 API body；HMAC 入口还会在应用内按实际读取字节再次校验。若调整 `ANALYTICSHUB_MAX_BODY_SIZE`，应同步评估并调整 `APP_SECURITY_MAX_REQUEST_BODY_BYTES` 与 JSON 结构限制。

## 回滚边界

数据库 migration 是 forward-only（只向前）。不要手工改已应用 migration checksum。

特别注意：1.0.0 无法读取 1.0.1 写入的 `enc:v1` 项目数据库凭据。升级后的紧急回滚不能只替换旧 JAR；要么尽快以前向修复版本恢复，要么停止应用并同时恢复升级前的 system database 备份与匹配的 1.0.0 artifact。项目数据库新增的 V2–V5 表/列可由旧版本忽略，但灾难恢复仍应以升级前的完整备份为准。

## 加密密钥轮换

1. 生成新的 Base64 32-byte key。
2. 将新 key 配为 `PROJECT_CREDENTIAL_ENCRYPTION_KEY`，旧 key 临时配为 `PROJECT_CREDENTIAL_PREVIOUS_ENCRYPTION_KEY`。
3. 单实例启动 1.0.1；启动 transaction 会使用旧 key 解密并用新 key 重加密全部项目凭据。
4. 验证所有项目 health 后，移除 previous key，并重新启动验证。

不要在所有凭据完成重加密之前删除旧 key，也不要同时跨越两代以上的 key。

## 发布证据与版本锚点

发布前必须先定位当前生产版本对应的 commit/tag 与旧 JAR；如果历史版本没有 tag，应先归档旧 JAR、配置版本和 SHA-256，不能根据分支名称或文件时间猜测生产锚点。

候选版本在干净的 checkout 中至少执行：

```bash
./scripts/mvn-project --batch-mode --no-transfer-progress clean verify
bash ops/tests/run.sh
ruby -e 'require "yaml"; YAML.load_file("src/main/resources/application.yml"); puts "yaml ok"'
bash -n ops/analyticshub
for f in ops/server/*.sh ops/apps/analyticshub/*.sh; do bash -n "$f" || exit 1; done
bash ops/analyticshub help
```

确认生成的 artifact 是 `target/analyticshub-1.0.1.jar`，记录其 SHA-256，并让 release tag、测试记录、部署 JAR 与 checksum 指向同一个 commit。仓库 CI 会在 push / pull request 上执行 Maven 全量验证与 ops 脚本测试，但本地发布检查仍不能省略生产备份、配置核对和逐项目 health 检查。
