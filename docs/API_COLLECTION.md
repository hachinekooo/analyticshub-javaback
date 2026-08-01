---
title: 采集端 API
type: api-reference
status: current
audience: frontend, client-sdk, backend
scope: 设备注册、事件上报、会话上传、公开流量与计数接口
agent_notes: 按路径或小节检索；不要作为默认上下文整篇读取
---

# 采集端 API 文档

本文是 AnalyticsHub 面向客户端和公开入口的接口参考。

## 认证机制

- **设备首次注册**：`POST /api/v1/auth/register` 是公开 bootstrap endpoint（引导注册入口），不使用 HMAC；它只为从未注册的 device UUID 发放一次凭据。
- **已注册采集端接口**：API Key + HMAC 签名 + 时间戳校验
  - 适用于：凭据轮换、事件追踪、会话上传、App 内流量指标采集
- **官网流量采集**：无需 HMAC 签名。
  - 同源接入可基于 first-party Cookie (`ah_did`) 零配置识别设备。
  - 跨域接入应稳定传递 `X-Device-ID`。
  - 可选 `X-Traffic-Token`。
  - 适用于：`/api/public/traffic/**`

## API 端点详情

### 1. 设备注册

```http
POST /api/v1/auth/register
Content-Type: application/json
X-Project-ID: your-project-id

{
  "deviceId": "550e8400-e29b-41d4-a716-446655440000",
  "deviceModel": "iPhone15,2",
  "osVersion": "iOS 26.0",
  "appVersion": "1.0.0"
}
```

**响应示例：**

```json
{
  "success": true,
  "data": {
    "apiKey": "ak_a1b2c3d4e5f6g7h8",
    "secretKey": "sk_z9y8x7w6v5u4t3s2r1q0p",
    "isNew": true
  },
  "timestamp": "2026-02-12T10:00:00.000Z"
}
```

公共注册不会覆盖同一项目下已存在 device UUID 的凭据。重复注册返回 HTTP 409 `DEVICE_ALREADY_REGISTERED`；客户端必须安全持久化首次响应，不能在遇到任意 HTTP 401 时直接删除凭据并重新注册。设备已经丢失全部凭据时，应由管理员调用管理端 credential reset endpoint（凭据重置接口），而不是恢复公开覆盖注册。

#### 已认证凭据轮换

```http
POST /api/v1/auth/credentials/rotate
X-Project-ID: your-project-id
X-API-Key: ak_xxxxxxxxxxxxx
X-Device-ID: 550e8400-e29b-41d4-a716-446655440000
X-User-ID: 00112233-4455-6677-8899-aabbccddeeff
X-Timestamp: 1673520000000
X-Signature: hmac_signature_here
```

响应 shape 与注册相同，但 `isNew=false`。客户端收到后先持久化完整新凭据，再切换内存状态。服务端默认让旧凭据保留 600 秒 grace window；窗口内旧凭据仍可认证所有 HMAC API。如果首次响应丢失，用旧凭据重试 rotate endpoint 会返回已生效的新凭据，不会再次轮换。

### 2. 事件追踪

```http
POST /api/v1/events/track
Content-Type: application/json
X-Project-ID: your-project-id
X-API-Key: ak_xxxxxxxxxxxxx
X-Device-ID: 550e8400-e29b-41d4-a716-446655440000
X-User-ID: 00112233-4455-6677-8899-aabbccddeeff
X-Timestamp: 1673520000000
X-Signature: hmac_signature_here

{
  "eventType": "button_click",
  "timestamp": 1673520000000,
  "properties": {
    "button_name": "submit",
    "screen": "home"
  },
  "sessionId": "660e8400-e29b-41d4-a716-446655440000",
  "idempotencyKey": "button-click:550e8400:1673520000000"
}
```

说明：
- `X-User-ID` 必须是 canonical UUID（标准 UUID，包含连字符）。缩写 UUID 和 32 位无连字符写法都会被拒绝，避免同一用户形成多个统计身份。
- HMAC 签名串格式：`method|path|timestamp|deviceId|userId|body`。
- `body` 使用原始请求体字符串；没有请求体时为空字符串。

**响应示例：**

```json
{
  "success": true,
  "data": {
    "eventId": "evt_123456789"
  },
  "timestamp": "2026-02-12T10:00:00.000Z"
}
```

`idempotencyKey` 是可选的 client-generated key（客户端生成键），最大 256 字符。同一项目内，同 key + 同 payload 会重放首次 `eventId`；同 key + 不同 payload 返回 HTTP 409 `IDEMPOTENCY_KEY_REUSED`。批量接口中的每个 item 独立使用自己的 key；单批最多 100 条。

### 3. 批量事件追踪

```http
POST /api/v1/events/batch
Content-Type: application/json
X-Project-ID: your-project-id
X-API-Key: ak_xxxxxxxxxxxxx
X-Device-ID: 550e8400-e29b-41d4-a716-446655440000
X-User-ID: 00112233-4455-6677-8899-aabbccddeeff
X-Timestamp: 1673520000000
X-Signature: hmac_signature_here

[
  {
    "eventType": "button_click",
    "timestamp": 1673520000000,
    "properties": {
      "button_name": "submit",
      "screen": "home"
    },
    "sessionId": "660e8400-e29b-41d4-a716-446655440000",
    "idempotencyKey": "button-click:550e8400:1673520000000"
  }
]
```

**响应示例：**

```json
{
  "success": true,
  "timestamp": "2026-02-12T10:00:00.000Z"
}
```

### 4. 会话上传

```http
POST /api/v1/sessions
Content-Type: application/json
X-Project-ID: your-project-id
X-API-Key: ak_xxxxxxxxxxxxx
X-Device-ID: 550e8400-e29b-41d4-a716-446655440000
X-User-ID: 00112233-4455-6677-8899-aabbccddeeff
X-Timestamp: 1673520000000
X-Signature: hmac_signature_here

{
  "sessionId": "660e8400-e29b-41d4-a716-446655440000",
  "sessionStartTime": "2026-01-12T10:00:00.000Z",
  "sessionDurationMs": 120000,
  "deviceModel": "iPhone15,2",
  "osVersion": "iOS 26.0",
  "appVersion": "1.0.0",
  "screenCount": 5,
  "eventCount": 20
}
```

**响应示例：**

```json
{
  "success": true,
  "timestamp": "2026-02-12T10:00:00.000Z"
}
```

### 5. 流量指标采集（采集端 App 内）

```http
POST /api/v1/traffic-metrics/track
Content-Type: application/json
X-Project-ID: your-project-id
X-API-Key: ak_xxxxxxxxxxxxx
X-Device-ID: 550e8400-e29b-41d4-a716-446655440000
X-User-ID: 00112233-4455-6677-8899-aabbccddeeff
X-Timestamp: 1673520000000
X-Signature: hmac_signature_here

{
  "metricType": "page_view",
  "pagePath": "/",
  "referrer": "https://www.google.com",
  "timestamp": 1673520000000,
  "sessionId": null,
  "metadata": {
    "utm_source": "google"
  }
}
```

**响应示例：**

```json
{
  "success": true,
  "data": {
    "metricId": "tm_12345new"
  },
  "timestamp": "2026-02-12T10:00:00.000Z"
}
```

支持批量写入：

```http
POST /api/v1/traffic-metrics/batch
```

**响应示例（批量）：**

```json
{
  "success": true,
  "data": {
    "received": 10,
    "accepted": 10,
    "rejected": 0
  },
  "timestamp": "2026-02-12T10:00:00.000Z"
}
```

### 6. 流量指标采集（官网 / 公共入口）

该入口专为“官网流量统计”设计，追求接入极简：

- **认证**：无需 HMAC 签名。可选配置 `X-Traffic-Token`。
- **项目识别**：支持通过请求头 `X-Project-ID` 或 URL 参数 `projectId` 传递（如 `?projectId=your_project`）。
- **设备识别优先级**：canonical UUID 格式的 `X-Device-ID` → 同源 `ah_did` Cookie → 服务端生成新 UUID 并写入 `SameSite=Lax` first-party Cookie。
- **同源接入**：无需维护设备 ID；浏览器会复用 `ah_did` Cookie，可零配置完成 UV 识别。
- **跨域接入**：SDK 应在站点自己的 `localStorage` 生成并保存稳定 UUID，每次通过 `X-Device-ID` 传递。浏览器可能拦截或分区第三方 Cookie，因此不能把 `credentials: 'include'` 当作可靠的跨域 UV 方案。
- **元数据**：服务端会自动解析 `userAgent`、机器人标记 (`isBot`)，并自动补全 `referrer`（基于 Header Fallback）。

跨域 SDK 示例：

```js
const storageKey = 'analyticshub_device_id';
let deviceId = localStorage.getItem(storageKey);
if (!deviceId) {
  deviceId = crypto.randomUUID();
  localStorage.setItem(storageKey, deviceId);
}

await fetch('https://analytics.example.com/api/public/traffic/track', {
  method: 'POST',
  headers: {
    'Content-Type': 'application/json',
    'X-Project-ID': 'your_project',
    'X-Device-ID': deviceId
  },
  body: JSON.stringify({ metricType: 'page_view', pagePath: location.pathname })
});
```

`X-Device-ID` 必须是小写、带连字符的 canonical UUID；格式非法时接口返回 HTTP 400，不会回退到 Cookie 或随机 ID。

**查询汇总数据（供官网展示实时 PV/UV）：**

```http
GET /api/public/traffic/summary?projectId=your-project-id&from=2026-01-01&to=2026-12-31
```
- 自动过滤机器人数据。

**响应示例：**

```json
{
  "success": true,
  "data": {
    "projectId": "your-project-id",
    "rangeStart": "2026-01-01",
    "rangeEnd": "2026-12-31",
    "pageViews": 12345,
    "visitors": 4567
  },
  "timestamp": "2026-02-12T10:00:00.000Z"
}
```

**上报数据：**

```http
POST /api/public/traffic/track
Content-Type: application/json
X-Project-ID: your-project-id
X-Device-ID: 550e8400-e29b-41d4-a716-446655440000
X-Traffic-Token: your_traffic_token
```

**响应示例：**

```json
{
  "success": true,
  "data": {
    "metricId": "tm_public_123"
  },
  "timestamp": "2026-02-12T10:00:00.000Z"
}
```

支持批量写入：

```http
POST /api/public/traffic/batch
Content-Type: application/json
X-Project-ID: your-project-id
X-Device-ID: 550e8400-e29b-41d4-a716-446655440000
```

**响应示例（批量）：**

```json
{
  "success": true,
  "data": {
    "received": 5,
    "accepted": 5,
    "rejected": 0
  },
  "timestamp": "2026-02-12T10:00:00.000Z"
}
```

### 7. 运营累计统计（官网展示集成）

适用于“累计完成任务 10000 项”这类高性能运营展示。

*   **批量加载（推荐首页使用）**：
    返回所有标记为 `isPublic=true` 的计数器，并**自动根据请求头切换语言**。
    ```http
    GET /api/public/counters?projectId=your-project-id
    Accept-Language: zh-CN  # 或 en-US
    ```

**响应示例：**

```json
{
  "success": true,
  "data": [
    {
      "key": "tasks_completed",
      "value": 1024,
      "displayName": "累计完成任务",
      "unit": "项",
      "updatedAt": "2026-02-12T09:00:00Z"
    },
    {
      "key": "total_users",
      "value": 500,
      "displayName": "用户总数",
      "unit": "人",
      "updatedAt": "2026-02-12T09:00:00Z"
    }
  ],
  "timestamp": "2026-02-12T10:00:00.000Z"
}
```

*   **精准查询**：
    ```http
    GET /api/public/counters/{key}?projectId=your-project-id
    ```

**i18n 逻辑**：服务端会根据 `Accept-Language` 自动从 `displayName` 和 `unit` 的 JSON 结构中摘取对应文字。如果未匹配到，则自动 Fallback 到中文。

### 8. 工具与测试接口

用于验证 API Key 签名实现是否正确。

```http
GET /api/v1/protected/test
X-Project-ID: your-project-id
X-API-Key: ak_xxxxxxxxxxxxx
X-Device-ID: 550e8400-e29b-41d4-a716-446655440000
X-User-ID: 00112233-4455-6677-8899-aabbccddeeff
X-Timestamp: 1673520000000
X-Signature: hmac_signature_here
```

**响应示例**：
```json
{
  "success": true,
  "data": {
    "message": "认证成功！",
    "deviceId": "...",
    "userId": "..."
  }
}
```

### 9. 隐私请求（App 端）

本模块用于用户发起数据导出或删除请求。采用 HMAC 签名鉴权，确保请求来源合法。

#### 1) 发起导出请求

```http
POST /api/v1/privacy/export
Content-Type: application/json
X-Project-ID: your_project
X-API-Key: ...
X-Device-ID: ...
X-User-ID: ...
X-Timestamp: ...
X-Signature: ...

{
  "contactEmail": "user@example.com",
  "processor": "ANALYTICSHUB",
  "source": "APP_SETTINGS",
  "requesterNote": "please send export by email",
  "metadata": {
    "region": "CN",
    "appVersion": "1.0.0"
  }
}
```

#### 2) 发起删除请求

```http
POST /api/v1/privacy/delete
```

请求体与 `/export` 一致，区别在于服务端 `requestType=DELETE`。

#### 3) 查询指定工单

```http
GET /api/v1/privacy/requests/{requestId}
```

#### 4) 查询当前用户最新工单

```http
GET /api/v1/privacy/requests/latest
```
