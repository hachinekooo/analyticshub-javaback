-- ============================================================
-- Project Init Script (Template)
-- 使用 {{PREFIX}} 作为表前缀占位符
-- 该脚本运行在“项目自己的数据库”中
-- ============================================================

SET timezone = 'UTC';

-- 1. 设备注册表
CREATE TABLE IF NOT EXISTS {{PREFIX}}devices (
    id SERIAL PRIMARY KEY,
    device_id UUID NOT NULL,
    api_key VARCHAR(100) NOT NULL UNIQUE,
    secret_key VARCHAR(100) NOT NULL,
    device_model VARCHAR(100),
    os_version VARCHAR(50),
    app_version VARCHAR(50),
    project_id VARCHAR(50) NOT NULL,
    is_banned BOOLEAN DEFAULT FALSE,
    ban_reason TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    last_active_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_devices_device_id ON {{PREFIX}}devices(device_id);
CREATE INDEX IF NOT EXISTS idx_devices_api_key ON {{PREFIX}}devices(api_key);
CREATE UNIQUE INDEX IF NOT EXISTS idx_devices_project_device ON {{PREFIX}}devices(project_id, device_id);

-- 2. 事件记录表
CREATE TABLE IF NOT EXISTS {{PREFIX}}events (
    id BIGSERIAL PRIMARY KEY,
    event_id VARCHAR(64) NOT NULL UNIQUE,
    device_id UUID NOT NULL,
    user_id VARCHAR(32) NOT NULL,
    session_id UUID,
    event_type VARCHAR(100) NOT NULL,
    event_timestamp BIGINT NOT NULL,
    properties JSONB,
    project_id VARCHAR(50) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_events_device_id ON {{PREFIX}}events(device_id);
CREATE INDEX IF NOT EXISTS idx_events_user_id ON {{PREFIX}}events(user_id);
CREATE INDEX IF NOT EXISTS idx_events_event_type ON {{PREFIX}}events(event_type);
CREATE INDEX IF NOT EXISTS idx_events_created_at ON {{PREFIX}}events(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_events_project_device ON {{PREFIX}}events(project_id, device_id);
CREATE INDEX IF NOT EXISTS idx_events_project_created ON {{PREFIX}}events(project_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_events_properties ON {{PREFIX}}events USING gin(properties);

-- 3. 会话记录表
CREATE TABLE IF NOT EXISTS {{PREFIX}}sessions (
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
    project_id VARCHAR(50) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_sessions_device_id ON {{PREFIX}}sessions(device_id);
CREATE INDEX IF NOT EXISTS idx_sessions_user_id ON {{PREFIX}}sessions(user_id);
CREATE INDEX IF NOT EXISTS idx_sessions_created_at ON {{PREFIX}}sessions(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_sessions_project_device ON {{PREFIX}}sessions(project_id, device_id);
CREATE INDEX IF NOT EXISTS idx_sessions_start_time ON {{PREFIX}}sessions(session_start_time DESC);

-- 4. 流量指标表（可选）
CREATE TABLE IF NOT EXISTS {{PREFIX}}traffic_metrics (
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
    project_id VARCHAR(50) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_traffic_device_id ON {{PREFIX}}traffic_metrics(device_id);
CREATE INDEX IF NOT EXISTS idx_traffic_user_id ON {{PREFIX}}traffic_metrics(user_id);
CREATE INDEX IF NOT EXISTS idx_traffic_type ON {{PREFIX}}traffic_metrics(metric_type);
CREATE INDEX IF NOT EXISTS idx_traffic_created_at ON {{PREFIX}}traffic_metrics(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_traffic_project_device ON {{PREFIX}}traffic_metrics(project_id, device_id);
CREATE INDEX IF NOT EXISTS idx_traffic_project_created ON {{PREFIX}}traffic_metrics(project_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_traffic_page_path ON {{PREFIX}}traffic_metrics(page_path);
CREATE INDEX IF NOT EXISTS idx_traffic_referrer ON {{PREFIX}}traffic_metrics(referrer);
CREATE INDEX IF NOT EXISTS idx_traffic_metadata ON {{PREFIX}}traffic_metrics USING gin(metadata);

-- 5. 运营累计统计计数器（可选）
CREATE TABLE IF NOT EXISTS {{PREFIX}}counters (
    id BIGSERIAL PRIMARY KEY,
    counter_key VARCHAR(100) NOT NULL,
    counter_value BIGINT NOT NULL DEFAULT 0,
    display_name JSONB,
    unit JSONB,
    event_trigger JSONB,
    is_public BOOLEAN DEFAULT FALSE,
    description TEXT,
    project_id VARCHAR(50) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    CONSTRAINT uq_counters_project_key UNIQUE (project_id, counter_key)
);

CREATE INDEX IF NOT EXISTS idx_counters_project_updated ON {{PREFIX}}counters(project_id, updated_at DESC);

-- 6. 隐私请求工单表（人工导出/删除流程）
CREATE TABLE IF NOT EXISTS {{PREFIX}}privacy_requests (
    id BIGSERIAL PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL UNIQUE,
    project_id VARCHAR(50) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    device_id UUID NOT NULL,
    request_type VARCHAR(16) NOT NULL,
    processor VARCHAR(32) NOT NULL,
    source VARCHAR(32) NOT NULL DEFAULT 'APP_SETTINGS',
    status VARCHAR(32) NOT NULL DEFAULT 'SUBMITTED',
    contact_email VARCHAR(255),
    requester_note TEXT,
    operator VARCHAR(64),
    operator_note TEXT,
    result_payload JSONB,
    metadata JSONB,
    requested_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    processed_at TIMESTAMP WITH TIME ZONE,
    closed_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_privacy_request_type CHECK (request_type IN ('EXPORT', 'DELETE')),
    CONSTRAINT chk_privacy_processor CHECK (processor IN ('ANALYTICSHUB', 'POSTHOG')),
    CONSTRAINT chk_privacy_status CHECK (status IN ('SUBMITTED', 'IN_PROGRESS', 'COMPLETED', 'REJECTED', 'CANCELLED'))
);

CREATE INDEX IF NOT EXISTS idx_privacy_project_status_requested
    ON {{PREFIX}}privacy_requests(project_id, status, requested_at DESC);
CREATE INDEX IF NOT EXISTS idx_privacy_project_user_requested
    ON {{PREFIX}}privacy_requests(project_id, user_id, requested_at DESC);
CREATE INDEX IF NOT EXISTS idx_privacy_project_processor_requested
    ON {{PREFIX}}privacy_requests(project_id, processor, requested_at DESC);
