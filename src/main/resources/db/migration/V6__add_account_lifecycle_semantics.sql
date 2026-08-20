-- 账号生命周期属于跨 App 可复用的官方语义，具体 raw event 仍由项目自行映射。
INSERT INTO analytics_semantic_definitions
    (project_id, source_kind, semantic_key, definition_origin, display_name, category, description, is_active)
SELECT project_id, 'EVENT_TYPE', preset.semantic_key, 'OFFICIAL', preset.display_name,
       preset.category, preset.description, TRUE
FROM analytics_projects
CROSS JOIN (VALUES
    ('core.account.created', '{"zh-CN":"云账号创建","en":"Cloud account created"}'::jsonb,
     'lifecycle', '后端权威确认创建了新的云账号'),
    ('core.account.recreated', '{"zh-CN":"云账号重新创建","en":"Cloud account recreated"}'::jsonb,
     'lifecycle', '账号注销后经用户确认重新创建云账号')
) AS preset(semantic_key, display_name, category, description)
WHERE analytics_projects.analysis_template IN ('app', 'webapp')
ON CONFLICT (project_id, source_kind, semantic_key) DO UPDATE SET
    definition_origin = EXCLUDED.definition_origin;
