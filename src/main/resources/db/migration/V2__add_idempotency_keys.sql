-- 幂等键表：确保同一业务动作不会被重复写入
-- 客户端发送 idempotencyKey → 服务端 SHA-256 哈希后写入此表
-- 重复请求 → UNIQUE 约束冲突 → 返回已有 event_id，不重复写入事件表
CREATE TABLE IF NOT EXISTS analytics_idempotency_keys (
    project_id   VARCHAR(50)  NOT NULL,
    key_hash     VARCHAR(64)  NOT NULL,
    event_id     VARCHAR(64)  NOT NULL,
    created_at   TIMESTAMPTZ  DEFAULT NOW(),
    PRIMARY KEY (project_id, key_hash)
);
