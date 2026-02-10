# 前端对接双因素认证 (2FA) 指南

AnalyticsHub 后端已启用双因素认证 (TOTP) 安全机制。当检测到**新设备**或**异常 IP**登录管理端时，接口会返回 `403 Forbidden` 并要求输入动态验证码。

本文档指导前端如何优雅地拦截该错误并弹出验证码输入框。

---

## 1. 核心流程

1.  **正常请求**：前端发起 API 请求（带 `X-Admin-Token`）。
2.  **触发拦截**：后端检测到异常环境，返回 HTTP `403`，其 Body 包含 `code: "REQUIRE_2FA"`。
3.  **前端处理**：
    *   拦截器捕获该错误。
    *   **弹出输入框**，提示用户输入 Authenticator App 上的 6 位数字。
4.  **重试请求**：前端将用户输入的 6 位数字放入 Header `X-Admin-OTP`，**重试**原请求。
5.  **成功**：后端验证通过，请求成功，且该 IP 在 24 小时内不再需要验证。

---

## 2. 接口契约

### 触发条件
当后端返回如下响应时，表示需要 2FA 验证：

- **HTTP Status**: `403 Forbidden`
- **Response Body**:
  ```json
  {
    "code": "REQUIRE_2FA",
    "message": "检测到异常/新环境登录，需要双因素认证。请提供 6 位动态验证码 (Header: X-Admin-OTP)。",
    "data": null
  }
  ```

### 重试要求
在重试请求中，必须添加以下 Header：

- `X-Admin-OTP`: `123456` (用户输入的 6 位数字)

---

## 3. 代码示例 (Axios)

建议在 Axios 的全局响应拦截器中统一处理，实现**无感验证**。

### 实现逻辑

```javascript
import axios from 'axios';
import { MessageBox, Message } from 'element-ui'; // 假设使用 Element UI，其他 UI 库同理

// 创建 axios 实例
const service = axios.create({
  baseURL: process.env.VUE_APP_BASE_API,
  timeout: 5000
});

// 响应拦截器
service.interceptors.response.use(
  response => response.data,
  async error => {
    const { response, config } = error;
    
    // =========================================================
    // 核心逻辑：拦截 2FA 要求
    // =========================================================
    if (response && response.status === 403 && response.data.code === 'REQUIRE_2FA') {
      
      try {
        // 1. 弹出输入框 (Promise 风格)
        // 注意：这里需要根据你的 UI 库调整，核心是拿到用户输入的 6 位数字
        const { value: otpCode } = await MessageBox.prompt(
          '检测到新环境登录，请输入验证码 (6位数字)', 
          '安全验证', 
          {
            confirmButtonText: '验证',
            cancelButtonText: '取消',
            inputPattern: /^\d{6}$/,
            inputErrorMessage: '格式不正确，请输入6位数字'
          }
        );

        // 2. 将 OTP 添加到原请求的 Header 中
        config.headers['X-Admin-OTP'] = otpCode;

        // 3. 重试原请求 (注意：config 是 axios 内部对象，直接传回 axios 即可)
        return await axios(config);
        
      } catch (e) {
        // 用户点击取消或重试失败
        if (e !== 'cancel') {
           Message.error('验证失败或已取消');
        }
        return Promise.reject(error); // 抛出原始错误，中断业务
      }
    }

    // 其他错误处理...
    Message.error(error.message || '系统错误');
    return Promise.reject(error);
  }
);

export default service;
```

---

## 4. 前端测试方法

1.  确保测试账号已绑定 Authenticator。
2.  在后端开启 `APP_SECURITY_2FA_ENABLED=true`。
3.  使用浏览器的 **无痕模式** 或 **清除 Cookie/Localstorage** (模拟新环境，后端主要看 IP)。
4.  发起任意管理端 API 请求（如获取项目列表）。
5.  观察是否弹出验证码输入框。
6.  输入正确验证码，观察请求是否重试并在网络面板看到 `200 OK`。
