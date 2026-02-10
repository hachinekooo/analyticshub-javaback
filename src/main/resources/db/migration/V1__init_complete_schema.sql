-- ============================================================
-- Analytics Hub - Complete Database Schema
-- 一次性初始化所有表结构
-- ============================================================

SET timezone = 'UTC';

-- ============================================================
-- Part 1: 系统配置表
-- ============================================================

-- 1. 项目配置表（核心表）
CREATE TABLE IF NOT EXISTS analytics_projects (
    id SERIAL PRIMARY KEY,
    project_id VARCHAR(50) NOT NULL UNIQUE,
    project_name VARCHAR(100) NOT NULL,
    
    -- 数据库配置
    db_host VARCHAR(255) NOT NULL,
    db_port INTEGER NOT NULL DEFAULT 5432,
    db_name VARCHAR(100) NOT NULL,
    db_user VARCHAR(100) NOT NULL,
    db_password_encrypted TEXT,
    
    -- 表前缀（避免与业务表名冲突）
    table_prefix VARCHAR(50) DEFAULT 'analytics_',
    
    -- 项目状态
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    
    CONSTRAINT chk_project_id_format CHECK (project_id ~ '^[a-z0-9_-]+$')
);

CREATE INDEX IF NOT EXISTS idx_projects_project_id ON analytics_projects(project_id);
CREATE INDEX IF NOT EXISTS idx_projects_is_active ON analytics_projects(is_active);

COMMENT ON TABLE analytics_projects IS '多项目配置表';

-- 更新时间触发器
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS update_projects_updated_at ON analytics_projects;
CREATE TRIGGER update_projects_updated_at
    BEFORE UPDATE ON analytics_projects
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

-- 插入系统自己的配置
INSERT INTO analytics_projects (
    project_id, 
    project_name, 
    db_host, 
    db_port, 
    db_name, 
    db_user,
    table_prefix
) VALUES (
    'analytics-system',
    'Analytics System',
    'localhost',
    5432,
    'analytics',
    'root',
    'analytics_'
) ON CONFLICT (project_id) DO NOTHING;

-- ============================================================
-- Part 2: 业务表（analytics-system 项目）
-- ============================================================

-- 2. 设备注册表
CREATE TABLE IF NOT EXISTS analytics_devices (
    id SERIAL PRIMARY KEY,
    device_id UUID NOT NULL,
    api_key VARCHAR(100) NOT NULL UNIQUE,
    secret_key VARCHAR(100) NOT NULL,
    device_model VARCHAR(100),
    os_version VARCHAR(50),
    app_version VARCHAR(50),
    project_id VARCHAR(50) NOT NULL DEFAULT 'analytics-system',
    is_banned BOOLEAN DEFAULT FALSE,
    ban_reason TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    last_active_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_devices_device_id ON analytics_devices(device_id);
CREATE INDEX IF NOT EXISTS idx_devices_api_key ON analytics_devices(api_key);
CREATE UNIQUE INDEX IF NOT EXISTS idx_devices_project_device ON analytics_devices(project_id, device_id);

COMMENT ON TABLE analytics_devices IS '设备注册表';

-- 3. 事件记录表
CREATE TABLE IF NOT EXISTS analytics_events (
    id BIGSERIAL PRIMARY KEY,
    event_id VARCHAR(64) NOT NULL UNIQUE,
    device_id UUID NOT NULL,
    user_id VARCHAR(32) NOT NULL,
    session_id UUID,
    event_type VARCHAR(100) NOT NULL,
    event_timestamp BIGINT NOT NULL,
    properties JSONB,
    project_id VARCHAR(50) NOT NULL DEFAULT 'analytics-system',
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_events_device_id ON analytics_events(device_id);
CREATE INDEX IF NOT EXISTS idx_events_user_id ON analytics_events(user_id);
CREATE INDEX IF NOT EXISTS idx_events_event_type ON analytics_events(event_type);
CREATE INDEX IF NOT EXISTS idx_events_created_at ON analytics_events(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_events_project_device ON analytics_events(project_id, device_id);
CREATE INDEX IF NOT EXISTS idx_events_project_created ON analytics_events(project_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_events_properties ON analytics_events USING gin(properties);

COMMENT ON TABLE analytics_events IS '事件追踪记录表';

-- 4. 会话记录表
CREATE TABLE IF NOT EXISTS analytics_sessions (
    id SERIAL PRIMARY KEY,
    session_id UUID NOT NULL UNIQUE,
    device_id UUID NOT NULL,
    user_id VARCHAR(32) NOT NULL,
    session_start_time TIMESTAMP WITH TIME ZONE NOT NULL,
    session_duration_ms BIGINT DEFAULT 0,
    device_model VARCHAR(100),
    os_version VARCHAR(50),
    app_version VARCHAR(50),
    build_number VARCHAR(50),
    screen_count INTEGER DEFAULT 0,
    event_count INTEGER DEFAULT 0,
    project_id VARCHAR(50) NOT NULL DEFAULT 'analytics-system',
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_sessions_device_id ON analytics_sessions(device_id);
CREATE INDEX IF NOT EXISTS idx_sessions_user_id ON analytics_sessions(user_id);
CREATE INDEX IF NOT EXISTS idx_sessions_created_at ON analytics_sessions(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_sessions_project_device ON analytics_sessions(project_id, device_id);
CREATE INDEX IF NOT EXISTS idx_sessions_start_time ON analytics_sessions(session_start_time DESC);

COMMENT ON TABLE analytics_sessions IS '用户会话记录表';

-- 5. 流量指标表
CREATE TABLE IF NOT EXISTS analytics_traffic_metrics (
    id BIGSERIAL PRIMARY KEY,
    metric_id VARCHAR(64) NOT NULL UNIQUE,
    device_id UUID NOT NULL,
    user_id VARCHAR(32) NOT NULL,
    session_id UUID,
    metric_type VARCHAR(50) NOT NULL,
    page_path VARCHAR(255),
    referrer VARCHAR(255),
    metric_timestamp BIGINT NOT NULL,
    metadata JSONB,
    project_id VARCHAR(50) NOT NULL DEFAULT 'analytics-system',
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_traffic_device_id ON analytics_traffic_metrics(device_id);
CREATE INDEX IF NOT EXISTS idx_traffic_user_id ON analytics_traffic_metrics(user_id);
CREATE INDEX IF NOT EXISTS idx_traffic_type ON analytics_traffic_metrics(metric_type);
CREATE INDEX IF NOT EXISTS idx_traffic_created_at ON analytics_traffic_metrics(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_traffic_project_device ON analytics_traffic_metrics(project_id, device_id);
CREATE INDEX IF NOT EXISTS idx_traffic_project_created ON analytics_traffic_metrics(project_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_traffic_page_path ON analytics_traffic_metrics(page_path);
CREATE INDEX IF NOT EXISTS idx_traffic_referrer ON analytics_traffic_metrics(referrer);
CREATE INDEX IF NOT EXISTS idx_traffic_metadata ON analytics_traffic_metrics USING gin(metadata);

COMMENT ON TABLE analytics_traffic_metrics IS '流量指标记录表';

-- 6. 运营累计统计计数器
CREATE TABLE IF NOT EXISTS analytics_counters (
    id BIGSERIAL PRIMARY KEY,
    counter_key VARCHAR(100) NOT NULL,
    counter_value BIGINT NOT NULL DEFAULT 0,
    display_name JSONB,    -- 支持多语言: {"zh": "累计寄信", "en": "Total Letters"}
    unit JSONB,            -- 支持多语言: {"zh": "封", "en": "Letters"}
    event_trigger JSONB,   -- 自动化规则: {"event_type": "send_letter", "conditions": {"status": "success"}}
    is_public BOOLEAN DEFAULT FALSE,
    description TEXT,
    project_id VARCHAR(50) NOT NULL DEFAULT 'analytics-system',
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    CONSTRAINT uq_counters_project_key UNIQUE (project_id, counter_key)
);

CREATE INDEX IF NOT EXISTS idx_counters_project_updated ON analytics_counters(project_id, updated_at DESC);

COMMENT ON TABLE analytics_counters IS '运营累计统计计数器（支持自动化触发与国际化）';
