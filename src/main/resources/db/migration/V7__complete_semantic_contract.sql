-- Semantic definitions are stable contracts consumed by dashboards and counters.
-- Raw event keys remain aliases and event facts are never rewritten.
ALTER TABLE analytics_semantic_definitions
    ADD COLUMN definition_origin VARCHAR(16) NOT NULL DEFAULT 'CUSTOM';

ALTER TABLE analytics_semantic_definitions
    ADD CONSTRAINT chk_semantic_definition_origin
        CHECK (definition_origin IN ('OFFICIAL', 'CUSTOM'));

COMMENT ON COLUMN analytics_semantic_definitions.definition_origin IS
    'OFFICIAL definitions are template-owned stable contracts; CUSTOM definitions are project-owned contracts';

-- Existing projects receive the same official contracts as newly created projects.
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
