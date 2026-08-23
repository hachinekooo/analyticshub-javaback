-- 管理端用户旅程需要两类 actor 查询：精确条件服务事件明细，lower 条件兼容
-- 历史 UUID 大小写。属性元数据在写入时生成，避免旅程候选查询批量解压 JSONB。
ALTER TABLE ${tablePrefix}events
    ADD COLUMN properties_size_bytes INTEGER,
    ADD COLUMN identity_scope VARCHAR(64);

CREATE INDEX ${tablePrefix}ix_evt_proj_user_ts
    ON ${tablePrefix}events(project_id, user_id, event_timestamp DESC);

CREATE INDEX ${tablePrefix}ix_evt_norm_usr_ts
    ON ${tablePrefix}events(project_id, lower(user_id), event_timestamp DESC);

-- 仅包含 V8 升级前尚未回填的旧行；回填完成后索引自然变为空，
-- 新写入事件因同步携带元数据不会进入该索引。
CREATE INDEX ${tablePrefix}ix_evt_meta_pending
    ON ${tablePrefix}events(project_id, id)
    WHERE properties_size_bytes IS NULL;
