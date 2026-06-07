---
title: AnalyticsHub Java Backend
type: project-readme
status: current
audience: contributor, operator, backend
scope: 项目总览、快速开始、核心特性、文档入口和基础开发说明
agent_notes: 项目入口概览；具体 API、部署或安全任务应转到 docs/README.md 中对应文档
---

# AnalyticsHub Java Backend

多项目分析系统后端服务，采用 Spring Boot 4 + JDK 25 构建。

## 技术栈

- **JDK**: 25
- **Spring Boot**: 4.0.1
- **Spring Security**: 7.x（由 Spring Boot 4 管理）
- **Database**: PostgreSQL 15+
- **Connection Pool**: HikariCP
- **Database Migration**: Flyway
- **Persistence**: MyBatis Plus
- **Build Tool**: Maven

## 核心特性

### 1. 多项目支持
- 支持多个项目共享一个后端服务
- 每个项目可配置独立数据库
### 2. 安全认证与防护
- 采集端接口：API Key + Secret Key 双密钥机制 + HMAC-SHA256 签名
- 管理端接口：Admin Token（`X-Admin-Token` / `Authorization: Bearer <token>`），不使用 HMAC
- **双因素认证 (2FA)**：
  - 基于 TOTP (Google/Microsoft Authenticator)
  - 针对异常 IP / 新设备强制校验
  - 支持 24 小时信任白名单
- **暴力破解防护**：
  - 基于 IP 的限流机制（5 次失败封禁 15 分钟）
  - 防时序攻击的常量时间比较
  - 自动邮件告警（达到封禁阈值时通知管理员）
- 防重放攻击（时间戳验证）
- 设备级认证和封禁管理

### 3. 数据库管理
- 使用 Flyway 进行版本化数据库迁移
- 系统库迁移由 Flyway 管理
- 接入项目的采集表通过管理端初始化接口创建或更新
- 支持按项目配置独立数据库、schema 与连接池

### 4. 高性能设计
- HikariCP 连接池管理
- 连接池缓存和复用
- 事件采集、会话上传、流量指标与计数器写入走独立业务服务

## 项目总览

- 采集端 API（`/api/v1/**`）：设备注册、事件上报、会话上传；使用 API Key + HMAC 签名认证
- 管理端 API（`/api/admin/**`）：项目管理、数据查询与配置；使用 Admin Token 认证
- 管理端 Token 校验（`/api/v1/auth/admin-token/verify`）：用于管理端登录态/Token 有效性探测
- 多项目多数据源：每个项目独立数据库与连接池，按项目动态切换
- 系统数据库（spring.datasource）仅保存项目管理元数据（analytics_projects），不承载业务采集表
- 运行状态：`/api/health` 公开；`/actuator/**` 生产环境需要 Admin Token
- 架构与时序文档：`docs/ARCHITECTURE.md`

## 文档入口

- 完整索引：[docs/README.md](docs/README.md)
- 本地启动：[QUICKSTART.md](QUICKSTART.md)
- 生产部署：[docs/运维/DEPLOYMENT_GUIDE.md](docs/运维/DEPLOYMENT_GUIDE.md)
- 运维脚本：[ops/README.md](ops/README.md)
- 架构说明：[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)

## 快速开始

本地启动的完整步骤见 [QUICKSTART.md](QUICKSTART.md)。最短路径如下：

```bash
export DB_HOST=127.0.0.1
export DB_PORT=5432
export DB_NAME=analytics
export DB_SCHEMA=analytics
export DB_USER=analytic
export DB_PASSWORD=replace-with-local-analytic-password
export ADMIN_TOKEN=replace-with-local-admin-token

mvn clean install -DskipTests
mvn spring-boot:run
```

健康检查：

```bash
curl http://localhost:3001/api/health
```

系统数据库（`spring.datasource`）只保存项目管理元数据，不承载业务采集表。管理端创建项目不会自动创建数据库或用户，接入项目的目标数据库和账号需要提前准备：

```sql
CREATE ROLE your_project_user LOGIN PASSWORD 'replace-with-project-password';
CREATE DATABASE your_project OWNER your_project_user;
\c your_project
CREATE SCHEMA IF NOT EXISTS analytics AUTHORIZATION your_project_user;
GRANT USAGE, CREATE ON SCHEMA analytics TO your_project_user;
```

## 环境变量配置

**必需配置：**
```bash
export ADMIN_TOKEN=your_secure_admin_token_here
export DB_PASSWORD=your_db_password
```

**邮件告警配置（可选）：**
```bash
export MAIL_ENABLED=true
export MAIL_HOST=smtpdm.aliyun.com
export MAIL_PORT=465
export MAIL_USERNAME=notify@mail.yourdomain.com
export MAIL_PASSWORD=your_smtp_password
export ALERT_EMAIL=admin@yourdomain.com
```

**双因素认证 (2FA) 配置（可选）：**
```bash
export APP_SECURITY_2FA_ENABLED=true
export APP_SECURITY_2FA_SECRET=your_totp_secret_key
```

详细配置指南见：[docs/EMAIL_SETUP.md](docs/EMAIL_SETUP.md)

## 生产部署与运维

本仓库提供 AnalyticsHub 自身的运维脚本闭环，入口是：

```bash
sudo bash ops/analyticshub <command>
```

常用命令：

```bash
# 初始化主机依赖、PostgreSQL、swap、journald 和 AnalyticsHub 数据库
sudo bash ops/analyticshub bootstrap

# 安装/检查证书并写入 /analyticshub/ Nginx 路由
sudo -E env DOMAIN=analytics.example.com CERTBOT_EMAIL=admin@example.com ISSUE_CERT=true bash ops/analyticshub web

# 创建 AnalyticsHub systemd、env、日志目录和运行用户
sudo bash ops/analyticshub deploy

# 本地检查和公网检查
sudo bash ops/analyticshub check
sudo -E env BASE_URL=https://analytics.example.com bash ops/analyticshub check-public

# 定期维护
sudo bash ops/analyticshub backup-db
sudo bash ops/analyticshub rotate-secrets
```

完整说明见 [ops/README.md](ops/README.md) 和 [部署指南](docs/运维/DEPLOYMENT_GUIDE.md)。

## API 文档

详细的 API 接口文档已拆分为两个独立文档：

- **[采集端 API 文档](docs/API_COLLECTION.md)**：包含设备注册、事件上报、会话上传、公开流量与计数等面向客户端的接口。
- **[管理端 API 文档](docs/API_MANAGEMENT.md)**：包含项目管理、健康检查、流量分析、运营配置等面向管理员的接口。


## 认证机制

- 管理端接口：`X-Admin-Token` 或 `Authorization: Bearer <token>`，不走 HMAC
- 采集端接口：API Key + HMAC 签名 + 时间戳校验
- 官网流量采集：`/api/public/traffic/**` 不走 HMAC；可选 `X-Traffic-Token`
- 详细流程与时序说明见 `docs/ARCHITECTURE.md`

## 数据库迁移

使用 Flyway 进行数据库版本管理：

### 创建新迁移

在 `src/main/resources/db/migration/` 目录下创建新的 SQL 文件：

```
V3__add_new_feature.sql
```

文件名格式：`V{version}__{description}.sql`

### 迁移状态

```bash
mvn flyway:info
```

### 手动迁移

```bash
mvn flyway:migrate
```

## 配置说明

### 应用配置

主要配置项在 `application.yml`:

- `server.port`: 服务端口（默认 3001）
- `spring.datasource.*`: 数据库连接配置
- `spring.flyway.*`: Flyway 迁移配置
- `app.rate-limit.*`: 请求限流配置
- `app.security.*`: 安全配置
- `app.traffic.*`: 官网流量采集配置（Token、IP 哈希盐）

### 环境配置

- **开发环境**: `spring.profiles.active=dev`
- **生产环境**: `spring.profiles.active=prod`

## 开发指南

### 添加新的端点

1. 在 `dto/` 创建请求和响应 DTO
2. 在 `service/` 实现业务逻辑
3. 在 `controller/` 创建 REST 端点
4. 在 `SecurityConfig` 配置认证规则（如需要）

### 添加新的项目

推荐通过管理端 API 或管理界面创建项目。接入项目的目标数据库和账号需要提前准备：

```sql
CREATE ROLE your_project_user LOGIN PASSWORD 'replace-with-project-password';
CREATE DATABASE your_project OWNER your_project_user;
\c your_project
CREATE SCHEMA IF NOT EXISTS analytics AUTHORIZATION your_project_user;
```

## 许可证

MIT License

## 📧 联系作者

- **Email**: hachineko@yeah.net
- **GitHub**: [@hachinekooo](https://github.com/hachinekooo)

欢迎交流和反馈！

---

## ☕ 请我喝杯咖啡

如果这个项目对你有帮助，可以请我喝杯咖啡 😊

欢迎扫码支持，你的支持是我持续更新的动力！

<div align="center">
  <img src="./docs/img/wechat-pay.jpg" alt="微信赞赏码" width="200"/>
  <img src="./docs/img/alipay.jpg" alt="支付宝收款码" width="200"/>
  
  <p><i>微信 & 支付宝</i></p>
</div>

<div align="center">
  <img src="./docs/img/wechat-qr.jpg" alt="个人微信" width="200"/>
  
  <p><i>添加微信 | 技术交流</i></p>
</div>
