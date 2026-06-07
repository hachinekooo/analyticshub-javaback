# 采集端 API 文档

本部分主要涉及数据采集、事件上报以及公开数据展示等面向客户端和公开访问的接口。

## 认证机制

- **采集端接口**：API Key + HMAC 签名 + 时间戳校验
  - 适用于：设备注册、事件追踪、会话上传、App 内流量指标采集
- **官网流量采集**：无需 HMAC 签名。
  - 基于 Cookie (`ah_did`) 自动识别设备。
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

### 2. 事件追踪

```http
POST /api/v1/events/track
Content-Type: application/json
X-Project-ID: your-project-id
X-API-Key: ak_xxxxxxxxxxxxx
X-Device-ID: 550e8400-e29b-41d4-a716-446655440000
X-User-ID: 00112233445566778899aabbccddeeff
X-Timestamp: 1673520000000
X-Signature: hmac_signature_here

{
  "eventType": "button_click",
  "timestamp": 1673520000000,
  "properties": {
    "button_name": "submit",
    "screen": "home"
  },
  "sessionId": "660e8400-e29b-41d4-a716-446655440000"
}
```

说明：
- `X-User-ID` 必须是 32 位十六进制字符串（不含 `-`）。
- HMAC 签名串格式：`method|path|timestamp|deviceId|userId|`（服务端不参与 body 签名）。

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

### 3. 会话上传

```http
POST /api/v1/sessions
Content-Type: application/json
X-Project-ID: your-project-id
X-API-Key: ak_xxxxxxxxxxxxx
X-Device-ID: 550e8400-e29b-41d4-a716-446655440000
X-User-ID: 00112233445566778899aabbccddeeff
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

### 4. 流量指标采集（采集端 App 内）

```http
POST /api/v1/traffic-metrics/track
Content-Type: application/json
X-Project-ID: your-project-id
X-API-Key: ak_xxxxxxxxxxxxx
X-Device-ID: 550e8400-e29b-41d4-a716-446655440000
X-User-ID: 00112233445566778899aabbccddeeff
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

### 5. 流量指标采集（官网 / 公共入口）

该入口专为“官网流量统计”设计，追求接入极简：

- **认证**：无需 HMAC 签名。基于 Cookie (`ah_did`) 自动识别设备。
- **项目识别**：支持通过请求头 `X-Project-ID` 或 URL 参数 `projectId` 传递（如 `?projectId=your_project`）。
- **设备识别**：前端无需传递任何 ID。服务端通过 `ah_did` Cookie 自动识别访客（用于 UV 统计）。
- **跨域支持**：请求请开启 `credentials: 'include'` 确保 Cookie 正常传递。
- **元数据**：服务端会自动解析 `userAgent`、机器人标记 (`isBot`)，并自动补全 `referrer`（基于 Header Fallback）。

**查询汇总数据（供官网展示实时 PV/UV）：**

```http
GET /api/public/traffic/summary?projectId=your-project-id&from=2024-01-01&to=2024-12-31
```
- 自动过滤机器人数据。

**响应示例：**

```json
{
  "success": true,
  "data": {
    "projectId": "your-project-id",
    "rangeStart": "2024-01-01",
    "rangeEnd": "2024-12-31",
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

### 6. 运营累计统计（官网展示集成）

适用于“累计写信 10000 封”这类高性能运营展示。

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
      "key": "total_letters",
      "value": 1024,
      "displayName": "累计寄信",
      "unit": "封",
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

### 7. 工具与测试接口

用于验证 API Key 签名实现是否正确。

```http
GET /api/v1/protected/test
X-Project-ID: ...
X-API-Key: ...
X-Signature: ...
...
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

### 8. 隐私请求（App 端）

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
