---
title: 本地 Docker PostgreSQL
type: local-note
status: current
audience: maintainer, agent
scope: 维护者本机 Docker PostgreSQL 连接和排障
agent_notes: 仅用于维护者本机环境；不是生产部署口径
---

# 本地 Docker PostgreSQL

本文只描述维护者本机开发环境，方便本地调试和 AI 代理排障。它不是生产部署口径；生产数据库初始化以 `ops/README.md` 和 `docs/运维/DEPLOYMENT_GUIDE.md` 为准。

## 当前本机口径

- PostgreSQL 运行在 Docker 容器中。
- 容器名：`infra-postgres15`
- 管理用户：`root`
- 本地开发默认系统库：`analytics`
- AnalyticsHub 应用用户：`analytic`
- AnalyticsHub schema：`analytics`

本地密码不要写入提交历史。如果需要执行命令，把密码通过当前 shell 环境变量传入。

## 进入 psql

```bash
PG_CONTAINER=infra-postgres15
PGUSER=root
PGPASSWORD=replace-with-local-postgres-password

docker exec -it -e PGPASSWORD="$PGPASSWORD" "$PG_CONTAINER" psql -U "$PGUSER" -d postgres
```

连接 AnalyticsHub 系统库：

```bash
PG_CONTAINER=infra-postgres15
PGUSER=root
PGPASSWORD=replace-with-local-postgres-password

docker exec -it -e PGPASSWORD="$PGPASSWORD" "$PG_CONTAINER" psql -U "$PGUSER" -d analytics
```

## 快速检查

```bash
docker exec -it infra-postgres15 psql -U root -d analytics -c "SELECT 1;"
docker exec -it infra-postgres15 psql -U root -d analytics -c "\\dn"
docker exec -it infra-postgres15 psql -U root -d analytics -c "\\du"
```

检查 `analytic` 用户连接：

```bash
PGPASSWORD=replace-with-analytic-password \
psql -h 127.0.0.1 -p 5432 -U analytic -d analytics -c "SELECT 1;"
```

## 常用修复

重设本地 `analytic` 密码：

```bash
docker exec -it infra-postgres15 psql -U root -d analytics -c \
  "ALTER ROLE analytic WITH PASSWORD 'replace-with-new-local-password';"
```

创建 schema：

```bash
docker exec -it infra-postgres15 psql -U root -d analytics -c \
  "CREATE SCHEMA IF NOT EXISTS analytics AUTHORIZATION analytic;"
```

更多通用 SQL 命令见 `docs/数据库操作/PostgreSQL常用命令.md`。
