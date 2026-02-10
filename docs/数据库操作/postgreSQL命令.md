## 如果你是 docker

这样进入交互式操作**

```
PG_CONTAINER=infra-postgres15
PGUSER=root
PGPASSWORD=root
docker exec -it -e PGPASSWORD="$PGPASSWORD" "$PG_CONTAINER" psql -U "$PGUSER" -d postgres
```

**1. 断开数据库连接并删除数据库：**
```sql
-- 断开所有连接到指定数据库的会话（不包括当前连接）
SELECT pg_terminate_backend(pid) 
FROM pg_stat_activity 
WHERE datname = '数据库名' AND pid <> pg_backend_pid();

-- 删除数据库
DROP DATABASE IF EXISTS 数据库名;
```

## 用户操作

查看所有用户

```sql
\du
```

精准查询 analytics 用户
```sql
SELECT analytics FROM pg_user WHERE usename = 'analytics';
```

创建用户

```
CREATE ROLE analytics WITH
LOGIN
PASSWORD 'analytics';
```

修改用户的密码：
```sql
ALTER ROLE analytics WITH PASSWORD '你的新密码';
```
## 数据库操作

创建数据库（UTF8编码，en_US.UTF-8排序规则）

```sql
-- 创建数据库并指定所有者
CREATE DATABASE analytics
    WITH
    OWNER = analytics
    ENCODING = 'UTF8'
    LC_COLLATE = 'en_US.utf8'  -- 和模板库一致的小写utf8
    LC_CTYPE = 'en_US.utf8'
    TEMPLATE = template0;      -- 关键：指定template0模板
```

查看数据库信息

```sql
-- 在psql命令行中使用
\l 数据库名     -- 查看特定数据库信息
\l             -- 查看所有数据库
```

**关键点：**
- `pg_stat_activity` 视图中使用 `pid` 列（不是 `backend_pid`）
- 创建数据库时要确保LC_COLLATE和LC_CTYPE与系统支持的locale一致
- 删除数据库前需要断开所有活动连接