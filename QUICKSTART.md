---
title: 快速启动指南
type: quickstart
status: current
audience: contributor, backend
scope: 本地启动最短路径、基础数据库准备和健康检查
agent_notes: 只覆盖本地启动；生产部署、Nginx、证书、备份和轮换见运维文档
---

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
export DB_SCHEMA=analytics
export DB_USER=analytic
export DB_PASSWORD=replace-with-local-analytic-password
export ADMIN_TOKEN=replace-with-at-least-32-random-characters
export PROJECT_CREDENTIAL_ENCRYPTION_KEY="$(openssl rand -base64 32)"
```

也可以复制 `.env.dev.example` 维护本地环境文件；不要修改并提交带有真实密码、Token 的共享配置。

## 4. 构建并启动

```bash
./scripts/mvn-project -DskipTests compile
./scripts/mvn-project spring-boot:run
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
  "version": "1.0.1"
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
./scripts/mvn-project spring-boot:run -Dspring-boot.run.arguments=--server.port=3002
```

Flyway 本地迁移失败且可以重建本地库时：

```bash
psql -U postgres -d analytics -c "DROP SCHEMA analytics CASCADE; CREATE SCHEMA analytics AUTHORIZATION analytic;"
./scripts/mvn-project spring-boot:run
```

## 下一步

- 创建和初始化接入项目：查看 [管理端 API](docs/API_MANAGEMENT.md#3-项目管理)。
- 启动管理前端：查看前端仓库 README。
- 生产部署：转到 [部署指南](docs/运维/DEPLOYMENT_GUIDE.md)，不要沿用本地配置。
