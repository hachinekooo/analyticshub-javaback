# 阿里云邮件服务配置指南

## 第一步：开通阿里云邮件推送服务

1. 登录 [阿里云控制台](https://www.aliyun.com/)
2. 搜索"邮件推送" -> 点击"立即开通"
3. 选择**按量付费**（每天 200 封免费，超出 0.01 元/封）

## 第二步：配置发信域名

### 1. 添加域名
- 进入"邮件推送控制台" -> "发信域名"
- 点击"新建域名"，输入你的域名（如 `yourdomain.com`）

### 2. 域名验证
按照提示在你的域名 DNS 中添加以下记录：

**A. SPF 记录（必需）**
```
类型: TXT
主机记录: @
记录值: v=spf1 include:spf1.dm.aliyun.com -all
```

**B. MX 记录（可选，用于收信）**
```
类型: MX
主机记录: @
记录值: mxn.mail.aliyun.com
优先级: 10
```

**C. CNAME 记录（必需）**
```
类型: CNAME
主机记录: aliyundm._domainkey
记录值: aliyundm._domainkey.XXX  （阿里云会给你具体的值）
```

### 3. 等待审核
- DNS 配置后，回到阿里云控制台点击"验证"
- 通常 10 分钟内完成验证

## 第三步：创建发信地址

1. 进入"发信地址" -> "新建发信地址"
2. 示例配置：
   - 发信地址：`notify@mail.yourdomain.com`
   - 发信名称：`Analytics Hub 安全告警`
3. 创建成功后，记录以下信息：
   - **SMTP 地址**：`smtpdm.aliyun.com`
   - **SMTP 端口**：`465`（SSL）或 `25`（无 SSL）
   - **发信地址**：`notify@mail.yourdomain.com`
   - **SMTP 密码**：点击"管理密码"设置

## 第四步：配置应用

### 方式 1：环境变量（推荐生产）

在服务器上设置：
```bash
export MAIL_ENABLED=true
export MAIL_HOST=smtpdm.aliyun.com
export MAIL_PORT=465
export MAIL_USERNAME=notify@mail.yourdomain.com
export MAIL_PASSWORD=你的SMTP密码
export ALERT_EMAIL=你的接收邮箱@gmail.com
```

### 方式 2：application.yml（开发环境）

```yaml
spring:
  mail:
    enabled: true
    host: smtpdm.aliyun.com
    port: 465
    username: notify@mail.yourdomain.com
    password: 你的SMTP密码
    properties:
      mail:
        smtp:
          auth: true
          ssl:
            enable: true

app:
  email:
    alert-recipient: 你的接收邮箱@gmail.com
```

## 第五步：测试

启动应用后，故意输错 Admin Token 5 次，你会：
1. 看到控制台输出：`[邮件已发送] 检测到暴力破解尝试`
2. 收到告警邮件到 `ALERT_EMAIL`

如果邮件未配置，会在控制台看到：
```
[邮件未配置] 安全告警: 检测到暴力破解尝试 - ...
```

## 成本说明

- **免费额度**：每天 200 封
- **超出收费**：0.01 元/封
- **暴力破解告警**：每次封禁只发 1 封邮件
- **预估成本**：如果每天被攻击 10 次 = 0.1 元/天 = 3 元/月

## 常见问题

### Q1: 邮件发送失败？
- 检查 SMTP 密码是否正确
- 确认域名已通过验证
- 查看阿里云控制台的"发送统计"

### Q2: 收不到邮件？
- 检查垃圾邮件箱
- 确认 `ALERT_EMAIL` 配置正确

### Q3: 不想用阿里云？
可以用其他服务商，只需修改 SMTP 配置：
- **腾讯企业邮箱**：`smtp.exmail.qq.com:465`
- **Gmail**：`smtp.gmail.com:587`（需开启两步验证）
- **Resend**：使用 API 而非 SMTP
