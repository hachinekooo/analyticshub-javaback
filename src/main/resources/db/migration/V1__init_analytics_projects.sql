-- ============================================================
-- Flyway Migration V1: Initialize Analytics Projects Table
-- 分析系统项目配置表初始化
-- ============================================================

SET timezone = 'UTC';

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
    
    -- 添加约束
    CONSTRAINT chk_project_id_format CHECK (project_id ~ '^[a-z0-9_-]+$')
);

-- 添加索引
CREATE INDEX IF NOT EXISTS idx_projects_project_id ON analytics_projects(project_id);
CREATE INDEX IF NOT EXISTS idx_projects_is_active ON analytics_projects(is_active);

-- 添加注释
COMMENT ON TABLE analytics_projects IS '多项目配置表';
COMMENT ON COLUMN analytics_projects.project_id IS '项目唯一标识（小写字母、数字、下划线、横线）';
COMMENT ON COLUMN analytics_projects.table_prefix IS '数据库表前缀（避免与业务表名冲突）';
COMMENT ON COLUMN analytics_projects.db_password_encrypted IS '加密后的数据库密码';

-- 2. 创建更新时间触发器
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

-- 3. 插入分析系统自己的配置
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
