---
title: PostgreSQL 常用命令
type: database-reference
status: current
audience: operator, backend
scope: 本地排障和手动 PostgreSQL 操作
agent_notes: 生产初始化优先使用 ops/analyticshub bootstrap；维护者 Docker 环境见 docs/本地开发/DOCKER_POSTGRES.md
---

# PostgreSQL 常用命令

本文是本地排障和手动数据库操作参考。生产初始化优先使用：

```bash
sudo bash ops/analyticshub bootstrap
```

维护者本机 Docker PostgreSQL 连接方式见 `docs/本地开发/DOCKER_POSTGRES.md`。

AnalyticsHub 默认系统库口径：

- 数据库：`analytics`
- 用户：`analytic`
- Schema：`analytics`

## 进入 psql

本机 PostgreSQL：

```bash
sudo -iu postgres psql
```

Docker PostgreSQL 示例：

```bash
PG_CONTAINER=your-postgres-container
PGUSER=postgres
PGPASSWORD=replace-with-postgres-password
docker exec -it -e PGPASSWORD="$PGPASSWORD" "$PG_CONTAINER" psql -U "$PGUSER" -d postgres
```

## 用户操作

查看用户：

```sql
\du
```

查看 AnalyticsHub 用户：

```sql
SELECT usename FROM pg_user WHERE usename = 'analytic';
```

创建或修改用户：

```sql
CREATE ROLE analytic LOGIN PASSWORD 'replace-with-strong-password';
ALTER ROLE analytic WITH PASSWORD 'replace-with-new-password';
```

## 数据库和 Schema

创建系统库：

```sql
CREATE DATABASE analytics OWNER analytic ENCODING 'UTF8' TEMPLATE template0;
```

创建业务 schema：

```sql
\c analytics
CREATE SCHEMA IF NOT EXISTS analytics AUTHORIZATION analytic;
ALTER DATABASE analytics OWNER TO analytic;
REVOKE CONNECT ON DATABASE analytics FROM PUBLIC;
GRANT CONNECT ON DATABASE analytics TO analytic;
GRANT USAGE, CREATE ON SCHEMA analytics TO analytic;
```

查看数据库：

```sql
\l
\l analytics
```

## 删除数据库

只在确认可以清理本地或测试数据时使用：

```sql
SELECT pg_terminate_backend(pid)
FROM pg_stat_activity
WHERE datname = 'analytics' AND pid <> pg_backend_pid();

DROP DATABASE IF EXISTS analytics;
```

## 备注

- `pg_stat_activity` 使用 `pid` 列，不是 `backend_pid`。
- 生产密码、Admin Token、SMTP 密码和 2FA Secret 不应写入仓库文档或提交历史。
- 接入方业务项目应使用自己的数据库/用户/schema，不要复用 AnalyticsHub 系统库承载业务采集表。
