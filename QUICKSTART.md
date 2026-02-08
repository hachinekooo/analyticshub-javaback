# 快速启动指南

本指南帮助你在 5 分钟内启动 Analytics Hub 后端服务。

## 前提条件

确保已安装：
- JDK 25
- Maven 3.9+
- PostgreSQL 15+
- Git

## 步骤 1: 克隆项目

```bash
cd <project-dir>
```

## 步骤 2: 配置数据库

### 创建数据库

```bash
# 连接到 PostgreSQL
psql -U root

# 创建数据库
CREATE DATABASE analytics;

# 退出
\q
```

### 更新配置

编辑 `src/main/resources/application.yml`:

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/analytics
    username: root
    password: your_password  # 修改为你的密码
```

### 为项目创建数据库与用户（管理端项目配置前置条件）

管理端创建项目**不会自动创建数据库/用户**，只会保存连接信息。为某个项目配置了 `dbName/dbUser/dbPassword` 后，需要你提前在 PostgreSQL 里创建对应的数据库与用户。

- Docker 安装的 PostgreSQL 操作示例见：[Docker_PostgreSQL_Guild.md 的 3.3 小节](docs/Docker_PostgreSQL_Guild.md#33-为项目创建数据库与用户管理端项目配置前置条件)

## 步骤 3: 构建项目

```bash
mvn clean install -DskipTests
```

## 步骤 4: 运行应用

```bash
mvn spring-boot:run
```

或者：

```bash
java -jar target/analyticshub-0.0.1-SNAPSHOT.jar
```

### 在 IDEA 里用环境变量进行 DEV 开发

推荐在 Run Configuration 里通过环境变量覆盖数据库与管理端 Token。

Run → Edit Configurations… → 选择你的 Application：

- Main class：`com.github.analyticshub.AnalyticshubJavabackApplication`
- Program arguments（可选）：`--spring.profiles.active=dev`

#### 方式 A：手动填写环境变量

在 Run Configuration 的 Environment variables 中设置（示例）：

- `DB_HOST=127.0.0.1`
- `DB_PORT=5432`
- `DB_NAME=analytics_flyway_test`
- `DB_USER=<your_db_user>`
- `DB_PASSWORD=<your_db_password>`
- `ADMIN_TOKEN=<your_admin_token>`

#### 方式 B：从文件加载环境变量

在 Run Configuration 的“从文件加载环境变量”（或 EnvFile 插件）入口选择文件加载。

文件内容通常是：

```bash
DB_HOST=127.0.0.1
DB_PORT=5432
DB_NAME=analytics_flyway_test
DB_USER=xxx
DB_PASSWORD=xxx
ADMIN_TOKEN=xxx
```

注意：在 IDEA 里这两种方式通常不能同时用（加载文件时就不能再额外手动加自定义变量）。需要叠加变量时，建议把所有变量都放进同一个文件，或改用 Program arguments / JVM options 来覆盖。

#### YAML 与环境变量优先级（Spring Boot）

同一个配置项出现多份时，常用覆盖顺序（高 → 低）：

- Program arguments（例如 `--spring.profiles.active=dev`、`--spring.datasource.url=...`）
- JVM System Properties（例如 `-Dspring.profiles.active=dev`）
- 环境变量（包括 IDEA 里手动填写、以及从文件加载的那份）
- `application-<profile>.yml`（如启用 `dev` / `prod`）
- `application.yml`

本项目 `application.yml` 里 `spring.datasource.url` 使用了占位符 `${DB_HOST:localhost}` 这类写法：

- 如果你设置了 `DB_HOST/DB_PORT/DB_NAME/DB_USER/DB_PASSWORD`，会覆盖占位符默认值
- 如果你直接设置 `SPRING_DATASOURCE_URL`（或用 `--spring.datasource.url=...`），会覆盖整个 `spring.datasource.url`

## 步骤 5: 验证服务

### 健康检查

```bash
curl http://localhost:3001/api/health
```

预期响应：
```json
{
  "status": "UP",
  "service": "analyticshub-javaback",
  "timestamp": "2026-01-12T10:00:00.000Z",
  "version": "1.0.0"
}
```

### 注册测试设备

```bash
curl -X POST http://localhost:3001/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -H "X-Project-ID: analytics-system" \
  -d '{
    "deviceId": "550e8400-e29b-41d4-a716-446655440000",
    "deviceModel": "iPhone15,2",
    "osVersion": "iOS 26.0",
    "appVersion": "1.0.0"
  }'
```

预期响应：
```json
{
  "success": true,
  "data": {
    "apiKey": "ak_xxxxxxxxxxxxx",
    "secretKey": "sk_xxxxxxxxxxxxx",
    "isNew": true
  },
  "error": null,
  "timestamp": "2026-01-12T10:00:00.000Z"
}
```

## 常见问题

### 数据库连接失败

**错误**: `Connection refused`

**解决**:
```bash
# 启动 PostgreSQL
brew services start postgresql@15  # macOS
sudo systemctl start postgresql    # Linux
```

### 端口已被占用

**错误**: `Port 3001 is already in use`

**解决**: 修改 `application.yml` 中的端口：
```yaml
server:
  port: 3002
```

### Flyway 迁移失败

**错误**: `Flyway migration failed`

**解决**:
```bash
# 清理数据库
psql -U root -d analytics -c "DROP SCHEMA public CASCADE; CREATE SCHEMA public;"

# 重新运行
mvn spring-boot:run
```

## 开发模式

使用开发模式启动（热重载）：

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```


## 生产部署

### 构建生产包

```bash
mvn clean package -Pprod
```

### 运行生产模式

```bash
java -jar target/analyticshub-0.0.1-SNAPSHOT.jar --spring.profiles.active=prod
```


## 需要帮助？

- 查看 Actuator: `curl http://localhost:3001/actuator/health`
- 提交 Issue: GitHub Issues

Happy Coding! 🚀
