-- ============================================================
-- Analytics Hub - System Database Schema
-- 系统库仅用于项目管理，不承载业务采集数据
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

    -- 目标项目数据库配置
    db_host VARCHAR(255) NOT NULL,
    db_port INTEGER NOT NULL DEFAULT 5432,
    db_name VARCHAR(100) NOT NULL,
    db_schema VARCHAR(63) NOT NULL DEFAULT 'analytics',
    db_user VARCHAR(100) NOT NULL,
    db_password_encrypted TEXT,

    -- 项目业务表前缀（在项目数据库内使用）
    table_prefix VARCHAR(50) DEFAULT 'analytics_',

    -- 项目状态
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),

    CONSTRAINT chk_project_id_format CHECK (project_id ~ '^[a-z0-9_-]+$')
);

CREATE INDEX IF NOT EXISTS idx_projects_project_id ON analytics_projects(project_id);
CREATE INDEX IF NOT EXISTS idx_projects_is_active ON analytics_projects(is_active);

COMMENT ON TABLE analytics_projects IS '多项目配置表（系统库）';

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
