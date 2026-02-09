-- ============================================================
-- Flyway Migration V2: Initialize Analytics Business Tables
-- 创建业务表（设备、事件、会话）
-- ============================================================

SET timezone = 'UTC';

-- 1. 设备注册表
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

CREATE INDEX idx_devices_device_id ON analytics_devices(device_id);
CREATE INDEX idx_devices_api_key ON analytics_devices(api_key);
CREATE UNIQUE INDEX idx_devices_project_device ON analytics_devices(project_id, device_id);

COMMENT ON TABLE analytics_devices IS '设备注册表';
COMMENT ON COLUMN analytics_devices.device_id IS '设备唯一标识（UUID）';
COMMENT ON COLUMN analytics_devices.api_key IS 'API密钥';
COMMENT ON COLUMN analytics_devices.secret_key IS '用于HMAC签名的密钥';

-- 2. 事件记录表
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

CREATE INDEX idx_events_device_id ON analytics_events(device_id);
CREATE INDEX idx_events_user_id ON analytics_events(user_id);
CREATE INDEX idx_events_event_type ON analytics_events(event_type);
CREATE INDEX idx_events_created_at ON analytics_events(created_at DESC);
CREATE INDEX idx_events_project_device ON analytics_events(project_id, device_id);
CREATE INDEX idx_events_project_created ON analytics_events(project_id, created_at DESC);
CREATE INDEX idx_events_properties ON analytics_events USING gin(properties);

COMMENT ON TABLE analytics_events IS '事件追踪记录表';
COMMENT ON COLUMN analytics_events.event_timestamp IS '事件发生时间戳（毫秒）';
COMMENT ON COLUMN analytics_events.properties IS '事件属性（JSON格式）';

-- 3. 会话记录表
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

CREATE INDEX idx_sessions_device_id ON analytics_sessions(device_id);
CREATE INDEX idx_sessions_user_id ON analytics_sessions(user_id);
CREATE INDEX idx_sessions_created_at ON analytics_sessions(created_at DESC);
CREATE INDEX idx_sessions_project_device ON analytics_sessions(project_id, device_id);
CREATE INDEX idx_sessions_start_time ON analytics_sessions(session_start_time DESC);

COMMENT ON TABLE analytics_sessions IS '用户会话记录表';
COMMENT ON COLUMN analytics_sessions.session_duration_ms IS '会话持续时间（毫秒）';

-- 4. 流量指标表（可选）
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

CREATE INDEX idx_traffic_device_id ON analytics_traffic_metrics(device_id);
CREATE INDEX idx_traffic_user_id ON analytics_traffic_metrics(user_id);
CREATE INDEX idx_traffic_type ON analytics_traffic_metrics(metric_type);
CREATE INDEX idx_traffic_created_at ON analytics_traffic_metrics(created_at DESC);
CREATE INDEX idx_traffic_project_device ON analytics_traffic_metrics(project_id, device_id);
CREATE INDEX idx_traffic_project_created ON analytics_traffic_metrics(project_id, created_at DESC);
CREATE INDEX idx_traffic_page_path ON analytics_traffic_metrics(page_path);
CREATE INDEX idx_traffic_referrer ON analytics_traffic_metrics(referrer);
CREATE INDEX idx_traffic_metadata ON analytics_traffic_metrics USING gin(metadata);

COMMENT ON TABLE analytics_traffic_metrics IS '流量指标记录表';

-- 5. 运营累计统计计数器（可选）
CREATE TABLE IF NOT EXISTS analytics_counters (
    id BIGSERIAL PRIMARY KEY,
    counter_key VARCHAR(100) NOT NULL,
    counter_value BIGINT NOT NULL DEFAULT 0,
    display_name VARCHAR(200),
    unit VARCHAR(50),
    is_public BOOLEAN DEFAULT FALSE,
    description TEXT,
    project_id VARCHAR(50) NOT NULL DEFAULT 'analytics-system',
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    CONSTRAINT uq_counters_project_key UNIQUE (project_id, counter_key)
);

CREATE INDEX IF NOT EXISTS idx_counters_project_updated ON analytics_counters(project_id, updated_at DESC);

COMMENT ON TABLE analytics_counters IS '运营累计统计计数器';
