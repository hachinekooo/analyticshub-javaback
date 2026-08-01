---
title: 管理端 API
type: api-reference
status: current
audience: admin-frontend, backend
scope: 项目管理、健康检查、数据查询、运营配置和安全管理接口
agent_notes: 按路径或小节检索；不要作为默认上下文整篇读取
---

# 管理端 API 文档

本文是 AnalyticsHub 管理后台接口参考。

## 认证机制

- **管理端接口**：`X-Admin-Token` 或 `Authorization: Bearer <token>`
  - 不走 HMAC 签名验证。
  - 适用于：`/api/admin/**` 和 `/api/v1/auth/admin-token/verify`。
  - `/api/health` 是公开健康检查接口，不需要 Admin Token。

## API 端点详情

### 1. 健康检查与运行状态

```http
GET /api/health
```

**响应示例：**

```json
{
  "status": "UP",
  "service": "analyticshub-javaback",
  "timestamp": "2026-01-12T10:00:00.000Z",
  "version": "1.0.1"
}
```

### 2. 管理端 Token 校验

使用 `X-Admin-Token` 或 `Authorization: Bearer <token>` 其中一种即可。此接口用于登录态或 Token 有效性探测。

```http
POST /api/v1/auth/admin-token/verify
X-Admin-Token: your_admin_token
```

**响应示例：**

```json
{
  "success": true,
  "data": {
    "valid": true
  },
  "timestamp": "2026-01-12T10:00:00.000Z"
}
```

### 3. 项目管理

系统数据库（`spring.datasource`）只承载项目管理信息。

每个业务项目都应使用自己独立的目标数据库和 schema；管理端创建项目**不会自动创建数据库/用户**，只会保存连接信息。`dbSchema` 为空时默认使用 `analytics`，初始化项目时会创建该 schema 并创建采集表。为某个项目配置了 `dbName/dbSchema/dbUser/dbPassword` 后，需要你提前在 PostgreSQL 里创建对应的数据库与用户。

```http
GET    /api/admin/projects
POST   /api/admin/projects
PUT    /api/admin/projects/{id}
DELETE /api/admin/projects/{id}
POST   /api/admin/projects/{id}/test   # 测试数据库连接
POST   /api/admin/projects/{id}/init   # 初始化项目表结构
GET    /api/admin/projects/{id}/health # 检查项目健康状态
```

**响应示例 (GET /projects)：**

```json
{
  "success": true,
  "data": [
    {
      "id": 1,
      "projectId": "your_project",
      "projectName": "Your Project",
      "dbName": "your_project",
      "dbSchema": "analytics",
      "createdAt": "2026-01-01T10:00:00Z"
    }
  ],
  "timestamp": "2026-02-12T10:00:00.000Z"
}
```

### 4. 设备管理与凭据恢复

```http
GET /api/admin/devices?projectId=your_project&page=1&pageSize=20
```
**参数**：
- `page`, `pageSize`: 分页参数
- `deviceId`: 精确搜素
- `apiKey`: 按 API Key 搜索
- `isBanned`: 筛选封禁状态
- `from`, `to`: 时间范围

**响应示例：**

```json
{
  "success": true,
  "data": {
    "projectId": "your_project",
    "rangeStart": null,
    "rangeEnd": null,
    "page": 1,
    "pageSize": 20,
    "total": 105,
    "items": [
      {
        "deviceId": "550e8400-e29b-41d4-a716-446655440000",
        "deviceModel": "iPhone 14 Pro",
        "osVersion": "iOS 17.2",
        "appVersion": "1.2.0",
        "isBanned": false,
        "lastActiveAt": "2026-02-12T09:30:00Z"
      }
    ]
  },
  "timestamp": "2026-02-12T10:00:00.000Z"
}
```

#### 管理员重置设备凭据

当设备已经丢失本地凭据、无法通过 HMAC rotate endpoint 自助轮换时，可以由管理员执行受控恢复：

```http
POST /api/admin/projects/your_project/devices/550e8400-e29b-41d4-a716-446655440000/credentials/reset
X-Admin-Token: your_admin_token
```

该操作不接收 request body。服务端会在 project database transaction（项目数据库事务）中锁定设备行，生成新的 API key/secret，并立即清空 current credential 之外的 previous credential grace window。设备的封禁状态不会被修改。

```json
{
  "success": true,
  "data": {
    "projectId": "your_project",
    "deviceId": "550e8400-e29b-41d4-a716-446655440000",
    "apiKey": "ak_new_credential",
    "secretKey": "sk_new_credential"
  },
  "timestamp": "2026-02-12T10:00:00.000Z"
}
```

`secretKey` 只在本次 reset response 中返回，响应带有 `Cache-Control: no-store`；后续设备查询不会再次暴露 secret。管理员应通过受控渠道交付并要求客户端原子持久化完整新凭据。设备不存在时返回 HTTP 404 `DEVICE_NOT_FOUND`。

### 5. 事件管理（查询）

```http
GET /api/admin/events?projectId=your_project
```
**参数**：
- `page`, `pageSize`: 分页参数
- `eventType`: 筛选事件类型
- `userId`: 筛选用户
- `deviceId`: 筛选设备
- `from`, `to`: 时间范围

**响应示例：**

```json
{
  "success": true,
  "data": {
    "page": 1,
    "pageSize": 20,
    "total": 5000,
    "items": [
      {
        "eventId": "evt_x1y2z3",
        "eventType": "button_click",
        "eventTimestamp": 1673520000000,
        "deviceId": "550e8400-...",
        "userId": "u_001",
        "sessionId": "sess_abc",
        "properties": {
          "button_id": "login_btn"
        }
      }
    ]
  }
}
```

### 6. 会话管理（查询）

```http
GET /api/admin/sessions?projectId=your_project
```
**参数**：
- `page`, `pageSize`: 分页参数
- `sessionId`: 精确搜索
- `userId`: 筛选用户
- `deviceId`: 筛选设备
- `from`, `to`: 时间范围

**响应示例：**

```json
{
  "success": true,
  "data": {
    "page": 1,
    "pageSize": 20,
    "items": [
      {
        "sessionId": "sess_abc",
        "deviceId": "550e8400-...",
        "userId": "u_001",
        "sessionStartTime": "2026-02-12T10:00:00Z",
        "sessionDurationMs": 120000,
        "screenCount": 5,
        "eventCount": 20
      }
    ]
  }
}
```

### 7. 产品运营指标（自定义事件分析）

与流量指标不同，此部分关注 `track` 接口上报的自定义业务事件。

```http
GET /api/admin/metrics/overview?projectId=your_project&from=2026-01-01&to=2026-01-31
```

**响应示例（概览）：**

```json
{
  "success": true,
  "data": {
    "projectId": "your_project",
    "rangeStart": "2026-01-01",
    "rangeEnd": "2026-01-31",
    "devicesTotal": 5000,
    "devicesActive": 1200,
    "usersActive": 800,
    "sessionsTotal": 15000,
    "eventsTotal": 45000,
    "avgSessionDurationMs": 180000,
    "avgEventsPerSession": 3.0
  }
}
```

```http
GET /api/admin/metrics/trends?projectId=your_project&granularity=day
```

**响应示例（趋势）：**

```json
{
  "success": true,
  "data": {
    "projectId": "your_project",
    "granularity": "day",
    "points": [
      {
        "time": "2026-01-01",
        "events": 150,
        "sessions": 50
      },
      {
        "time": "2026-01-02",
        "events": 180,
        "sessions": 60
      }
    ]
  }
}
```

```http
GET /api/admin/metrics/top-events?projectId=your_project&limit=10
GET /api/admin/metrics/top-events?projectId=your_project&limit=10&aggregation=semantic
```

**响应示例（热门事件）：**

```json
{
  "success": true,
  "data": {
    "items": [
      { "eventType": "app_open", "count": 5000 },
      { "eventType": "purchase", "count": 120 }
    ]
  }
}
```

`aggregation=raw`（默认）按原始 event key 排名；`aggregation=semantic` 会先按项目语义字典执行 many-to-one 聚合，再排名。

#### 漏斗分析

```http
GET /api/admin/analytics/funnel?projectId=your_project&from=2026-01-01&to=2026-01-31&steps=landing,checkout,purchase&groupBy=source
```

- `steps` 必须按顺序提供 2–12 个不同 event key。
- actor（统计主体）优先使用 `user_id`，遗留空值才回退到 `device_id`；每一步按 actor 去重。
- `groupBy` 可选，只读取第一步事件顶层 properties 中对应的 key。
- `attributionModel=first_touch_actor` 表示 actor 永久归到第一次进入漏斗时的 group，不会因后续重复进入而跨组重复计算。
- `conversionRate` 相对第一步计算，`dropOffRate` 相对上一步计算。

#### 留存分析

```http
GET /api/admin/analytics/retention?projectId=your_project&from=2026-01-01&to=2026-01-31&cohortEvent=signup&returnEvent=app_open&days=0,1,7,30
```

- `from` / `to` 定义 cohort event 的入组范围；服务端会继续读取最大留存日之后一天内的 return event。
- `days` 支持 0–90、自动去重并升序返回，省略时为 `1,7,30`。
- D0、D1 等按 UTC calendar day（UTC 自然日）计算，不按“注册后满 24 小时”计算；同一 actor 同一天多次返回只计一次。

上述事件列表、概览、趋势、热门事件、漏斗与留存统一使用服务端 `created_at`（接收时间）做范围和顺序口径，客户端 `eventTimestamp` 作为原始事实返回但不参与 1.0.1 的时间窗口。这样可避免错误客户端时钟污染运营报表；如业务需要离线上报的发生时间分析，应通过后续显式 time-basis contract 扩展，不能在同一报表里隐式混用两种时间。

### 8. 流量指标（查询与分析）

```http
GET /api/admin/traffic-metrics?projectId=your-project-id&metricType=page_view&page=1&pageSize=20
GET /api/admin/traffic-metrics/summary?projectId=your-project-id&from=...&to=...
GET /api/admin/traffic-metrics/trends?projectId=your-project-id&granularity=day
GET /api/admin/traffic-metrics/top-pages?projectId=your-project-id&limit=10
GET /api/admin/traffic-metrics/top-referrers?projectId=your-project-id&limit=10
```

**接口说明**：
- `summary`：返回核心计数（PV、UV），自动排除机器人流量。
- `trends`：返回时间维度的访问趋势，参数 `granularity` 支持 `hour`, `day`, `week`, `month`, `year`。
- `top-pages`：返回访问量最高的页面路径排行。
- `top-referrers`：返回流量来源站点的排行。

**响应示例（Summary）：**

```json
{
  "success": true,
  "data": {
    "projectId": "your-project-id",
    "pageViews": 10500,
    "visitors": 3200,
    "rangeStart": "2026-01-01",
    "rangeEnd": "2026-01-31"
  }
}
```

**响应示例（Trends）：**

```json
{
  "success": true,
  "data": {
    "granularity": "day",
    "points": [
      { "time": "2026-01-01", "pageViews": 100, "visitors": 80 },
      { "time": "2026-01-02", "pageViews": 120, "visitors": 90 }
    ]
  }
}
```

**响应示例（Top Pages/Referrers）：**

```json
{
  "success": true,
  "data": {
    "items": [
      { "key": "/home", "count": 500 },
      { "key": "/pricing", "count": 200 }
    ]
  }
}
```

### 9. 运营累计统计（Counters）配置与管理

通过配置 `event_trigger`，计数器会在事件上报时**全自动维护**，无需手动代码累加。

管理前端的“运营数据 → 累计统计”组件已经提供新建、编辑、删除、手动递增和历史回算入口。常规运营配置应使用管理页面或以下管理 API，不要直接修改项目数据库表。新建带 `eventTrigger` 的 Counter 后，点击“按历史事件回算”即可用同一规则重算存量事件；后续匹配事件会自动累计。

#### 配置化自动触发

**配置示例 (PUT /api/admin/counters/{key})**：

`key` 长度为 1–100，只允许字母、数字、点、下划线、冒号和连字符，并且必须以字母或数字开头。

```json
{
  "displayName": {"zh": "累计完成任务", "en": "Completed Tasks"},
  "unit": {"zh": "项", "en": "Tasks"},
  "eventTrigger": {
    "event_type": "task_completed",
    "conditions": {"status": "success"}
  },
  "isPublic": true
}
```

**效果**：当采集 API 监听到 `task_completed` 且属性中 `status == "success"` 时，该计数器自动 +1。

埋点改名后，可以使用多 raw key 规则继续累计同一个 Counter：

```json
{
  "eventTrigger": {
    "event_types": ["task_completed", "task_done_v2"]
  }
}
```

这时顶层 `conditions` 会同时作用于 `event_types` 中的所有事件。如果历史和当前埋点的判断条件不同，应使用 `any_of` 为每个 alias 配置独立 clause（分支）：

```json
{
  "eventTrigger": {
    "any_of": [
      {"event_type": "task_completed"},
      {
        "event_type": "task_done_v2",
        "conditions": {"status": "success"}
      }
    ]
  }
}
```

以上规则表示 `(task_completed) OR (task_done_v2 AND status == success)`；实时累计和历史 rebuild 使用同一语义。`any_of` 也允许同一个 `event_type` 出现在多个不同条件的 clause 中，适合表达同一事件的多个有效分支；完全重复的 clause 会被拒绝。

`event_type`、`event_types` 与 `any_of` 是三种互斥形态：

- `event_type`：单个事件 key，可带一组顶层 `conditions`，兼容 1.0.0。
- `event_types`：1–100 个不重复事件 key，共享一组可选的顶层 `conditions`。
- `any_of`：1–100 个 clause；每个 clause 只允许一个 `event_type` 和可选 `conditions`，不能再配置顶层 `conditions`。

规则使用 allow-list validation（白名单校验）。`conditions` 必须是 JSON object；允许嵌套 object、array、string、number、boolean 和 `null`。最大嵌套深度为 8，每个 object/array 最多 100 项，字段名长度 1–100，字符串值最长 1024，整条规则的 condition 节点总数最多 1000。非法规则返回 HTTP 400 `INVALID_COUNTER_EVENT_TRIGGER`。

语义字典不会隐式修改 Counter 触发规则，历史 aliases 必须明确写进规则。更新后调用 rebuild 即可按新规则重算。若要把已有 Counter 改成纯手工模式，发送 `{"clearEventTrigger": true}`；不能同时传 `eventTrigger`。

#### 管理端操作接口

用于管理配置或手动同步数据：

```http
GET    /api/admin/counters?projectId=...
GET    /api/admin/counters/metadata/event-types?projectId=... # 获取已有事件名建议
GET    /api/admin/counters/{key}?projectId=...     # 获取单个计数器详情
PUT    /api/admin/counters/{key}?projectId=...     # 创建或更新规则/元数据
POST   /api/admin/counters/{key}/increment?projectId=... # 手动累加（偏移操作）
POST   /api/admin/counters/{key}/rebuild?projectId=... # 按 eventTrigger 回算全部历史事件
DELETE /api/admin/counters/{key}?projectId=...     # 删除计数器配置
```

**响应示例（单个计数器）：**

```json
{
  "success": true,
  "data": {
    "key": "tasks_completed",
    "value": 100,
    "displayName": {"zh": "累计完成任务", "en": "Completed Tasks"},
    "unit": {"zh": "项", "en": "Tasks"},
    "isPublic": true,
    "eventTrigger": {
      "event_type": "task_completed",
      "conditions": {"status": "success"}
    },
    "updatedAt": "2026-02-12T10:00:00Z",
    "lastRebuiltAt": "2026-02-12T10:00:00Z",
    "lastRebuildEventCount": 100
  }
}
```

### 10. 安全管理

```http
GET /api/admin/security/2fa/setup  # 首次绑定时生成临时 Secret；已启用时只返回状态
```

**响应示例：**

```json
{
  "success": true,
  "data": {
    "secret": "123456789",
    "otpAuthUrl": "otpauth://totp/AnalyticsHub:AnalyticsHub-Admin?secret=123456789&issuer=AnalyticsHub",
    "status": "disabled",
    "instruction": "请将 secret 添加到 Authenticator App..."
  }
}
```

当 `APP_SECURITY_2FA_ENABLED=true` 时，接口不会回传当前 secret：

```json
{
  "success": true,
  "data": {
    "status": "enabled",
    "instruction": "2FA 已启用；出于安全原因不会通过 API 返回当前 TOTP secret。"
  }
}
```

### 11. 隐私请求管理（Admin 端）

用于管理后台（Admin）对用户发起的隐私工单进行查看、处理和状态回填。需携带 Admin Token 鉴权。

#### 1) 工单列表

```http
GET /api/admin/privacy/requests?projectId=your_project&page=1&pageSize=20&status=SUBMITTED&processor=POSTHOG
```

筛选参数（可选）：
- `openOnly`: 默认无日期、无状态筛选时为 `true`，查询全部历史中的 `SUBMITTED / IN_PROGRESS` 待办；显式传 `false` 查询全部状态
- `status`: `SUBMITTED | IN_PROGRESS | COMPLETED | REJECTED | CANCELLED`
- `requestType`: `EXPORT | DELETE`
- `processor`: `ANALYTICSHUB | POSTHOG`
- `userId`
- `from`, `to`（ISO-8601 或 yyyy-MM-dd）

#### 2) 工单详情

```http
GET /api/admin/privacy/requests/{requestId}?projectId=your_project
```

#### 3) 更新工单状态（回填处理结果）

```http
PATCH /api/admin/privacy/requests/{requestId}?projectId=your_project
Content-Type: application/json

{
  "version": 3,
  "status": "COMPLETED",
  "operator": "ops@yourcompany.com",
  "operatorNote": "processed in PostHog console",
  "resultPayload": {
    "ticketNo": "PH-20260213-001",
    "completedAt": "2026-02-13T10:00:00Z"
  },
  "notifyUser": true,
  "notificationMessage": "Your request has been completed."
}
```

`version` 必填。服务端使用 optimistic concurrency（乐观并发控制）；版本过期返回 `PRIVACY_REQUEST_VERSION_CONFLICT` / 409。状态仅允许向前流转，终态不可改成另一状态。

#### 4) 手动发送通知邮件

```http
POST /api/admin/privacy/requests/{requestId}/notify?projectId=your_project
Content-Type: application/json

{
  "subject": "Privacy request update",
  "message": "Your privacy request has been processed.",
  "operator": "ops@yourcompany.com"
}
```

成功响应表示通知已写入 transactional outbox（事务发件箱），状态为 `QUEUED`，不是已经送达：

```json
{
  "requestId": "prv_example",
  "notificationId": "example-notification-id",
  "status": "QUEUED"
}
```

#### 5) 查询不可变处理记录

```http
GET /api/admin/privacy/requests/{requestId}/activities?projectId=your_project
```

#### 6) 运维补偿投递

```http
POST /api/admin/privacy/requests/outbox/deliver?projectId=your_project&batchSize=20
```

正常情况下由内置 scheduler 自动投递；该接口用于人工或外部调度补偿。邮件未配置时 scheduler 不消费队列。

### 12. 隐私工单数据库参考

每个项目在其自身数据库的 `dbSchema` 内创建 `{{PREFIX}}privacy_requests` 表（例如 `analytics.analytics_privacy_requests`）。

核心字段说明：
- `request_id`: 工单号（`prv_` 前缀）
- `request_type`: `EXPORT / DELETE`
- `processor`: `ANALYTICSHUB / POSTHOG`
- `status`: `SUBMITTED / IN_PROGRESS / COMPLETED / REJECTED / CANCELLED`
- `result_payload`: 人工处理结果快照（JSON）

### 13. 隐私处理推荐流程

1. **发起**：App 侧通过 [采集端 API](API_COLLECTION.md) 发起导出/删除请求。
2. **建单**：后端落库工单，并触发内部通知（如邮件告警）。
3. **人工处理**：运营人员根据工单信息，在对应系统（AnalyticsHub/PostHog）执行实际操作。
4. **回填**：运营人员通过 Admin 接口调用 `PATCH` 回填处理结果。
5. **通知**：回填成功后，后端自动（或手动）发送结果通知邮件给用户。

### 14. 项目语义字典

语义字典保存在 system database，原始事件仍保存在项目数据库。一个项目内多个历史/当前 raw key 可以映射到一个稳定 semantic key。

```http
GET    /api/admin/projects/{projectId}/event-catalog
GET    /api/admin/projects/{projectId}/semantics
GET    /api/admin/projects/{projectId}/semantics/{semanticKey}
PUT    /api/admin/projects/{projectId}/semantics/{semanticKey}
DELETE /api/admin/projects/{projectId}/semantics/{semanticKey}
```

PUT 示例：

```json
{
  "sourceKind": "EVENT_TYPE",
  "displayName": {"zh-CN": "完成任务", "en": "Task Completed"},
  "category": "engagement",
  "description": "A task reached its completed state",
  "isActive": true,
  "aliasMode": "REPLACE",
  "aliases": ["task_completed", "task_done_v2"]
}
```

- `REPLACE`：`aliases` 必填，完整替换；传 `[]` 明确清空。
- `PRESERVE`：必须省略 `aliases`，保留现有映射。
- 同一 raw key 在同一项目/类型中只能属于一个 semantic key；冲突返回 409。

### 15. 项目 Dashboard 定义

Dashboard 定义保存在 system database，只接受 allow-list 内置组件和声明式参数，不接受 HTML、JavaScript、SQL、URL 或 dynamic import。

```http
GET    /api/admin/projects/{projectId}/dashboards
GET    /api/admin/projects/{projectId}/dashboards/{dashboardKey}
PUT    /api/admin/projects/{projectId}/dashboards/{dashboardKey}
DELETE /api/admin/projects/{projectId}/dashboards/{dashboardKey}?expectedRevision=3
```

创建/更新示例：

```json
{
  "displayName": {"zh-CN": "运营概况", "en": "Operations"},
  "description": "Project operations dashboard",
  "schemaVersion": 1,
  "definition": {
    "schemaVersion": 1,
    "defaultRange": "7d",
    "widgets": [
      {
        "id": "overview-main",
        "type": "core.overview",
        "layout": {"x": 0, "y": 0, "w": 12, "h": 4}
      }
    ]
  },
  "expectedRevision": 0,
  "isDefault": true,
  "isActive": true
}
```

创建时 `expectedRevision` 为 `0`；更新和删除必须传当前 revision，过期返回 409。
