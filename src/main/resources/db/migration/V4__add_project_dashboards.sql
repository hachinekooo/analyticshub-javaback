-- Project-scoped dashboard definitions live in the AnalyticsHub system database.
-- Definitions contain declarative widget configuration only; never HTML, JavaScript or SQL.
CREATE TABLE analytics_dashboards (
    id BIGSERIAL PRIMARY KEY,
    project_id VARCHAR(50) NOT NULL,
    dashboard_key VARCHAR(64) NOT NULL,
    display_name JSONB NOT NULL,
    description TEXT,
    schema_version INTEGER NOT NULL,
    definition JSONB NOT NULL,
    revision BIGINT NOT NULL DEFAULT 1,
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_dashboard_project
        FOREIGN KEY (project_id) REFERENCES analytics_projects(project_id) ON DELETE CASCADE,
    CONSTRAINT uq_dashboard_project_key UNIQUE (project_id, dashboard_key),
    CONSTRAINT ck_dashboard_schema_version CHECK (schema_version > 0),
    CONSTRAINT ck_dashboard_revision CHECK (revision > 0),
    CONSTRAINT ck_dashboard_key CHECK (dashboard_key ~ '^[a-z0-9][a-z0-9._-]{0,63}$'),
    CONSTRAINT ck_dashboard_display_name CHECK (
        jsonb_typeof(display_name) = 'object' AND display_name <> '{}'::jsonb
    ),
    CONSTRAINT ck_dashboard_definition CHECK (
        jsonb_typeof(definition) = 'object' AND octet_length(definition::text) <= 262144
    ),
    CONSTRAINT ck_dashboard_description CHECK (
        description IS NULL OR char_length(description) <= 1000
    ),
    CONSTRAINT ck_dashboard_default_active CHECK (NOT is_default OR is_active)
);

CREATE UNIQUE INDEX ux_dashboard_default
    ON analytics_dashboards(project_id)
    WHERE is_default = TRUE AND is_active = TRUE;

CREATE INDEX ix_dashboard_project_active
    ON analytics_dashboards(project_id, is_active, updated_at DESC);

CREATE TRIGGER update_dashboards_updated_at
    BEFORE UPDATE ON analytics_dashboards
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();
