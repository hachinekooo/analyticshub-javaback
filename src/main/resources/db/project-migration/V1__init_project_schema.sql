-- ============================================================
-- AnalyticsHub project database schema
--
-- This migration is intentionally separate from the system database
-- migrations under db/migration. Each table prefix has its own Flyway
-- history, so multiple projects may share one PostgreSQL schema safely.
-- ============================================================

SET timezone = 'UTC';
SET search_path TO ${schema}, public;

CREATE TABLE ${tablePrefix}devices (
    id SERIAL CONSTRAINT ${tablePrefix}pk_devices PRIMARY KEY,
    device_id UUID NOT NULL,
    api_key VARCHAR(100) NOT NULL CONSTRAINT ${tablePrefix}uq_devices_api_key UNIQUE,
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

CREATE INDEX ${tablePrefix}ix_devices_device ON ${tablePrefix}devices(device_id);
CREATE INDEX ${tablePrefix}ix_devices_api_key ON ${tablePrefix}devices(api_key);
CREATE UNIQUE INDEX ${tablePrefix}ix_dev_project_device
    ON ${tablePrefix}devices(project_id, device_id);

CREATE TABLE ${tablePrefix}events (
    id BIGSERIAL CONSTRAINT ${tablePrefix}pk_events PRIMARY KEY,
    event_id VARCHAR(64) NOT NULL CONSTRAINT ${tablePrefix}uq_events_event_id UNIQUE,
    device_id UUID NOT NULL,
    user_id VARCHAR(128) NOT NULL,
    session_id UUID,
    event_type VARCHAR(100) NOT NULL,
    event_timestamp BIGINT NOT NULL,
    properties JSONB,
    project_id VARCHAR(50) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX ${tablePrefix}ix_events_device ON ${tablePrefix}events(device_id);
CREATE INDEX ${tablePrefix}ix_events_user ON ${tablePrefix}events(user_id);
CREATE INDEX ${tablePrefix}ix_events_type ON ${tablePrefix}events(event_type);
CREATE INDEX ${tablePrefix}ix_events_created ON ${tablePrefix}events(created_at DESC);
CREATE INDEX ${tablePrefix}ix_evt_project_device
    ON ${tablePrefix}events(project_id, device_id);
CREATE INDEX ${tablePrefix}ix_evt_project_created
    ON ${tablePrefix}events(project_id, created_at DESC);
CREATE INDEX ${tablePrefix}ix_events_properties
    ON ${tablePrefix}events USING gin(properties);

CREATE TABLE ${tablePrefix}sessions (
    id SERIAL CONSTRAINT ${tablePrefix}pk_sessions PRIMARY KEY,
    session_id UUID NOT NULL CONSTRAINT ${tablePrefix}uq_sessions_session_id UNIQUE,
    device_id UUID NOT NULL,
    user_id VARCHAR(128) NOT NULL,
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

CREATE INDEX ${tablePrefix}ix_sessions_device ON ${tablePrefix}sessions(device_id);
CREATE INDEX ${tablePrefix}ix_sessions_user ON ${tablePrefix}sessions(user_id);
CREATE INDEX ${tablePrefix}ix_sessions_created ON ${tablePrefix}sessions(created_at DESC);
CREATE INDEX ${tablePrefix}ix_ses_project_device
    ON ${tablePrefix}sessions(project_id, device_id);
CREATE INDEX ${tablePrefix}ix_sessions_start
    ON ${tablePrefix}sessions(session_start_time DESC);

CREATE TABLE ${tablePrefix}traffic_metrics (
    id BIGSERIAL CONSTRAINT ${tablePrefix}pk_traffic_metrics PRIMARY KEY,
    metric_id VARCHAR(64) NOT NULL CONSTRAINT ${tablePrefix}uq_traffic_metric_id UNIQUE,
    device_id UUID NOT NULL,
    user_id VARCHAR(128) NOT NULL,
    session_id UUID,
    metric_type VARCHAR(50) NOT NULL,
    page_path VARCHAR(255),
    referrer VARCHAR(255),
    metric_timestamp BIGINT NOT NULL,
    metadata JSONB,
    project_id VARCHAR(50) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX ${tablePrefix}ix_traffic_device ON ${tablePrefix}traffic_metrics(device_id);
CREATE INDEX ${tablePrefix}ix_traffic_user ON ${tablePrefix}traffic_metrics(user_id);
CREATE INDEX ${tablePrefix}ix_traffic_type ON ${tablePrefix}traffic_metrics(metric_type);
CREATE INDEX ${tablePrefix}ix_traffic_created ON ${tablePrefix}traffic_metrics(created_at DESC);
CREATE INDEX ${tablePrefix}ix_trf_project_device
    ON ${tablePrefix}traffic_metrics(project_id, device_id);
CREATE INDEX ${tablePrefix}ix_trf_project_created
    ON ${tablePrefix}traffic_metrics(project_id, created_at DESC);
CREATE INDEX ${tablePrefix}ix_traffic_page ON ${tablePrefix}traffic_metrics(page_path);
CREATE INDEX ${tablePrefix}ix_traffic_referrer ON ${tablePrefix}traffic_metrics(referrer);
CREATE INDEX ${tablePrefix}ix_traffic_metadata
    ON ${tablePrefix}traffic_metrics USING gin(metadata);

CREATE TABLE ${tablePrefix}counters (
    id BIGSERIAL CONSTRAINT ${tablePrefix}pk_counters PRIMARY KEY,
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
    CONSTRAINT ${tablePrefix}uq_counter_key UNIQUE (project_id, counter_key)
);

CREATE INDEX ${tablePrefix}ix_counters_updated
    ON ${tablePrefix}counters(project_id, updated_at DESC);

CREATE TABLE ${tablePrefix}privacy_requests (
    id BIGSERIAL CONSTRAINT ${tablePrefix}pk_privacy_requests PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL CONSTRAINT ${tablePrefix}uq_privacy_request_id UNIQUE,
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
    CONSTRAINT ${tablePrefix}ck_privacy_type
        CHECK (request_type IN ('EXPORT', 'DELETE')),
    CONSTRAINT ${tablePrefix}ck_privacy_processor
        CHECK (processor IN ('ANALYTICSHUB', 'POSTHOG')),
    CONSTRAINT ${tablePrefix}ck_privacy_status
        CHECK (status IN ('SUBMITTED', 'IN_PROGRESS', 'COMPLETED', 'REJECTED', 'CANCELLED'))
);

CREATE INDEX ${tablePrefix}ix_privacy_status
    ON ${tablePrefix}privacy_requests(project_id, status, requested_at DESC);
CREATE INDEX ${tablePrefix}ix_privacy_user
    ON ${tablePrefix}privacy_requests(project_id, user_id, requested_at DESC);
CREATE INDEX ${tablePrefix}ix_privacy_processor
    ON ${tablePrefix}privacy_requests(project_id, processor, requested_at DESC);
