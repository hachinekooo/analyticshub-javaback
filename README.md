# Analytics Hub - Java Backend

多项目分析系统后端服务，采用 Spring Boot 4 + JDK 25 最佳实践构建。

## 技术栈

- **JDK**: 25
- **Spring Boot**: 4.0.1
- **Spring Security**: 6.x
- **Database**: PostgreSQL 15+
- **Connection Pool**: HikariCP
- **Database Migration**: Flyway
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
- 自动表创建和版本升级
- 支持多数据源动态管理

### 4. 高性能设计
- HikariCP 连接池管理
- 连接池缓存和复用
- 异步事件处理（可扩展）

## 项目总览

- 采集端 API（`/api/v1/**`）：设备注册、事件上报、会话上传；使用 API Key + HMAC 签名认证
- 管理端 API（`/api/admin/**`）：项目管理与健康检查；使用 Admin Token 认证
- 管理端 Token 校验（`/api/v1/auth/admin-token/verify`）：用于管理端登录态/Token 有效性探测
- 多项目多数据源：每个项目独立数据库与连接池，按项目动态切换
- 系统数据库（spring.datasource）仅保存项目管理元数据（analytics_projects），不承载业务采集表
- 运行状态：`/api/health` 公开；`/actuator/**` 生产环境需要 Admin Token
- 架构与时序文档：`docs/ARCHITECTURE.md`

## 项目结构

```
src/main/java/com/github/analyticshub/
├── common/
│   └── dto/              # 通用数据传输对象
│       └── ApiResponse.java
├── config/               # 配置类
│   ├── CorsConfig.java
│   ├── SecurityConfig.java
│   ├── DatabaseInitializer.java
│   └── MultiDataSourceManager.java
├── controller/           # REST API 控制器
│   ├── AuthController.java
│   ├── AdminCounterController.java
│   ├── EventController.java
│   ├── TrafficMetricController.java
│   ├── SessionController.java
│   ├── PublicCounterController.java
│   ├── PublicTrafficController.java
│   └── HealthController.java
├── dto/                  # 请求/响应 DTO
│   ├── DeviceRegisterRequest.java
│   ├── DeviceRegisterResponse.java
│   ├── CounterIncrementRequest.java
│   ├── CounterRecord.java
│   ├── CounterUpsertRequest.java
│   ├── CountersResponse.java
│   ├── EventTrackRequest.java
│   ├── EventTrackResponse.java
│   ├── SessionUploadRequest.java
│   ├── TrafficMetricSummaryResponse.java
│   ├── TrafficMetricTrackRequest.java
│   └── TrafficMetricTrackResponse.java
├── entity/               # JPA 实体类
│   ├── AnalyticsProject.java
│   ├── Device.java
│   ├── Event.java
│   └── Session.java
├── exception/            # 异常处理
│   ├── BusinessException.java
│   └── GlobalExceptionHandler.java
├── security/             # 安全组件
│   ├── AdminAuthenticationFilter.java
│   ├── AdminApiAuthenticationFilter.java
│   ├── ApiAuthenticationFilter.java
│   └── RequestContext.java
├── service/              # 业务逻辑层
│   ├── AuthService.java
│   ├── CounterService.java
│   ├── EventService.java
│   ├── SessionService.java
│   ├── TrafficMetricService.java
│   └── TrafficMetricStatsService.java
├── util/                 # 工具类
│   └── CryptoUtils.java
└── AnalyticshubJavabackApplication.java

src/main/resources/
├── application.yml       # 应用配置
└── db/migration/         # Flyway 迁移脚本
    └── V1__init_complete_schema.sql  # 系统库初始化（仅项目管理表）
```

## 快速开始

### 1. 环境要求

- JDK 25+
- Maven 3.9+
- PostgreSQL 15+

### 2. 数据库配置

创建数据库：

```sql
CREATE DATABASE analytics;
```

系统数据库（`spring.datasource`）只用于项目管理。

每个业务项目必须使用自己的目标数据库；管理端创建项目**不会自动创建数据库/用户**，只会保存连接信息。为某个项目配置了 `dbName/dbUser/dbPassword` 后，需要你提前在 PostgreSQL 里创建对应的数据库与用户：

- Docker 安装的 PostgreSQL 操作示例见：[Docker_PostgreSQL_Guild.md 的 3.3 小节](docs/Docker_PostgreSQL_Guild.md#33-为项目创建数据库与用户管理端项目配置前置条件)

更新配置文件 `application.yml`:

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/analytics
    username: root
    password: your_password
```

### 3. 构建项目

```bash
mvn clean install
```

### 4. 运行应用

```bash
mvn spring-boot:run
```

或者：

```bash
java -jar target/analyticshub-0.0.1-SNAPSHOT.jar
```

应用将在 `http://localhost:3001` 启动。

### IDEA DEV（环境变量）

本项目支持用环境变量覆盖数据库连接与管理端 Token，适合在 IDEA Run Configuration 里做 DEV 开发（支持手动填写或从文件加载，二选一），并且需要理解 YAML 与环境变量的覆盖优先级：

- 操作步骤与优先级说明见：[快速启动指南：在 IDEA 里用环境变量进行 DEV 开发](QUICKSTART.md#在-idea-里用环境变量进行-dev-开发)

### 5. 环境变量配置

**必需配置：**
```bash
export ADMIN_TOKEN=your_secure_admin_token_here
export DB_PASSWORD=your_db_password
```

# 阿里云邮件推送配置
export MAIL_ENABLED=true
export MAIL_HOST=smtpdm.aliyun.com
export MAIL_PORT=465
export MAIL_USERNAME=notify@mail.yourdomain.com
export MAIL_PASSWORD=your_smtp_password
export ALERT_EMAIL=admin@yourdomain.com

## 双因素认证 (2FA) 配置
export APP_SECURITY_2FA_ENABLED=true
export APP_SECURITY_2FA_SECRET=your_totp_secret_key

详细配置指南见：[docs/EMAIL_SETUP.md](docs/EMAIL_SETUP.md)

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

## JDK 25 特性应用

1. **Record 类**: 用于 DTO 和配置类，提供不可变数据结构
2. **Pattern Matching**: 改进的类型检查和转换
3. **增强的加密 API**: 更安全的密钥生成和签名验证
4. **Virtual Threads**: 可用于高并发场景（预留）

## Spring Boot 4 最佳实践

1. **声明式 Security**: 使用新的 SecurityFilterChain 配置
2. **ResponseEntity**: 统一的响应封装
3. **Validation**: JSR-380 Bean Validation
4. **Actuator**: 健康检查和监控端点
5. **Profiles**: 环境配置隔离（dev/prod）

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

通过管理界面或直接插入数据库：

```sql
INSERT INTO analytics_projects (
  project_id, project_name, db_host, db_port, db_name, db_user, table_prefix
) VALUES (
  'new-project', 'New Project', 'localhost', 5432, 'analytics_new_project', 'root', 'analytics_'
);
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
