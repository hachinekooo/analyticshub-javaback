# Docker PostgreSQL 操作指南

本文档专注于 Docker 环境下的 PostgreSQL 数据库操作，包括容器管理、数据库创建、用户管理以及容器内命令执行。

## 前提

- PostgreSQL 运行在 Docker 容器内
- 本机已安装并配置好 `docker` 命令

## 1. 找到 PostgreSQL 容器名

```bash
docker ps --format 'table {{.Names}}\t{{.Image}}\t{{.Ports}}\t{{.Status}}'
```

在输出里找到 PostgreSQL/pgvector 对应的容器名，例如：

- `infra-postgres15`

后续命令用 `${PG_CONTAINER}` 指代该容器名。

## 2. 连接到容器内的 psql

如果容器里使用密码认证，可通过环境变量传递 `PGPASSWORD`：

```bash
PG_CONTAINER=infra-postgres15
PGUSER=<your_db_user>
PGPASSWORD=<your_db_password>
docker exec -e PGPASSWORD="$PGPASSWORD" "$PG_CONTAINER" psql -U "$PGUSER" -d postgres
```

常用只读检查（不进入交互）：

```bash
PG_CONTAINER=infra-postgres15
PGUSER=<your_db_user>
PGPASSWORD=<your_db_password>
docker exec -e PGPASSWORD="$PGPASSWORD" "$PG_CONTAINER" psql -U "$PGUSER" -d postgres -c "\\l"
```

## 3. 创建/重建本地 dev 测试库

典型的 dev 测试库名：

- `analytics_flyway_test`

### 3.1 重建（会删除现有库，慎用）

```bash
PG_CONTAINER=infra-postgres15
PGUSER=<your_db_user>
PGPASSWORD=<your_db_password>
DB=analytics_flyway_test

docker exec -e PGPASSWORD="$PGPASSWORD" "$PG_CONTAINER" psql -U "$PGUSER" -d postgres -c "DROP DATABASE IF EXISTS ${DB};"
docker exec -e PGPASSWORD="$PGPASSWORD" "$PG_CONTAINER" psql -U "$PGUSER" -d postgres -c "CREATE DATABASE ${DB};"
```

### 3.2 验证库是否为空（可选）

```bash
PG_CONTAINER=infra-postgres15
PGUSER=<your_db_user>
PGPASSWORD=<your_db_password>
DB=analytics_flyway_test

docker exec -e PGPASSWORD="$PGPASSWORD" "$PG_CONTAINER" psql -U "$PGUSER" -d "$DB" -c "\\dt"
```

若输出 `Did not find any relations.`，代表当前库里没有任何表。

## 3.3 为项目创建数据库与用户（管理端项目配置前置条件）

管理端创建项目**不会自动创建数据库/用户**，只会保存连接信息。若你在管理端填写了：

- `dbName=memobox`
- `dbUser=memobox`
- `dbPassword=memobox`
- `tablePrefix=analytics_`

那么必须先在 PostgreSQL 里手动创建对应的数据库和用户，否则会连接失败。

示例（在 Docker 容器里执行）：

```bash
PG_CONTAINER=infra-postgres15
PGUSER=root
PGPASSWORD=root

# 创建用户与数据库（如已存在可忽略错误）
docker exec -e PGPASSWORD="$PGPASSWORD" "$PG_CONTAINER" psql -U "$PGUSER" -d postgres -c "CREATE ROLE memobox LOGIN PASSWORD 'memobox';"
docker exec -e PGPASSWORD="$PGPASSWORD" "$PG_CONTAINER" psql -U "$PGUSER" -d postgres -c "CREATE DATABASE memobox OWNER memobox;"
```

验证连接：

```bash
PG_CONTAINER=infra-postgres15
PGPASSWORD=your_db_password
docker exec -e PGPASSWORD="$PGPASSWORD" "$PG_CONTAINER" psql -U memobox -d memobox -c "SELECT 1;"
```

随后再调用管理端初始化接口：

```
POST /api/admin/projects/{id}/init
```

该接口会在项目数据库里创建 `devices/events/sessions/traffic_metrics` 等表（模板见 `src/main/resources/db/project-init.sql`）。

## 4. 容器管理常用命令

### 4.1 查看容器状态

```bash
docker ps --format 'table {{.Names}}\t{{.Image}}\t{{.Ports}}\t{{.Status}}' | grep -E "(postgres|pg)"
```

### 4.2 启动/停止容器

```bash
# 启动容器
docker start infra-postgres15

# 停止容器
docker stop infra-postgres15

# 重启容器
docker restart infra-postgres15
```

### 4.3 查看容器日志

```bash
docker logs infra-postgres15

# 实时查看日志
docker logs -f infra-postgres15
```

### 4.4 进入容器终端

```bash
docker exec -it infra-postgres15 bash
```

## 5. 数据库备份与恢复

### 5.1 备份数据库

```bash
PG_CONTAINER=infra-postgres15
PGUSER=<your_db_user>
PGPASSWORD=<your_db_password>
DB=<database_name>

# 备份到文件
docker exec -e PGPASSWORD="$PGPASSWORD" "$PG_CONTAINER" pg_dump -U "$PGUSER" -d "$DB" > backup_$(date +%Y%m%d).sql

# 或者备份到容器内再复制出来
docker exec -e PGPASSWORD="$PGPASSWORD" "$PG_CONTAINER" pg_dump -U "$PGUSER" -d "$DB" > /tmp/backup.sql
docker cp "$PG_CONTAINER":/tmp/backup.sql ./
```

### 5.2 恢复数据库

```bash
PG_CONTAINER=infra-postgres15
PGUSER=<your_db_user>
PGPASSWORD=<your_db_password>
DB=<database_name>

# 从文件恢复
docker exec -i -e PGPASSWORD="$PGPASSWORD" "$PG_CONTAINER" psql -U "$PGUSER" -d "$DB" < backup_file.sql

# 或者先复制到容器内再恢复
docker cp backup_file.sql "$PG_CONTAINER":/tmp/backup.sql
docker exec -e PGPASSWORD="$PGPASSWORD" "$PG_CONTAINER" psql -U "$PGUSER" -d "$DB" -f /tmp/backup.sql
```

## 6. 网络与端口配置

### 6.1 查看容器网络信息

```bash
docker inspect infra-postgres15 | grep -A 10 "NetworkSettings"
```

### 6.2 端口映射检查

```bash
docker port infra-postgres15
```

### 6.3 自定义网络创建（可选）

```bash
# 创建自定义网络
docker network create analytics-network

# 将容器连接到自定义网络
docker network connect analytics-network infra-postgres15
```

