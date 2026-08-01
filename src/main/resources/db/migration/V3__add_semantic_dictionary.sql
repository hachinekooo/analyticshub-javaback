-- Semantic dictionary metadata belongs to the AnalyticsHub system database.
-- Raw analytics events remain in each project's own database.

CREATE TABLE analytics_semantic_definitions (
    project_id VARCHAR(50) NOT NULL,
    source_kind VARCHAR(32) NOT NULL,
    semantic_key VARCHAR(100) NOT NULL,
    display_name JSONB NOT NULL,
    category VARCHAR(100),
    description VARCHAR(1000),
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_semantic_definitions
        PRIMARY KEY (project_id, source_kind, semantic_key),
    CONSTRAINT fk_semantic_definitions_project
        FOREIGN KEY (project_id) REFERENCES analytics_projects(project_id) ON DELETE CASCADE,
    CONSTRAINT chk_semantic_definitions_source_kind
        CHECK (source_kind IN ('EVENT_TYPE')),
    CONSTRAINT chk_semantic_definitions_key
        CHECK (semantic_key ~ '^[a-z0-9][a-z0-9._-]{0,99}$'),
    CONSTRAINT chk_semantic_definitions_display_name
        CHECK (jsonb_typeof(display_name) = 'object' AND display_name <> '{}'::jsonb)
);

CREATE TABLE analytics_semantic_aliases (
    project_id VARCHAR(50) NOT NULL,
    source_kind VARCHAR(32) NOT NULL,
    raw_key VARCHAR(100) NOT NULL,
    semantic_key VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_semantic_aliases
        PRIMARY KEY (project_id, source_kind, raw_key),
    CONSTRAINT fk_semantic_aliases_definition
        FOREIGN KEY (project_id, source_kind, semantic_key)
        REFERENCES analytics_semantic_definitions(project_id, source_kind, semantic_key)
        ON DELETE CASCADE,
    CONSTRAINT chk_semantic_aliases_source_kind
        CHECK (source_kind IN ('EVENT_TYPE')),
    CONSTRAINT chk_semantic_aliases_raw_key
        CHECK (raw_key ~ '[^[:space:]]')
);

CREATE INDEX ix_semantic_definitions_active
    ON analytics_semantic_definitions(project_id, source_kind, is_active);

CREATE INDEX ix_semantic_aliases_definition
    ON analytics_semantic_aliases(project_id, source_kind, semantic_key);

CREATE TRIGGER update_semantic_definitions_updated_at
    BEFORE UPDATE ON analytics_semantic_definitions
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

COMMENT ON TABLE analytics_semantic_definitions IS
    'Project-scoped canonical semantic definitions stored in the AnalyticsHub system database';
COMMENT ON TABLE analytics_semantic_aliases IS
    'Project-scoped raw-key aliases; multiple raw keys may resolve to one semantic definition';
