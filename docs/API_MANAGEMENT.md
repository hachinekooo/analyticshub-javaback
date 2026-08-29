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
  "version": "1.1.2"
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

`analysisTemplate` 是项目工作台的稳定初始化模板，新建项目必填：

- `app`：仅 App 产品分析；
- `website`：仅官网流量分析；
- `webapp`：App + 官网组合分析；
- `blank`：空白工作台。

所有模板都有 `overview`（数据大屏）和 `details`（明细数据）两个稳定空间。模板只决定两个空间开放哪些只读组件及其默认布局，不决定数据库、Schema、表或采集 API 如何拆分。所有项目初始化时都创建同一套受管项目表；没有出现在当前模板中的数据不会被删除，只是不在该工作台中默认展示。历史项目在 system migration V5 中迁移为 `app`，同一 migration 会将旧 Dashboard 的 `operations` / `technical` key 迁移为 `overview` / `details`。之后可以通过更新项目 API 调整模板。

#### 项目创建、初始化与模板选择

项目配置、项目库初始化和工作台模板是三个不同层次：

1. **新建项目**：在 system database 登记稳定 `projectId`、项目库连接、目标 Schema、表前缀和分析模板；不会创建外部 PostgreSQL database 或用户。
2. **初始化项目**：管理员明确执行 `POST /api/admin/projects/{id}/init` 后，AnalyticsHub 才连接目标 database。目标 Schema 不存在时会在数据库用户权限允许的前提下创建；已经存在时只校验并执行待补的 Flyway migration，不重复创建或清空已有表。
3. **选择模板**：只控制 `overview` / `details` 中可用的组件和新工作区默认布局，不改变 `projectId`、数据库连接、Schema、表前缀或历史数据。

初始化后，目标 Schema 包含带 `tablePrefix` 的项目表。以 `dbSchema=analytics`、`tablePrefix=product_` 为例：

| 物理表 | 主要职责 |
| --- | --- |
| `product_devices` | 分析凭据注册和设备版本快照 |
| `product_events` | App、客户端或 Web 产品行为事件 |
| `product_sessions` | 分析会话事实 |
| `product_traffic_metrics` | 官网页面访问、访客、页面和来源数据 |
| `product_counters` | 不随 Dashboard 日期范围变化的长期累计值 |
| `product_idempotency_keys` | 采集请求幂等边界 |
| `product_actor_identity_links` | 匿名 actor 到权威账号 actor 的项目内关联 |
| `product_actor_suppressions` | 隐私删除后阻止迟到关联恢复的不可逆摘要 |
| `product_privacy_requests` | 隐私请求主记录 |
| `product_work_order_activities` | 隐私工单活动审计 |
| `product_work_order_outbox` | 隐私工单异步通知任务 |
| `product_flyway_history` | 该 Schema + 表前缀自己的项目 migration 历史 |

这些表属于同一个 AnalyticsHub Project，但按事实类型保持边界。例如，同一产品的 App 事件进入 `product_events`，官网访问进入 `product_traffic_metrics`；使用组合模板不会把两种身份或原始事实合并成一张表。

##### 示例：同一产品同时拥有 App 和官网

假设一个产品使用下面的通用配置：

```text
projectId:   example_product_prod
dbName:      example_product
dbSchema:    analytics
tablePrefix: product_
```

如果 App SDK 与官网流量采集都使用 `example_product_prod`：

- 选择 `webapp`，管理端显示为“App + 官网（组合分析）”；
- App 的事件、漏斗和留存继续读取 `product_events` 等产品事实表；
- 官网的 PV、访客、热门页面和来源读取 `product_traffic_metrics`；
- 两类数据在同一个项目工作台中分别展示，不要求拆分 database、Schema 或 Project；
- 官网匿名访客标识和 App actor 仍是两套身份边界，模板不会把它们自动关联。

如果项目已经使用 `app` 模板并且官网流量也已写入，只需把模板更新为 `webapp`。切换不会迁移或删除数据，也不会覆盖已保存 Dashboard；保存后在“编辑布局”中添加流量组件即可。只有当 App 与官网确实需要不同的访问权限、保留周期、数据库边界或运营归属时，才应建立两个使用不同 `projectId` 的项目，例如 `example_product_app_prod` 与 `example_product_web_prod`。同一个 `projectId` 不能重复创建。

```http
GET    /api/admin/projects
POST   /api/admin/projects
PUT    /api/admin/projects/{id}
DELETE /api/admin/projects/{id}
POST   /api/admin/projects/{id}/test   # 测试数据库连接
POST   /api/admin/projects/{id}/init   # 初始化项目表结构
GET    /api/admin/projects/{id}/health # 检查项目健康状态
```

**创建请求示例：**

```json
{
  "projectId": "your_project",
  "projectName": "Your Project",
  "analysisTemplate": "app",
  "dbHost": "postgres.example.internal",
  "dbPort": 5432,
  "dbName": "your_project",
  "dbSchema": "analytics",
  "dbUser": "your_project",
  "dbPassword": "replace-with-server-side-secret",
  "tablePrefix": "analytics_"
}
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
      "analysisTemplate": "app",
      "dbHost": "postgres.example.internal",
      "dbPort": 5432,
      "dbName": "your_project",
      "dbSchema": "analytics",
      "dbUser": "your_project",
      "tablePrefix": "analytics_",
      "isActive": true,
      "createdAt": "2026-01-01T10:00:00Z"
    }
  ],
  "timestamp": "2026-02-12T10:00:00.000Z"
}
```

响应使用专用 DTO，不返回 `dbPassword` 或 `dbPasswordEncrypted`。删除项目只删除 system database 中的项目配置，不删除项目业务库数据；`POST /init` 才会显式执行项目库 migration。

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

设备列表展示的是 Analytics credential registration（分析凭据注册）记录，字段口径如下：

- `createdAt`：当前 device record 的创建时间，不等同于 App 安装或首次打开时间；
- `appVersion`：创建该记录时的客户端版本快照，正常升级和凭据轮换不会持续刷新；
- 按 `createdAt + appVersion` 汇总可观察“哪个版本产生了多少分析注册记录”，适合排查版本发布后的 SDK 接入、凭据注册异常和同意分析用户的注册批次；它不是下载、新增安装或完整用户增长；
- “所选周期内活跃设备主要使用什么版本”应查询“活跃 App 版本”接口；下载量、重新下载和商店转化应以 App Store Connect 报告为准。

#### 管理员重置设备凭据

当设备已经丢失本地凭据、无法通过 HMAC rotate endpoint 自助轮换时，可以由管理员执行受控恢复：

```http
POST /api/admin/projects/{projectId}/devices/{deviceId}/credentials/reset
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
- `userId`: 按事件写入时的原始统计身份精确筛选
- `resolvedActorId`: 按归一身份筛选完整旅程；可传归一 actor 或其已绑定的匿名 raw actor，都会返回关联前后的事件
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
        "userId": "550e8400-e29b-41d4-a716-446655440000",
        "resolvedActorId": "0f8fad5b-d9cb-469f-a165-70867728950e",
        "identityScope": "anonymous",
        "actorLinked": true,
        "sessionId": "sess_abc",
        "properties": {
          "button_id": "login_btn"
        }
      }
    ]
  }
}
```

`userId` 是不可改写的原始采集事实：匿名阶段为统计专用 UUID，云账号阶段通常为业务云账号 UUID。
`resolvedActorId` 是当前 alias 规则下用于漏斗和旅程查询的归一身份；建立关联后通常对应云账号 UUID。
这些字段仅供受控运营和工单排查，不包含资料或内容数据。`identityScope` 来自事件采集阶段，
用于区分匿名阶段与云账号阶段，不应单独作为登录成功的业务审计证据。

#### 5.1 以事件为锚点查看用户旅程片段

```http
GET /api/admin/events/journey?projectId=your_project&anchorEventId=evt_x1y2z3&beforeMinutes=60&afterMinutes=60
```

该接口用于管理端常见的“从一条事件查看前后行为”操作：

- `anchorEventId` 必填，必须来自当前项目的真实事件；调用方不需要预先查询或复制 actor ID。
- `beforeMinutes`、`afterMinutes` 默认各 60 分钟，单侧最大 7 天。
- 有 actor 时自动解析匿名与登录后的归一身份；遗留事件没有 actor 时，退化为同设备时间线。
- 事件按发生时间正序返回，`anchorEventId` 可用于在 UI 中高亮所选事件。
- 单次最多返回最接近锚点的 200 条事件；`truncated=true` 时应向管理员明确提示范围内还有其他事件。
- 旅程事件包含完整 `properties`，供受控管理员连续排查；该接口不得暴露给普通项目成员或公共客户端。
- 正常属性直接返回；超过 64 KiB 或超出整段响应预算的属性会标记 `propertiesDeferred=true`。
  其中不超过 2 MiB 的属性可由管理端调用
  `GET /api/admin/events/properties?projectId=...&eventId=...` 单独读取；超过 2 MiB 时仅返回大小且不提供在线加载，
  避免异常数据拖垮服务端或浏览器。
- V8 起，事件写入会同时保存 `properties_size_bytes` 与 `identity_scope` 查询元数据；旅程候选只读取该整数判断
  是否加载属性，不会为了估算大小批量解压 JSONB。升级前、同样受 1 MiB 请求上限约束的历史事件由后台任务
  按主键小批次、独立事务、可续跑地回填；回填完成前，尚未处理的旧属性按需加载，不阻塞服务启动。
  调度器每轮可连续提交有限批次，并在遇到末批时立即停止本轮，以缩短旧数据的体验降级窗口而不扩大单事务。
- 从 V7 升级 V8 需在低流量维护窗口由管理员执行一次“初始化项目数据库”。V8 在单一事务中加列并建立三个索引，
  成功后整体提交，失败则整体回滚；jar 重启本身不会隐式修改各项目数据库。迁移前的 V7 项目继续走原事件写入字段，
  元数据回填会等待 V8 列就绪，不影响既有事件采集。
- 同一毫秒内发生的事件按数据库接收顺序稳定排列；该顺序用于打破时间戳并列，不替代客户端发生时间。
- 该结果用于运营理解和有限问题定位，不是账号或信件业务审计记录。

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
    "devicesInventoryTotal": 5000,
    "devicesActive": 1200,
    "usersActive": 800,
    "cloudAccountsCreated": 36,
    "cloudAccountsRecreated": 2,
    "sessionsTotal": 15000,
    "eventsTotal": 45000,
    "avgSessionDurationMs": 180000,
    "avgEventsPerSession": 3.0,
    "availableMetricKeys": [
      "system.active_devices",
      "system.active_actors",
      "system.event_occurrences",
      "system.top_active_app_version",
      "core.account.created",
      "core.account.recreated"
    ]
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
    "availableMetricKeys": [
      "system.active_actors",
      "system.active_devices",
      "core.account.created",
      "core.account.recreated"
    ],
    "points": [
      {
        "time": "2026-01-01",
        "events": 150,
        "activeDevices": 42,
        "activeUsers": 31,
        "cloudAccountsCreated": 3,
        "cloudAccountsRecreated": 0,
        "sessions": 50
      },
      {
        "time": "2026-01-02",
        "events": 180,
        "activeDevices": 48,
        "activeUsers": 35,
        "cloudAccountsCreated": 2,
        "cloudAccountsRecreated": 1,
        "sessions": 60
      }
    ]
  }
}
```

`usersActive` / `activeUsers` 按事件真实发生时间统计，并通过 actor alias 将同一用户的匿名阶段与登录阶段归一后去重。账号创建与重建指标分别依赖 `core.account.created`、`core.account.recreated` 官方语义；项目必须把自己的后端权威事件配置为 raw aliases。

`availableMetricKeys` 是展示能力的权威边界：system 指标始终可用；official business metric 只有语义定义启用且至少存在一个有效 alias 时才会出现。调用方必须用它区分“尚未配置，不应展示”和“已配置但当前周期确实为 0”。账号指标只代表已同意数据分析且成功采集到的可观测账号，不等于业务账号库总量。

滚动升级时应先发布 backend、再发布 frontend。新版 frontend 对尚未返回 `availableMetricKeys` 的旧 backend 只回退展示旧 API 已能可靠提供的 system 指标，不推断任何业务能力。

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

#### 活跃 App 版本

```http
GET /api/admin/metrics/app-versions?projectId=your_project&from=2026-01-01&to=2026-01-31
```

该接口按活跃设备统计，而不是按账号统计。每台设备只采用所选时间范围内最后发生事件携带的
`app_version` 与 `build_number`，因此同一设备在周期内升级不会被重复计数。`coverageRate` 表示
携带可识别版本的活跃设备占比；未携带版本的历史事件归入 `unknown`，不会静默丢弃。
这里的“活跃”要求设备在周期内实际产生事件且用户允许分析；升级后从未打开 App、尚未同步离线队列或未同意分析的设备不会被猜测计入。

```json
{
  "success": true,
  "data": {
    "measurement": "latest_occurred_event_per_device",
    "activeDevices": 120,
    "versionKnownDevices": 116,
    "coverageRate": 0.9667,
    "items": [
      {
        "appVersion": "2.4.1",
        "buildNumber": "241",
        "activeDevices": 80,
        "share": 0.6667,
        "lastObservedAt": "2026-01-30T12:00:00Z"
      }
    ]
  }
}
```

#### 漏斗分析

```http
GET /api/admin/analytics/funnel?projectId=your_project&from=2026-01-01&to=2026-01-31&steps=landing,checkout,purchase&groupBy=source
```

可选 `journeyKey` 用于按一次业务旅程而不是按 actor 计数。例如付费墙使用
`journeyKey=paywall_flow_id`，同一用户的多次触发会分别进入漏斗；缺少该属性的事件不会被猜测归入某次旅程。
响应中的 `countingUnit` 为 `actors` 或 `journeys`，管理端必须据此显示“人数”或“旅程数”。

- `steps` 必须按顺序提供 2–12 个不同 event key。
- actor（统计主体）优先使用 `user_id`，遗留空值才回退到 `device_id`；未配置 `journeyKey` 时每一步按 actor 去重。
- `groupBy` 可选，只读取第一步事件顶层 properties 中对应的 key。
- `journeyKey` 可选；配置后按 `canonical actor + journey value` 计数，属性缺失或非字符串的事件不进入该旅程。
- `attributionModel=first_touch_actor` 表示按 actor 首触归因；`first_touch_journey` 表示按每次旅程首触归因。
- `conversionRate` 相对第一步计算，`dropOffRate` 相对上一步计算。

#### 留存分析

```http
GET /api/admin/analytics/retention?projectId=your_project&from=2026-01-01&to=2026-01-31&cohortEvent=signup&returnEvent=app_open&days=0,1,7,30
```

- `from` / `to` 定义 cohort event 的入组范围；响应中的 `rangeStart / rangeEnd` 对应该范围。
- 服务端计划读取最大留存日之后一天内的 return event，响应以 `requestedObservationEnd` 表示计划截止、`observationEnd` 表示截至查询时刻的实际数据截止，并以 `observationComplete` 标识计划窗口是否成熟。return event 必须在 `(event_timestamp, id)` 顺序上严格晚于该 actor 的首次 cohort event；同日更早事件与入组事件本身都不算回访，同毫秒事件按数据库写入 ID 判定先后。每个 bucket 的 `eligibleUsers` 只包含已完整走完对应 UTC 自然日的分群成员，`retentionRate` 使用该值而不是全部 `cohortUsers` 作分母；未来尚未发生的观察时间不会被算作零留存。最大查询范围按 `rangeStart` 到 `requestedObservationEnd` 的完整计划扫描窗口校验，不会只按入组范围放行超大查询。
- `days` 支持 0–90、自动去重并升序返回，省略时为 `1,7,30`。
- D0、D1 等按 UTC calendar day（UTC 自然日）计算，不按“注册后满 24 小时”计算；同一 actor 同一天多次返回只计一次。

上述事件列表、概览、趋势、热门事件、活跃版本、漏斗与留存统一使用 `eventTimestamp`
（客户端记录的真实发生时间）做范围和顺序口径，使离线积压事件仍归入实际发生周期。服务端
`created_at` 保留为接收时间，只用于传输延迟、补发和故障诊断，不参与产品运营窗口；两个时间字段
不得在同一运营指标中隐式混用。会话类指标仍使用独立的 `sessionStartTime`。

### 8. 流量指标（查询与分析）

```http
GET /api/admin/traffic-metrics?projectId=your-project-id&metricType=page_view&page=1&pageSize=20
GET /api/admin/traffic-metrics/summary?projectId=your-project-id&from=...&to=...
GET /api/admin/traffic-metrics/trends?projectId=your-project-id&granularity=day
GET /api/admin/traffic-metrics/top-pages?projectId=your-project-id&limit=10
GET /api/admin/traffic-metrics/top-referrers?projectId=your-project-id&limit=10
```

**接口说明**：
- `summary`：返回核心计数（PV、UV），自动排除机器人流量；未传 `from` / `to` 时与运营趋势、排行统一使用最近 7 天。
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

管理前端的“计数器”页面提供新建、编辑、删除、人工递增和按累计口径回算入口。常规运营配置应使用管理页面或以下管理 API，不要直接修改项目数据库表。新建带 `eventTrigger` 的 Counter，或修改它的规则、历史范围、基础调整值时，管理前端会在保存后自动调用 rebuild；后续匹配事件会自动累计。单独调用 `PUT` 只保存配置，不隐式执行可能较重的历史扫描。

#### 配置化自动触发

**配置示例 (PUT /api/admin/counters/{key})**：

`key` 长度为 1–100，只允许字母、数字、点、下划线、冒号和连字符，并且必须以字母或数字开头。

```json
{
  "displayName": {"zh": "累计完成任务", "en": "Completed Tasks"},
  "unit": {"zh": "项", "en": "Tasks"},
  "eventTrigger": {
    "semantic_key": "core.action.completed",
    "conditions": {"status": "success"}
  },
  "historyMode": "INCLUDE_EXISTING",
  "rebuildOffset": 12,
  "isPublic": true
}
```

**效果**：当采集 API 收到一个映射到 `core.action.completed`、且属性中 `status == "success"` 的事件时，该计数器自动 +1。raw event key 的调整只需维护语义字典，不需要修改 Counter。

一个 Counter 也可以监听多个稳定语义：

```json
{
  "eventTrigger": {
    "semantic_keys": ["core.action.completed", "custom.content.shared"]
  }
}
```

这时顶层 `conditions` 会同时作用于所有语义。不同语义需要不同条件时，使用 `any_of`：

```json
{
  "eventTrigger": {
    "any_of": [
      {"semantic_key": "core.action.completed"},
      {
        "semantic_key": "custom.content.shared",
        "conditions": {"status": "success"}
      }
    ]
  }
}
```

以上规则表示 `(core.action.completed) OR (custom.content.shared AND status == success)`；实时累计和历史 rebuild 使用同一份解析规则。完全重复的 clause 会被拒绝。

`semantic_key`、`semantic_keys` 与 `any_of` 是三种互斥形态：

- `semantic_key`：单个语义 Key，可带一组顶层 `conditions`。
- `semantic_keys`：1–100 个不重复语义 Key，共享一组可选的顶层 `conditions`。
- `any_of`：1–100 个 clause；每个 clause 只允许一个 `semantic_key` 和可选 `conditions`，不能再配置顶层 `conditions`。

规则使用 allow-list validation（白名单校验）。`conditions` 必须是 JSON object；允许嵌套 object、array、string、number、boolean 和 `null`。最大嵌套深度为 8，每个 object/array 最多 100 项，字段名长度 1–100，字符串值最长 1024，整条规则的 condition 节点总数最多 1000。非法规则返回 HTTP 400 `INVALID_COUNTER_EVENT_TRIGGER`。

保存规则时会校验引用的语义定义存在且启用。语义 aliases 更新后调用 rebuild 即可按新映射重算。若要把已有 Counter 改成纯手工模式，发送 `{"clearEventTrigger": true}`；不能同时传 `eventTrigger`。

#### 首次统计口径与基础调整值

事件驱动 Counter 必须明确已有事件是否纳入累计：

- `historyMode=INCLUDE_EXISTING`：默认值；rebuild 统计全部匹配的历史事件。
- `historyMode=START_FROM_NOW`：首次选择该口径时由数据库记录服务端起算时间；rebuild 只统计该边界之后接收的匹配事件。以后重复 rebuild 不会把边界移动到新的“现在”。
- `rebuildOffset`：持久化的基础调整值，可为正数、零或负数。它用于补入埋点接入前已有的业务累计量，或扣除已确认的异常数据。

最终值始终按 `匹配事件数 + rebuildOffset` 计算，`lastRebuildEventCount` 只记录匹配事件数，不包含调整值。自动 Counter 的临时人工 `increment` 会在下次 rebuild 时被公式覆盖；需要永久修正时应编辑 `rebuildOffset`。纯手工 Counter 不使用历史范围和 rebuild offset。

#### 管理端操作接口

用于管理配置或手动同步数据：

```http
GET    /api/admin/counters?projectId=...
GET    /api/admin/counters/{key}?projectId=...     # 获取单个计数器详情
PUT    /api/admin/counters/{key}?projectId=...     # 创建或更新规则/元数据
POST   /api/admin/counters/{key}/increment?projectId=... # 手动增减当前值；不修改持久化基础调整值
POST   /api/admin/counters/{key}/rebuild?projectId=... # 按已保存的规则、范围和调整值回算
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
      "semantic_key": "core.action.completed",
      "conditions": {"status": "success"}
    },
    "historyMode": "INCLUDE_EXISTING",
    "rebuildOffset": 12,
    "eventCountStartAt": null,
    "updatedAt": "2026-02-12T10:00:00Z",
    "lastRebuiltAt": "2026-02-12T10:00:00Z",
    "lastRebuildEventCount": 88
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
GET /api/admin/privacy/requests?projectId=your_project&page=1&pageSize=20&status=SUBMITTED&processor=ANALYTICSHUB
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

#### 3) 客服执行项目数据处理

App 只负责创建申请，不会自动导出或匿名化数据。客服确认用户、设备和请求类型后，通过管理端执行：

```http
POST /api/admin/privacy/requests/{requestId}/execute?projectId=your_project
Content-Type: application/json

{
  "version": 3,
  "operator": "customer-service",
  "confirmation": "prv_example"
}
```

- `EXPORT`：生成当前项目中工单用户及其一跳匿名 actor phase 的事件、会话和流量 JSON 快照；设备只按工单 device ID 导出。设备 API Key、Secret 及历史凭据不会导出。`confirmation` 可省略。
- `DELETE`：执行内置 anonymization policy（匿名化策略），不物理删除分析事实。会覆盖该用户的一跳匿名 actor phase，撤销仅工单设备的凭据，并移除活动 alias；必须在 `confirmation` 中输入完整工单号。
- 两种操作都使用 `version` 做 optimistic concurrency（乐观并发控制），并在同一个项目数据库事务中更新工单和追加审计活动。
- 成功后工单自动变为 `COMPLETED`。`ANALYTICSHUB` 工单不能只通过状态接口直接标记完成。

匿名化策略会替换受影响 actor closure（身份闭包）内的用户、设备、会话和记录 ID，清空自由 JSON 属性、页面路径和来源，降低时间精度，并撤销工单设备凭据。它不会因为同一设备上存在其他用户的事件而扩展处理范围。完成后系统只保留 canonical actor 的 SHA-256 摘要，用于把迟到的 actor-link 确认为终态成功，避免删除后重建关联。Counter 等聚合统计、隐私工单和不可变活动记录保留。部署方仍需结合实际埋点内容、备份、日志、法定保存期限与适用地区法规评估匿名化效果；AnalyticsHub 的内置策略不构成法律意见，也不自动证明达到任一司法辖区的匿名化标准。

#### 4) 更新工单状态（人工回填）

```http
PATCH /api/admin/privacy/requests/{requestId}?projectId=your_project
Content-Type: application/json

{
  "version": 3,
  "status": "COMPLETED",
  "operator": "ops@yourcompany.com",
  "operatorNote": "processed by customer service",
  "resultPayload": {
    "ticketNo": "PH-20260213-001",
    "completedAt": "2026-02-13T10:00:00Z"
  },
  "notifyUser": true,
  "notificationMessage": "Your request has been completed."
}
```

`version` 必填。服务端使用 optimistic concurrency（乐观并发控制）；版本过期返回 `PRIVACY_REQUEST_VERSION_CONFLICT` / 409。状态仅允许向前流转，终态不可改成另一状态。

状态接口用于进入处理中、拒绝、取消，以及兼容由其他处理器完成的工单。AnalyticsHub 自有数据的 `COMPLETED` 必须由上述执行接口产生。

#### 5) 手动发送通知邮件

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

#### 6) 查询不可变处理记录

```http
GET /api/admin/privacy/requests/{requestId}/activities?projectId=your_project
```

#### 7) 运维补偿投递

```http
POST /api/admin/privacy/requests/outbox/deliver?projectId=your_project&batchSize=20
```

正常情况下由内置 scheduler 自动投递；该接口用于人工或外部调度补偿。邮件未配置时 scheduler 不消费队列。

完整状态机、客服页面操作、去标识化字段和审计保留口径见 [隐私工单处理流程](PRIVACY_WORKFLOW.md)。本 API 文档只维护接口契约。

### 12. 项目语义字典

语义字典保存在 system database，原始事件仍保存在项目数据库。一个项目内多个历史/当前 raw key 可以映射到一个稳定 semantic key，历史事件事实不会被重写。

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
- `app` / `webapp` 项目自动初始化四个 `core.*` 官方语义；官方定义不能删除。
- 新增项目自定义语义必须使用 `custom.*`；右侧 semantic key 创建后不提供改名操作，展示名称和左侧 aliases 可以继续维护。

### 13. 项目 Dashboard 定义

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
        "layout": {"x": 0, "y": 0, "w": 12, "h": 4},
        "config": {
          "metricKeys": [
            "system.active_devices",
            "system.active_actors",
            "core.account.created"
          ]
        }
      }
    ]
  },
  "expectedRevision": 0,
  "isDefault": true,
  "isActive": true
}
```

创建时 `expectedRevision` 为 `0`；更新和删除必须传当前 revision，过期返回 409。

`definition.defaultRange` 仅接受 `24h`、`7d`、`30d`、`90d`、`custom`；`custom` 表示不预置日期。它是项目 Dashboard 的初始查询范围，不是用户每次在页面选择日期后都会更新的临时状态。

`core.overview.config.metricKeys` 是有序选择：system 指标由 AnalyticsHub 事实直接计算；`core.*` 业务指标复用官方语义 Key，并且只有完成有效映射后才可新加入配置和在正常大屏展示。后端会在 Dashboard 写入边界再次校验，旧前端或脚本不能绕过。已保存业务指标后来因停用或清空映射而失效时，读取会隐藏该指标但保留原配置；无关布局编辑仍可保存，重新加入其他失效指标仍会被拒绝。历史 Dashboard 未保存该字段时，由当前可用指标生成动态兼容默认值；普通布局保存不会隐式固化，只有管理员实际编辑概览选择后才转为显式配置。

`core.counters.config.keys` 同样是有序选择，表示从项目已有计数器中拿哪些累计值展示。后端在 Dashboard 写入边界校验本次新增的引用确实存在；历史 Counter 后来被删除时允许原样保留，使无关布局编辑仍可保存，但展示和配置界面都必须明确标记失效引用。计数器组件展示截至当前的持久化累计值，不跟随 Dashboard 日期范围，也不能静默改变其他项的顺序。

### 14. 分析配置与数据质量

属性语义和指标定义保存在 system database；事件事实仍只存在对应 Project Database。

```http
GET /api/admin/projects/{projectId}/properties
PUT /api/admin/projects/{projectId}/properties/{propertyKey}
GET /api/admin/projects/{projectId}/metrics
PUT /api/admin/projects/{projectId}/metrics/{metricKey}
GET /api/admin/projects/{projectId}/trusted-schema-policy
GET /api/admin/projects/{projectId}/metric-results/{metricKey}?from=...&to=...
GET /api/admin/projects/{projectId}/analysis-packs
PUT /api/admin/projects/{projectId}/analysis-packs/{packKey}
GET /api/admin/metrics/data-quality?projectId=...&from=...&to=...
```

属性定义明确 `STRING / BOOLEAN / INTEGER / NUMBER` 类型，以及 `filterable / groupable / journeyKey / sensitive` 能力。敏感属性不能开启筛选、分组或旅程关联。漏斗、留存、运营概览、趋势、事件排行和 App 版本分布使用同一 `propertyFilters` 合同：

`allowedValues` 与筛选输入使用同一类型规范形式：BOOLEAN 归一为 `true / false`，INTEGER 去除无意义前导零，NUMBER 去除无意义尾零，STRING 去除首尾空白；规范化后重复或类型无效的定义会被拒绝。数据质量检查也按该规范形式比对真实 JSON 标量，避免定义、筛选和质量页各自解释同一个值。

```json
[
  {"propertyKey":"release_channel","operator":"EQ","values":["production"]},
  {"propertyKey":"event_schema_version","operator":"IN","values":["3","4"]},
  {"propertyKey":"campaign","operator":"EXISTS","values":[]}
]
```

- 只支持顶层属性和 `EQ / IN / EXISTS`；最多 8 个条件，`IN` 最多 20 个值，条件之间是 AND。
- 属性必须已登记、active、非敏感并启用 filterable；不支持 JSONPath、正则、表达式或 SQL。
- 未传 `propertyFilters` 时保持旧 API“不附加属性筛选”的全量统计语义；这不表示无限范围、无限候选量。无筛选请求同样受下述时间范围、候选数和超时预算保护，超限会明确失败且不返回部分统计。
- 漏斗的 `groupBy` 和 `journeyKey` 分别要求属性启用对应能力。尚未建立任何属性定义的项目处于 legacy ungoverned mode（遗留未治理模式）：任意格式合法的分组/旅程 Key 仍可兼容执行，但不代表该属性已经验证。项目首次写入属性定义或导入 Analysis Pack 时，会在同一事务内预检现有活动指标与 Dashboard；所有 `groupBy / journeyKey` 引用均已声明对应能力后才允许进入治理模式，否则整次操作回滚并返回 `ANALYTICS_GOVERNANCE_TRANSITION_BLOCKED`。

受治理指标使用稳定 `metricKey` 和 `EVENT_COUNT / UNIQUE_ACTORS / FUNNEL_CONVERSION / RETENTION` 类型。`definition` 只接受该类型的声明式字段，事件引用使用 semantic key。`metric-results` 负责执行同一查询合同，管理端不自行拼 SQL；响应通过 `resultClassification` 区分 `TRUSTED_SCHEMA`、`CROSS_VERSION_DIAGNOSTIC` 与 `UNGOVERNED_DIAGNOSTIC`。只有第一种可直接作为稳定 KPI；跨版本结果同时返回 `diagnosticReason`，未配置可信策略的结果也必须在 UI 标为诊断口径。active metric 或 active Dashboard 漏斗/留存仍引用某个 semantic key 时，系统会阻止停用、删除或替换其原始事件映射并返回 `SEMANTIC_DEFINITION_IN_USE`；`error.details` 分别列出 `metricKeys / dashboardKeys`。应先停用或调整全部依赖，完成映射变更并复核后再启用，避免看板静默换口径或清零。

管理端可读取项目现有 Pack 的完整 Manifest、版本、checksum 与更新时间，以服务端快照作为下一版本或恢复操作的来源；回退也必须用更高版本提交完整快照。

Analysis Pack schemaVersion 当前为 `1`，顶层只允许 `schemaVersion / trustedSchemaPolicy / properties / metrics`。同一 Pack 的 Manifest 是该 Pack 的 authoritative snapshot（权威快照）：升级时从 Manifest 删除的属性或指标会被停用，不会继续混入新口径。发生停用或移除可信策略时，首次请求返回 `ANALYSIS_PACK_DEACTIVATION_CONFIRMATION_REQUIRED`；管理员审查完整清单后以 `confirmDeactivations=true` 明确确认，避免把局部 JSON 当成增量更新。属性能力被停用前会检查 active metrics 与 Dashboard 的 `groupBy / journeyKey` 依赖；存在跨 Pack 或手工配置依赖时整次更新失败，不会留下“配置成功但指标不可计算”的状态。Dashboard 当前只持久化分组与旅程 Key，`propertyFilters` 是管理端当次查询条件，不写入 Dashboard 定义。Pack 版本不能回退，同版本不能对应不同 checksum；同一项目的 Pack 导入会串行执行，不同 Pack 不能声明同一个属性或指标。启用中 Pack 声明的属性和指标不能再通过单项 API 修改，必须通过 Pack 升级保持 Manifest、checksum、审计与真实定义一致。可信策略也可以引用 Pack 外部的已有属性；该属性仍可手动维护名称等安全字段，但不能被改成非 active、敏感、不可筛选、非 STRING，或移除可信值，否则整次写入回滚并返回 `ANALYSIS_PACK_TRUSTED_SCHEMA_CONFLICT`。后端原子写入属性、指标、Pack 快照与审计，并返回 Manifest 的 SHA-256 checksum。Pack 不接受 SQL、脚本、HTML、URL 或动态 import；产品私有配置应作为部署输入导入，不应硬编码到开源核心。

数据质量接口返回所选范围内由项目可信策略声明的协议值、异常客户端时间、历史超大属性，以及已登记属性的存在量、类型不一致量和允许值域之外的事件量。发布渠道、后端环境等产品特有维度必须由项目将其登记为普通受治理属性，开源核心不固定要求。`trustedSchemaPolicyConfigured` 是稳定的治理状态合同；为 `false` 时，即使 `issues` 为空也只能解释为“未验证”，不得显示 clean。此时 `schemaVersionPropertyKey` 为 `null`、`schemaVersions` 为空；配置策略后前者回显实际检查的协议属性。可信协议值超过 200 种时，`schemaVersions` 只展示高频项、`schemaVersionDistributionTruncated=true`，并加入 `schema_version_distribution_truncated` warning；缺失与不可信事件总数仍按完整检查范围独立计算。类型不一致或值域越界会进入总体 `issues`；覆盖检查超过单次 64 项上限时会返回 `propertyCoverageTotal / propertyCoverageTruncated` 并给出 warning，不会把部分清单伪装成完整结果。它只诊断，不修改历史事实。

产品事件 schema 进入稳定运营版本后，应由项目 Analysis Pack 通过 `trustedSchemaPolicy` 声明版本属性与可信值，并把该属性登记为 `STRING`、启用 `filterable`、用 `allowedValues` 覆盖可信集合。所有 active metric 必须显式筛选可信值；已人工验证为跨版本稳定的指标可声明 `schemaScope=CROSS_VERSION_VERIFIED`，同时填写不少于 10 个字符的 `schemaScopeReason` 留下审计语义。更早版本仍保留用于版本趋势、兼容性和事故复盘，不会因建立基线而删除。原始 overview、trend 和直接分析 API 未携带该筛选时属于兼容/诊断口径，不能标作受治理 KPI。AnalyticsHub 不把某个产品的 schema 版本写死在开源核心中，具体基线由项目 Analysis Pack 声明。

所有交互式分析受最大时间范围、事实候选数和事务超时保护。事件聚合按相同时间与属性条件、会话聚合按相同时间条件，先执行最多 `max + 1` 条的有界探测；超限后不会继续做完整聚合。漏斗另外限制最多 1,000 个分组以及 256 字符的 `groupBy / journeyKey` 值，避免合法高基数维度制造超大内存或响应；超限同样整体失败，不截断分组。主查询本身不加 LIMIT，因此不会把部分结果伪装成完整统计。`devicesInventoryTotal` 是项目设备库存事实，明确不参与事件 `propertyFilters`，也不进入受筛选的 Dashboard 指标清单；活跃设备使用 `devicesActive`。数据质量检查使用更小的独立事件预算；高基数诊断分布会明确标记截断，不会让整份质量报告失败。预算超限返回 `ANALYTICS_QUERY_RANGE_EXCEEDED`、`ANALYTICS_QUERY_BUDGET_EXCEEDED` 或 `ANALYTICS_QUERY_TIMEOUT`。
