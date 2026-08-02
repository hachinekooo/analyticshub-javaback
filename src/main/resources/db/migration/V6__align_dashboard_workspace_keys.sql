-- Replace the retired generic workspace keys with template-owned keys.
-- Dashboard definitions are preserved; only their stable placement key changes.
UPDATE analytics_dashboards AS dashboard
SET dashboard_key = CASE project.analysis_template
        WHEN 'website' THEN 'website'
        WHEN 'webapp' THEN 'product'
        WHEN 'blank' THEN 'custom'
        ELSE 'app'
    END,
    revision = dashboard.revision + 1
FROM analytics_projects AS project
WHERE dashboard.project_id = project.project_id
  AND dashboard.dashboard_key = 'operations';

UPDATE analytics_dashboards
SET dashboard_key = 'details',
    revision = revision + 1
WHERE dashboard_key = 'technical';
