# 快速启动指南

本指南只覆盖本地启动的最短路径。生产部署、Nginx、证书、备份和密钥轮换请看 [ops/README.md](ops/README.md) 和 [部署指南](docs/运维/DEPLOYMENT_GUIDE.md)。

## 前提条件

- JDK 25
- Maven 3.9+
- PostgreSQL 15+
- Git

## 1. 进入项目

```bash
cd <project-dir>
```

## 2. 准备数据库

使用 PostgreSQL 管理账号执行：

```sql
CREATE ROLE analytic LOGIN PASSWORD 'replace-with-local-analytic-password';
CREATE DATABASE analytics OWNER analytic;
\c analytics
CREATE SCHEMA IF NOT EXISTS analytics AUTHORIZATION analytic;
```

如果你要在管理端新增业务项目，需要提前为该项目准备独立数据库和用户。管理端只保存连接信息，不会自动建库。

```sql
CREATE ROLE your_project_user LOGIN PASSWORD 'replace-with-project-password';
CREATE DATABASE your_project OWNER your_project_user;
\c your_project
CREATE SCHEMA IF NOT EXISTS analytics AUTHORIZATION your_project_user;
GRANT USAGE, CREATE ON SCHEMA analytics TO your_project_user;
```

## 3. 配置本地环境

推荐用环境变量覆盖数据库和管理端 Token：

```bash
export DB_HOST=127.0.0.1
export DB_PORT=5432
export DB_NAME=analytics
export DB_USER=analytic
export DB_PASSWORD=replace-with-local-analytic-password
export ADMIN_TOKEN=replace-with-local-admin-token
```

也可以直接编辑 `src/main/resources/application.yml`，但不要提交真实密码、Token 或生产配置。

## 4. 构建并启动

```bash
mvn clean install -DskipTests
mvn spring-boot:run
```

应用默认监听 `3001`。

## 5. 验证服务

```bash
curl http://localhost:3001/api/health
```

预期返回：

```json
{
  "status": "UP",
  "service": "analyticshub-javaback",
  "version": "1.0.0"
}
```

## 常见问题

数据库连接失败：

```bash
brew services start postgresql@15
sudo systemctl start postgresql
```

端口被占用时，可以临时指定端口：

```bash
mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=3002
```

Flyway 本地迁移失败且可以重建本地库时：

```bash
psql -U postgres -d analytics -c "DROP SCHEMA analytics CASCADE; CREATE SCHEMA analytics AUTHORIZATION analytic;"
mvn spring-boot:run
```

## 生产部署入口

```bash
sudo bash ops/analyticshub bootstrap
sudo -E env DOMAIN=analytics.example.com CERTBOT_EMAIL=admin@example.com ISSUE_CERT=true bash ops/analyticshub web
sudo bash ops/analyticshub deploy
```

上传 JAR、编辑 `/etc/analyticshub/analyticshub.env` 后：

```bash
sudo systemctl restart analyticshub
sudo bash ops/analyticshub check
sudo -E env BASE_URL=https://analytics.example.com bash ops/analyticshub check-public
```

详细说明见 [ops/README.md](ops/README.md)。
