-- Project-owned analytics semantics stay in the system database. Event facts remain in project databases.

CREATE TABLE analytics_property_definitions (
    project_id VARCHAR(50) NOT NULL,
    property_key VARCHAR(80) NOT NULL,
    display_name JSONB NOT NULL,
    data_type VARCHAR(16) NOT NULL,
    description VARCHAR(1000),
    allowed_values JSONB,
    is_filterable BOOLEAN NOT NULL DEFAULT FALSE,
    is_groupable BOOLEAN NOT NULL DEFAULT FALSE,
    is_journey_key BOOLEAN NOT NULL DEFAULT FALSE,
    is_sensitive BOOLEAN NOT NULL DEFAULT FALSE,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_property_definitions PRIMARY KEY (project_id, property_key),
    CONSTRAINT fk_property_definitions_project
        FOREIGN KEY (project_id) REFERENCES analytics_projects(project_id) ON DELETE CASCADE,
    CONSTRAINT ck_property_definitions_key
        CHECK (property_key ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,79}$'),
    CONSTRAINT ck_property_definitions_type
        CHECK (data_type IN ('STRING', 'BOOLEAN', 'INTEGER', 'NUMBER')),
    CONSTRAINT ck_property_definitions_display_name
        CHECK (jsonb_typeof(display_name) = 'object' AND display_name <> '{}'::jsonb),
    CONSTRAINT ck_property_definitions_allowed_values
        CHECK (allowed_values IS NULL OR jsonb_typeof(allowed_values) = 'array'),
    CONSTRAINT ck_property_definitions_sensitive_usage
        CHECK (NOT is_sensitive OR (NOT is_filterable AND NOT is_groupable AND NOT is_journey_key))
);

CREATE INDEX ix_property_definitions_capabilities
    ON analytics_property_definitions(project_id, is_active, is_filterable, is_groupable, is_journey_key);

CREATE TRIGGER update_property_definitions_updated_at
    BEFORE UPDATE ON analytics_property_definitions
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TABLE analytics_metric_definitions (
    project_id VARCHAR(50) NOT NULL,
    metric_key VARCHAR(100) NOT NULL,
    display_name JSONB NOT NULL,
    metric_type VARCHAR(32) NOT NULL,
    definition JSONB NOT NULL,
    description VARCHAR(1000),
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_metric_definitions PRIMARY KEY (project_id, metric_key),
    CONSTRAINT fk_metric_definitions_project
        FOREIGN KEY (project_id) REFERENCES analytics_projects(project_id) ON DELETE CASCADE,
    CONSTRAINT ck_metric_definitions_key CHECK (metric_key ~ '^[a-z0-9][a-z0-9._-]{0,99}$'),
    CONSTRAINT ck_metric_definitions_type
        CHECK (metric_type IN ('EVENT_COUNT', 'UNIQUE_ACTORS', 'FUNNEL_CONVERSION', 'RETENTION')),
    CONSTRAINT ck_metric_definitions_display_name
        CHECK (jsonb_typeof(display_name) = 'object' AND display_name <> '{}'::jsonb),
    CONSTRAINT ck_metric_definitions_definition
        CHECK (jsonb_typeof(definition) = 'object' AND octet_length(definition::text) <= 32768)
);

CREATE INDEX ix_metric_definitions_active
    ON analytics_metric_definitions(project_id, is_active, metric_key);

CREATE TRIGGER update_metric_definitions_updated_at
    BEFORE UPDATE ON analytics_metric_definitions
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TABLE analytics_analysis_packs (
    project_id VARCHAR(50) NOT NULL,
    pack_key VARCHAR(64) NOT NULL,
    pack_version INTEGER NOT NULL,
    display_name JSONB NOT NULL,
    manifest JSONB NOT NULL,
    checksum_sha256 CHAR(64) NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_analysis_packs PRIMARY KEY (project_id, pack_key),
    CONSTRAINT fk_analysis_packs_project
        FOREIGN KEY (project_id) REFERENCES analytics_projects(project_id) ON DELETE CASCADE,
    CONSTRAINT ck_analysis_packs_key CHECK (pack_key ~ '^[a-z0-9][a-z0-9._-]{0,63}$'),
    CONSTRAINT ck_analysis_packs_version CHECK (pack_version > 0),
    CONSTRAINT ck_analysis_packs_display_name
        CHECK (jsonb_typeof(display_name) = 'object' AND display_name <> '{}'::jsonb),
    CONSTRAINT ck_analysis_packs_manifest
        CHECK (jsonb_typeof(manifest) = 'object' AND octet_length(manifest::text) <= 524288),
    CONSTRAINT ck_analysis_packs_checksum CHECK (checksum_sha256 ~ '^[0-9a-f]{64}$')
);

CREATE TRIGGER update_analysis_packs_updated_at
    BEFORE UPDATE ON analytics_analysis_packs
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TABLE analytics_analysis_pack_audits (
    id BIGSERIAL PRIMARY KEY,
    project_id VARCHAR(50) NOT NULL,
    pack_key VARCHAR(64) NOT NULL,
    pack_version INTEGER NOT NULL,
    display_name JSONB NOT NULL,
    manifest JSONB NOT NULL,
    checksum_sha256 CHAR(64) NOT NULL,
    operation VARCHAR(16) NOT NULL,
    applied_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_analysis_pack_audits_project
        FOREIGN KEY (project_id) REFERENCES analytics_projects(project_id) ON DELETE CASCADE,
    CONSTRAINT uq_analysis_pack_audits_version UNIQUE (project_id, pack_key, pack_version),
    CONSTRAINT ck_analysis_pack_audits_display_name
        CHECK (jsonb_typeof(display_name) = 'object' AND display_name <> '{}'::jsonb),
    CONSTRAINT ck_analysis_pack_audits_manifest
        CHECK (jsonb_typeof(manifest) = 'object' AND octet_length(manifest::text) <= 524288),
    CONSTRAINT ck_analysis_pack_audits_operation CHECK (operation IN ('IMPORT', 'UPDATE', 'DEACTIVATE'))
);

CREATE INDEX ix_analysis_pack_audits_project
    ON analytics_analysis_pack_audits(project_id, applied_at DESC);

COMMENT ON TABLE analytics_property_definitions IS
    'Project-scoped allowlist and display semantics for top-level analytics properties';
COMMENT ON TABLE analytics_analysis_packs IS
    'Validated declarative project configuration; manifests must never contain SQL, scripts or remote URLs';
