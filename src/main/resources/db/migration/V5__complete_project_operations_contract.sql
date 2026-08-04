-- Complete the first post-production project operations contract in one release migration.
-- Production 1.0.1 artifacts contain system migrations only through V4.

-- Projects declare the analysis template that owns their initial workspace layout.
ALTER TABLE analytics_projects
    ADD COLUMN analysis_template VARCHAR(24) NOT NULL DEFAULT 'app';

ALTER TABLE analytics_projects
    ADD CONSTRAINT ck_project_analysis_template
        CHECK (analysis_template IN ('app', 'website', 'webapp', 'blank'));

COMMENT ON COLUMN analytics_projects.analysis_template IS
    '项目工作台初始化模板：app、website、webapp 或 blank';

-- Workspace purpose is stable across templates; templates only select its widgets.
UPDATE analytics_dashboards
SET dashboard_key = 'overview',
    revision = revision + 1
WHERE dashboard_key = 'operations';

UPDATE analytics_dashboards
SET dashboard_key = 'details',
    revision = revision + 1
WHERE dashboard_key = 'technical';

-- Historical projects were App projects. Their retired generic details layout
-- included a website traffic table, which is not part of the App data scope.
UPDATE analytics_dashboards AS dashboard
SET definition = jsonb_set(
        dashboard.definition,
        '{widgets}',
        COALESCE((
            SELECT jsonb_agg(widget)
            FROM jsonb_array_elements(dashboard.definition -> 'widgets') AS widget
            WHERE widget ->> 'type' <> 'core.traffic'
        ), '[]'::jsonb)
    ),
    revision = dashboard.revision + 1
FROM analytics_projects AS project
WHERE dashboard.project_id = project.project_id
  AND project.analysis_template = 'app'
  AND dashboard.dashboard_key = 'details'
  AND jsonb_typeof(dashboard.definition -> 'widgets') = 'array';

-- Semantic definitions are stable contracts; raw event facts remain unchanged aliases.
ALTER TABLE analytics_semantic_definitions
    ADD COLUMN definition_origin VARCHAR(16) NOT NULL DEFAULT 'CUSTOM';

ALTER TABLE analytics_semantic_definitions
    ADD CONSTRAINT chk_semantic_definition_origin
        CHECK (definition_origin IN ('OFFICIAL', 'CUSTOM'));

COMMENT ON COLUMN analytics_semantic_definitions.definition_origin IS
    'OFFICIAL definitions are template-owned stable contracts; CUSTOM definitions are project-owned contracts';

-- Existing app-like projects receive the same official contracts as newly created projects.
INSERT INTO analytics_semantic_definitions
    (project_id, source_kind, semantic_key, definition_origin, display_name, category, description, is_active)
SELECT project_id, 'EVENT_TYPE', preset.semantic_key, 'OFFICIAL', preset.display_name,
       preset.category, preset.description, TRUE
FROM analytics_projects
CROSS JOIN (VALUES
    ('core.activation.completed', '{"zh-CN":"激活完成","en":"Activation completed"}'::jsonb,
     'lifecycle', '用户完成产品定义的首次关键激活动作'),
    ('core.action.completed', '{"zh-CN":"核心动作完成","en":"Core action completed"}'::jsonb,
     'engagement', '用户完成产品最核心的业务动作'),
    ('core.paywall.opened', '{"zh-CN":"付费墙打开","en":"Paywall opened"}'::jsonb,
     'monetization', '用户打开付费页面或付费墙'),
    ('core.purchase.completed', '{"zh-CN":"购买完成","en":"Purchase completed"}'::jsonb,
     'monetization', '用户完成一次有效购买')
) AS preset(semantic_key, display_name, category, description)
WHERE analytics_projects.analysis_template IN ('app', 'webapp')
ON CONFLICT (project_id, source_kind, semantic_key) DO UPDATE SET
    definition_origin = EXCLUDED.definition_origin;
