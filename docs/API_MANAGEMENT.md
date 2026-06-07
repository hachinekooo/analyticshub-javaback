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
  "version": "1.0.0"
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

### 4. 设备管理（查询）

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

#### 配置化自动化（Lambda 引擎）

**配置示例 (PUT /api/admin/counters/{key})**：

```json
{
  "displayName": {"zh": "累计寄信", "en": "Total Letters"},
  "unit": {"zh": "封", "en": "Letters"},
  "eventTrigger": {
    "event_type": "send_letter",
    "conditions": {"status": "success"}
  },
  "isPublic": true
}
```

**效果**：当采集 API 监听到 `send_letter` 且属性中 `status == "success"` 时，该计数器自动 +1。

#### 管理端操作接口

用于管理配置或手动同步数据：

```http
GET    /api/admin/counters?projectId=...
GET    /api/admin/counters/metadata/event-types?projectId=... # 获取已有事件名建议
GET    /api/admin/counters/{key}?projectId=...     # 获取单个计数器详情
PUT    /api/admin/counters/{key}?projectId=...     # 创建或更新规则/元数据
POST   /api/admin/counters/{key}/increment?projectId=... # 手动累加（偏移操作）
DELETE /api/admin/counters/{key}?projectId=...     # 删除计数器配置
```

**响应示例（单个计数器）：**

```json
{
  "success": true,
  "data": {
    "key": "total_letters",
    "value": 100,
    "displayName": {"zh": "累计寄信", "en": "Total Letters"},
    "unit": {"zh": "封", "en": "Letters"},
    "isPublic": true,
    "eventTrigger": {
      "event_type": "send_letter",
      "conditions": {"status": "success"}
    },
    "updatedAt": "2026-02-12T10:00:00Z"
  }
}
```

### 10. 安全管理

```http
GET /api/admin/security/2fa/setup  # 获取 2FA 绑定指引（Secret 和 QRCode URL）
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

### 11. 隐私请求管理（Admin 端）

用于管理后台（Admin）对用户发起的隐私工单进行查看、处理和状态回填。需携带 Admin Token 鉴权。

#### 1) 工单列表

```http
GET /api/admin/privacy/requests?projectId=your_project&page=1&pageSize=20&status=SUBMITTED&processor=POSTHOG
```

筛选参数（可选）：
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
