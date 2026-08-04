---
title: 快速启动指南
type: quickstart
status: current
audience: contributor, evaluator, backend
scope: 本地后端启动、演示数据初始化和前端验收最短路径
agent_notes: 只覆盖本地启动；生产部署、Nginx、证书、备份和轮换见运维文档
---

# 快速启动指南

本指南用于从空环境启动后端，并可选加载三种项目模板的演示数据、连接管理前端。生产部署、Nginx、证书、备份和密钥轮换请看 [ops/README.md](ops/README.md) 和 [部署指南](docs/运维/DEPLOYMENT_GUIDE.md)。

## 前提条件

- JDK 25
- PostgreSQL 15+
- Git
- OpenSSL、curl

仓库自带 Maven Wrapper，不要求全局安装 Maven。维护者可使用 jenv；其他开发者只需确保 `java -version` 指向 JDK 25。

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

也可以复制模板并在本机维护配置：

```bash
cp .env.dev.example .env.dev
# 编辑 .env.dev，替换密码、Token，并填入 openssl rand -base64 32 的输出
set -a
source ./.env.dev
set +a
```

Spring Boot 不会自动读取 `.env.dev`，因此每次新开终端都需要执行上面的 `source`。`.env.dev` 已被 Git 忽略，不要把真实密码、Token 或密钥写回 `.env.dev.example`。

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

## 6. 加载演示数据并查看完整页面（可选）

要快速体验 App、Website 和 WebApp 三种项目模板，请先保持后端运行，再在另一个终端执行：

```bash
cd <project-dir>
set -a
source ./.env.dev
set +a
bash examples/demo-data/seed.sh
```

演示脚本额外依赖 `jq` 和 PostgreSQL `psql`。执行成功后会生成 `demo_app`、`demo_website`、`demo_webapp` 三个项目；详细边界和 Docker 用法见 [演示数据说明](examples/demo-data/README.md)。

然后启动 [AnalyticsHub 前端](https://github.com/hachinekooo/analyticshub-vuefront)：

```bash
cd <analyticshub-vuefront-dir>/frontend
nvm install
nvm use
corepack enable
pnpm install --frozen-lockfile
pnpm dev
```

打开 `http://127.0.0.1:5173/analyticshub/`，使用与后端相同的 `ADMIN_TOKEN` 登录。项目首页应显示三张演示项目卡片；进入项目后可查看对应 Dashboard、明细、指标字典、Counter 和隐私工单。

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
- 理解演示数据：查看 [三模板演示数据](examples/demo-data/README.md)。
- 生产部署：转到 [部署指南](docs/运维/DEPLOYMENT_GUIDE.md)，不要沿用本地配置。
