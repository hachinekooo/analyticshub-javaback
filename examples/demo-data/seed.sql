\set ON_ERROR_STOP on
SET timezone = 'UTC';

-- This script only resets tables owned by the three explicitly named demo projects.
TRUNCATE TABLE
    demo_app.analytics_work_order_outbox,
    demo_app.analytics_work_order_activities,
    demo_app.analytics_privacy_requests,
    demo_app.analytics_idempotency_keys,
    demo_app.analytics_events,
    demo_app.analytics_sessions,
    demo_app.analytics_devices,
    demo_app.analytics_traffic_metrics,
    demo_app.analytics_counters
RESTART IDENTITY CASCADE;

DELETE FROM analytics.analytics_semantic_aliases
WHERE project_id IN ('demo_app', 'demo_website', 'demo_webapp');
DELETE FROM analytics.analytics_semantic_definitions
WHERE project_id IN ('demo_app', 'demo_website', 'demo_webapp')
  AND definition_origin = 'CUSTOM';
DELETE FROM analytics.analytics_dashboards
WHERE project_id IN ('demo_app', 'demo_website', 'demo_webapp');

TRUNCATE TABLE
    demo_website.analytics_work_order_outbox,
    demo_website.analytics_work_order_activities,
    demo_website.analytics_privacy_requests,
    demo_website.analytics_idempotency_keys,
    demo_website.analytics_events,
    demo_website.analytics_sessions,
    demo_website.analytics_devices,
    demo_website.analytics_traffic_metrics,
    demo_website.analytics_counters
RESTART IDENTITY CASCADE;

TRUNCATE TABLE
    demo_webapp.analytics_work_order_outbox,
    demo_webapp.analytics_work_order_activities,
    demo_webapp.analytics_privacy_requests,
    demo_webapp.analytics_idempotency_keys,
    demo_webapp.analytics_events,
    demo_webapp.analytics_sessions,
    demo_webapp.analytics_devices,
    demo_webapp.analytics_traffic_metrics,
    demo_webapp.analytics_counters
RESTART IDENTITY CASCADE;

-- Mobile App: devices, sessions and product events across 60 days.
INSERT INTO demo_app.analytics_devices (
    device_id, api_key, secret_key, device_model, os_version, app_version,
    project_id, is_banned, created_at, last_active_at
)
SELECT md5('app-device-' || n)::uuid,
       'demo-app-key-' || lpad(n::text, 4, '0'),
       'demo-app-secret-' || lpad(n::text, 4, '0'),
       (ARRAY['iPhone 17 Pro','iPhone 16','iPhone 15 Pro','iPad Air'])[1 + n % 4],
       (ARRAY['26.0','18.6','18.5'])[1 + n % 3],
       (ARRAY['1.0.0','1.0.1','1.1.0'])[1 + n % 3],
       'demo_app', FALSE,
       now() - (n % 60) * interval '1 day',
       now() - (n % 72) * interval '1 hour'
FROM generate_series(1, 160) AS series(n);

INSERT INTO demo_app.analytics_sessions (
    session_id, device_id, user_id, session_start_time, session_duration_ms,
    device_model, os_version, app_version, build_number, screen_count,
    event_count, project_id, created_at
)
SELECT md5('app-session-' || n)::uuid,
       md5('app-device-' || (1 + n % 160))::uuid,
       'app_user_' || lpad((1 + n % 110)::text, 4, '0'),
       now() - (n % 60) * interval '1 day' - (n % 1440) * interval '1 minute',
       45000 + (n % 900) * 1000,
       (ARRAY['iPhone 17 Pro','iPhone 16','iPhone 15 Pro','iPad Air'])[1 + n % 4],
       (ARRAY['26.0','18.6','18.5'])[1 + n % 3],
       (ARRAY['1.0.0','1.0.1','1.1.0'])[1 + n % 3],
       (100 + n % 12)::text, 2 + n % 12, 4 + n % 24,
       'demo_app',
       now() - (n % 60) * interval '1 day' - (n % 1440) * interval '1 minute'
FROM generate_series(1, 1800) AS series(n);

WITH facts AS (
    SELECT n,
           now() - (n % 60) * interval '1 day' - (n % 1440) * interval '1 minute' AS occurred_at,
           CASE n % 12
             WHEN 0 THEN 'app_opened'
             WHEN 1 THEN 'onboarding_completed'
             WHEN 2 THEN 'letter_save'
             WHEN 3 THEN 'letter_saved'
             WHEN 4 THEN 'stationery_beautification_applied'
             WHEN 5 THEN 'template_applied'
             WHEN 6 THEN 'share_completed'
             WHEN 7 THEN 'paywall_opened'
             WHEN 8 THEN 'purchase_started'
             WHEN 9 THEN 'purchase_completed'
             WHEN 10 THEN 'notification_opened'
             ELSE 'settings_opened'
           END AS event_type
    FROM generate_series(1, 12000) AS series(n)
)
INSERT INTO demo_app.analytics_events (
    event_id, device_id, user_id, session_id, event_type, event_timestamp,
    properties, project_id, created_at
)
SELECT 'app-event-' || lpad(n::text, 6, '0'),
       md5('app-device-' || (1 + n % 160))::uuid,
       'app_user_' || lpad((1 + n % 110)::text, 4, '0'),
       md5('app-session-' || (1 + n % 1800))::uuid,
       event_type,
       floor(extract(epoch FROM occurred_at) * 1000)::bigint,
       jsonb_build_object(
         'result', CASE WHEN n % 17 = 0 THEN 'cancelled' ELSE 'success' END,
         'source', (ARRAY['editor','share_extension','import','shortcut'])[1 + n % 4],
         'platform', 'ios',
         'app_version', (ARRAY['1.0.0','1.0.1','1.1.0'])[1 + n % 3],
         'plan', (ARRAY['free','monthly','annual'])[1 + n % 3]
       ),
       'demo_app', occurred_at
FROM facts;

-- Marketing Website: enough page views, visitors, campaigns and bot traffic to show real ratios.
WITH facts AS (
    SELECT n,
           now() - (n % 60) * interval '1 day' - (n % 1440) * interval '1 minute' AS occurred_at
    FROM generate_series(1, 18000) AS series(n)
)
INSERT INTO demo_website.analytics_traffic_metrics (
    metric_id, device_id, user_id, session_id, metric_type, page_path,
    referrer, metric_timestamp, metadata, project_id, created_at
)
SELECT 'website-metric-' || lpad(n::text, 6, '0'),
       md5('website-visitor-' || (1 + n % 1450))::uuid,
       'visitor_' || lpad((1 + n % 1450)::text, 5, '0'),
       md5('website-session-' || (1 + n % 5200))::uuid,
       CASE WHEN n % 23 = 0 THEN 'cta_click' ELSE 'page_view' END,
       (ARRAY['/','/features','/pricing','/download','/blog','/blog/productivity','/privacy','/support'])[1 + n % 8],
       (ARRAY['','https://www.google.com/','https://www.bing.com/','https://www.xiaohongshu.com/','https://github.com/','https://newsletter.example/'])[1 + n % 6],
       floor(extract(epoch FROM occurred_at) * 1000)::bigint,
       jsonb_build_object(
         'isBot', n % 97 = 0,
         'browser', (ARRAY['Safari','Chrome','Edge','Firefox'])[1 + n % 4],
         'country', (ARRAY['CN','US','JP','SG','DE'])[1 + n % 5],
         'utm_source', (ARRAY['organic','newsletter','social','partner','direct'])[1 + n % 5],
         'deviceType', (ARRAY['mobile','desktop','tablet'])[1 + n % 3]
       ),
       'demo_website', occurred_at
FROM facts;

-- SaaS WebApp: product events and web traffic intentionally coexist.
INSERT INTO demo_webapp.analytics_devices (
    device_id, api_key, secret_key, device_model, os_version, app_version,
    project_id, is_banned, created_at, last_active_at
)
SELECT md5('webapp-device-' || n)::uuid,
       'demo-webapp-key-' || lpad(n::text, 4, '0'),
       'demo-webapp-secret-' || lpad(n::text, 4, '0'),
       (ARRAY['MacBook Pro','Windows PC','iPad Pro','Android Phone'])[1 + n % 4],
       (ARRAY['macOS 26','Windows 11','iPadOS 26','Android 16'])[1 + n % 4],
       (ARRAY['2026.7','2026.8','2026.9'])[1 + n % 3],
       'demo_webapp', FALSE,
       now() - (n % 75) * interval '1 day',
       now() - (n % 96) * interval '1 hour'
FROM generate_series(1, 220) AS series(n);

INSERT INTO demo_webapp.analytics_sessions (
    session_id, device_id, user_id, session_start_time, session_duration_ms,
    device_model, os_version, app_version, build_number, screen_count,
    event_count, project_id, created_at
)
SELECT md5('webapp-session-' || n)::uuid,
       md5('webapp-device-' || (1 + n % 220))::uuid,
       'team_user_' || lpad((1 + n % 150)::text, 4, '0'),
       now() - (n % 75) * interval '1 day' - (n % 1440) * interval '1 minute',
       90000 + (n % 1800) * 1000,
       (ARRAY['MacBook Pro','Windows PC','iPad Pro','Android Phone'])[1 + n % 4],
       (ARRAY['macOS 26','Windows 11','iPadOS 26','Android 16'])[1 + n % 4],
       (ARRAY['2026.7','2026.8','2026.9'])[1 + n % 3],
       (700 + n % 20)::text, 3 + n % 18, 6 + n % 36,
       'demo_webapp',
       now() - (n % 75) * interval '1 day' - (n % 1440) * interval '1 minute'
FROM generate_series(1, 2600) AS series(n);

WITH facts AS (
    SELECT n,
           now() - (n % 75) * interval '1 day' - (n % 1440) * interval '1 minute' AS occurred_at,
           CASE n % 12
             WHEN 0 THEN 'account_created'
             WHEN 1 THEN 'workspace_created'
             WHEN 2 THEN 'project_created'
             WHEN 3 THEN 'task_completed'
             WHEN 4 THEN 'document_published'
             WHEN 5 THEN 'member_invited'
             WHEN 6 THEN 'integration_connected'
             WHEN 7 THEN 'billing_page_opened'
             WHEN 8 THEN 'subscription_started'
             WHEN 9 THEN 'purchase_completed'
             WHEN 10 THEN 'dashboard_viewed'
             ELSE 'search_completed'
           END AS event_type
    FROM generate_series(1, 15000) AS series(n)
)
INSERT INTO demo_webapp.analytics_events (
    event_id, device_id, user_id, session_id, event_type, event_timestamp,
    properties, project_id, created_at
)
SELECT 'webapp-event-' || lpad(n::text, 6, '0'),
       md5('webapp-device-' || (1 + n % 220))::uuid,
       'team_user_' || lpad((1 + n % 150)::text, 4, '0'),
       md5('webapp-session-' || (1 + n % 2600))::uuid,
       event_type,
       floor(extract(epoch FROM occurred_at) * 1000)::bigint,
       jsonb_build_object(
         'result', CASE WHEN n % 19 = 0 THEN 'failed' ELSE 'success' END,
         'plan', (ARRAY['free','pro','business'])[1 + n % 3],
         'role', (ARRAY['owner','admin','member'])[1 + n % 3],
         'workspace_size', 1 + n % 40
       ),
       'demo_webapp', occurred_at
FROM facts;

WITH facts AS (
    SELECT n,
           now() - (n % 75) * interval '1 day' - (n % 1440) * interval '1 minute' AS occurred_at
    FROM generate_series(1, 14000) AS series(n)
)
INSERT INTO demo_webapp.analytics_traffic_metrics (
    metric_id, device_id, user_id, session_id, metric_type, page_path,
    referrer, metric_timestamp, metadata, project_id, created_at
)
SELECT 'webapp-metric-' || lpad(n::text, 6, '0'),
       md5('webapp-device-' || (1 + n % 220))::uuid,
       'team_user_' || lpad((1 + n % 150)::text, 4, '0'),
       md5('webapp-session-' || (1 + n % 2600))::uuid,
       CASE WHEN n % 17 = 0 THEN 'button_click' ELSE 'page_view' END,
       (ARRAY['/app','/app/dashboard','/app/projects','/app/tasks','/app/documents','/app/settings','/pricing'])[1 + n % 7],
       (ARRAY['','https://www.google.com/','https://app.partner.example/','https://docs.example/'])[1 + n % 4],
       floor(extract(epoch FROM occurred_at) * 1000)::bigint,
       jsonb_build_object(
         'isBot', FALSE,
         'browser', (ARRAY['Chrome','Safari','Edge','Firefox'])[1 + n % 4],
         'plan', (ARRAY['free','pro','business'])[1 + n % 3]
       ),
       'demo_webapp', occurred_at
FROM facts;

-- Counters use realistic business baselines and semantic trigger contracts.
INSERT INTO demo_app.analytics_counters (
    counter_key, counter_value, display_name, unit, event_trigger, is_public,
    description, project_id, last_rebuilt_at, last_rebuild_event_count, rebuild_offset
) VALUES
  ('letters_completed', 18432, '{"zh-CN":"累计完成信件","en":"Letters Completed"}', '{"zh-CN":"封","en":"letters"}',
   '{"semantic_key":"core.action.completed","conditions":{"result":"success"}}', TRUE,
   '历史成功保存事件加业务上线前基线', 'demo_app', now(), 1882, 16550),
  ('beautification_uses', 972, '{"zh-CN":"美化使用次数","en":"Beautification Uses"}', '{"zh-CN":"次","en":"uses"}',
   '{"semantic_key":"custom.stationery.beautification_applied"}', FALSE,
   '信纸美化能力累计使用次数', 'demo_app', now(), 972, 0),
  ('shares_completed', 836, '{"zh-CN":"分享完成次数","en":"Completed Shares"}', '{"zh-CN":"次","en":"shares"}',
   '{"semantic_key":"custom.content.shared"}', FALSE,
   '成功分享内容的累计次数', 'demo_app', now(), 836, 0);

INSERT INTO demo_website.analytics_counters (
    counter_key, counter_value, display_name, unit, is_public, description, project_id, rebuild_offset
) VALUES
  ('downloads', 2684, '{"zh-CN":"累计下载点击","en":"Download Clicks"}', '{"zh-CN":"次","en":"clicks"}', TRUE,
   '官网下载按钮累计点击数', 'demo_website', 2684),
  ('newsletter_subscribers', 1260, '{"zh-CN":"邮件订阅人数","en":"Newsletter Subscribers"}', '{"zh-CN":"人","en":"subscribers"}', TRUE,
   '邮件订阅业务累计人数', 'demo_website', 1260);

INSERT INTO demo_webapp.analytics_counters (
    counter_key, counter_value, display_name, unit, event_trigger, is_public,
    description, project_id, last_rebuilt_at, last_rebuild_event_count, rebuild_offset
) VALUES
  ('tasks_completed', 48250, '{"zh-CN":"累计完成任务","en":"Tasks Completed"}', '{"zh-CN":"项","en":"tasks"}',
   '{"semantic_key":"core.action.completed","conditions":{"result":"success"}}', TRUE,
   '成功完成任务事件加导入历史基线', 'demo_webapp', now(), 1184, 47066),
  ('workspaces_created', 1420, '{"zh-CN":"累计创建工作区","en":"Workspaces Created"}', '{"zh-CN":"个","en":"workspaces"}',
   '{"semantic_key":"core.activation.completed"}', FALSE,
   '创建工作区的累计次数', 'demo_webapp', now(), 1250, 170),
  ('subscriptions_started', 624, '{"zh-CN":"累计订阅","en":"Subscriptions Started"}', '{"zh-CN":"个","en":"subscriptions"}',
   '{"semantic_key":"core.purchase.completed"}', FALSE,
   '成功开始订阅的累计数量', 'demo_webapp', now(), 624, 0);

-- A small work-order set makes the privacy workflow useful without dominating analytics data.
INSERT INTO demo_app.analytics_privacy_requests (
    request_id, project_id, user_id, device_id, request_type, processor,
    source, status, contact_email, requester_note, operator, operator_note,
    result_payload, requested_at, processed_at, closed_at
) VALUES
  ('PRIV-DEMO-APP-001','demo_app','app_user_0008',md5('app-device-8')::uuid,'EXPORT','ANALYTICSHUB','APP_SETTINGS','SUBMITTED','app-user-8@example.org','希望导出账号分析数据',NULL,NULL,NULL,now()-interval '2 hours',NULL,NULL),
  ('PRIV-DEMO-APP-002','demo_app','app_user_0016',md5('app-device-16')::uuid,'DELETE','ANALYTICSHUB','APP_SETTINGS','IN_PROGRESS','app-user-16@example.org','申请去标识化', 'demo_agent','已完成身份核验',NULL,now()-interval '1 day',NULL,NULL),
  ('PRIV-DEMO-APP-003','demo_app','app_user_0024',md5('app-device-24')::uuid,'DELETE','ANALYTICSHUB','EMAIL','COMPLETED','app-user-24@example.org','不再使用产品','demo_agent','已去标识化并保留审计记录','{"action":"ANONYMIZED","scope":"analytics_identifiers"}',now()-interval '6 days',now()-interval '5 days',now()-interval '5 days'),
  ('PRIV-DEMO-APP-004','demo_app','app_user_0032',md5('app-device-32')::uuid,'EXPORT','ANALYTICSHUB','APP_SETTINGS','REJECTED','app-user-32@example.org','导出请求','demo_agent','身份信息无法匹配',NULL,now()-interval '10 days',now()-interval '9 days',now()-interval '9 days');

INSERT INTO demo_website.analytics_privacy_requests (
    request_id, project_id, user_id, device_id, request_type, processor,
    source, status, contact_email, requester_note, operator, operator_note,
    result_payload, requested_at, processed_at, closed_at
) VALUES
  ('PRIV-DEMO-WEB-001','demo_website','visitor_00012',md5('website-visitor-12')::uuid,'EXPORT','ANALYTICSHUB','WEB_FORM','SUBMITTED','visitor-12@example.org','申请导出网站访问数据',NULL,NULL,NULL,now()-interval '3 hours',NULL,NULL),
  ('PRIV-DEMO-WEB-002','demo_website','visitor_00028',md5('website-visitor-28')::uuid,'DELETE','ANALYTICSHUB','WEB_FORM','IN_PROGRESS','visitor-28@example.org','撤回分析标识','demo_agent','等待执行去标识化',NULL,now()-interval '2 days',NULL,NULL),
  ('PRIV-DEMO-WEB-003','demo_website','visitor_00036',md5('website-visitor-36')::uuid,'DELETE','ANALYTICSHUB','EMAIL','COMPLETED','visitor-36@example.org','删除个人标识','demo_agent','访问标识已匿名化','{"action":"ANONYMIZED","scope":"traffic_identifiers"}',now()-interval '8 days',now()-interval '7 days',now()-interval '7 days');

INSERT INTO demo_webapp.analytics_privacy_requests (
    request_id, project_id, user_id, device_id, request_type, processor,
    source, status, contact_email, requester_note, operator, operator_note,
    result_payload, requested_at, processed_at, closed_at
) VALUES
  ('PRIV-DEMO-SAAS-001','demo_webapp','team_user_0012',md5('webapp-device-12')::uuid,'EXPORT','ANALYTICSHUB','APP_SETTINGS','SUBMITTED','team-user-12@example.org','导出工作区个人分析数据',NULL,NULL,NULL,now()-interval '4 hours',NULL,NULL),
  ('PRIV-DEMO-SAAS-002','demo_webapp','team_user_0026',md5('webapp-device-26')::uuid,'DELETE','ANALYTICSHUB','APP_SETTINGS','IN_PROGRESS','team-user-26@example.org','离开团队后去标识化','demo_agent','已确认不影响团队业务数据',NULL,now()-interval '3 days',NULL,NULL),
  ('PRIV-DEMO-SAAS-003','demo_webapp','team_user_0044',md5('webapp-device-44')::uuid,'EXPORT','ANALYTICSHUB','EMAIL','COMPLETED','team-user-44@example.org','导出请求','demo_agent','导出文件已通过安全渠道交付','{"action":"EXPORTED","recordCount":128}',now()-interval '9 days',now()-interval '8 days',now()-interval '8 days'),
  ('PRIV-DEMO-SAAS-004','demo_webapp','team_user_0058',md5('webapp-device-58')::uuid,'DELETE','ANALYTICSHUB','EMAIL','COMPLETED','team-user-58@example.org','去标识化请求','demo_agent','个人标识已匿名化，团队统计保留','{"action":"ANONYMIZED","scope":"personal_identifiers"}',now()-interval '14 days',now()-interval '13 days',now()-interval '13 days');

-- Semantic aliases keep raw SDK naming independent from stable platform contracts.
INSERT INTO analytics.analytics_semantic_definitions (
    project_id, source_kind, semantic_key, definition_origin, display_name,
    category, description, is_active
) VALUES
  ('demo_app','EVENT_TYPE','custom.stationery.beautification_applied','CUSTOM','{"zh-CN":"信纸美化已使用","en":"Stationery Beautification Applied"}','engagement','用户成功使用一次信纸美化',TRUE),
  ('demo_app','EVENT_TYPE','custom.content.shared','CUSTOM','{"zh-CN":"内容分享完成","en":"Content Shared"}','engagement','用户成功分享内容',TRUE),
  ('demo_webapp','EVENT_TYPE','custom.workspace.created','CUSTOM','{"zh-CN":"工作区创建完成","en":"Workspace Created"}','activation','用户创建一个新工作区',TRUE),
  ('demo_webapp','EVENT_TYPE','custom.member.invited','CUSTOM','{"zh-CN":"成员邀请完成","en":"Member Invited"}','collaboration','用户成功邀请团队成员',TRUE)
ON CONFLICT (project_id, source_kind, semantic_key) DO UPDATE SET
    display_name = EXCLUDED.display_name,
    category = EXCLUDED.category,
    description = EXCLUDED.description,
    is_active = TRUE;

INSERT INTO analytics.analytics_semantic_aliases (project_id, source_kind, raw_key, semantic_key) VALUES
  ('demo_app','EVENT_TYPE','app_opened','core.activation.completed'),
  ('demo_app','EVENT_TYPE','onboarding_completed','core.activation.completed'),
  ('demo_app','EVENT_TYPE','letter_save','core.action.completed'),
  ('demo_app','EVENT_TYPE','letter_saved','core.action.completed'),
  ('demo_app','EVENT_TYPE','paywall_opened','core.paywall.opened'),
  ('demo_app','EVENT_TYPE','purchase_completed','core.purchase.completed'),
  ('demo_app','EVENT_TYPE','stationery_beautification_applied','custom.stationery.beautification_applied'),
  ('demo_app','EVENT_TYPE','share_completed','custom.content.shared'),
  ('demo_webapp','EVENT_TYPE','account_created','core.activation.completed'),
  ('demo_webapp','EVENT_TYPE','workspace_created','custom.workspace.created'),
  ('demo_webapp','EVENT_TYPE','task_completed','core.action.completed'),
  ('demo_webapp','EVENT_TYPE','billing_page_opened','core.paywall.opened'),
  ('demo_webapp','EVENT_TYPE','purchase_completed','core.purchase.completed'),
  ('demo_webapp','EVENT_TYPE','member_invited','custom.member.invited')
ON CONFLICT (project_id, source_kind, raw_key) DO UPDATE SET semantic_key = EXCLUDED.semantic_key;

-- Valid declarative layouts make every example useful immediately after seeding.
INSERT INTO analytics.analytics_dashboards (
    project_id, dashboard_key, display_name, description, schema_version,
    definition, revision, is_default, is_active
) VALUES
  ('demo_app','overview','{"zh-CN":"数据大屏","en":"Dashboard"}','Mobile product operations example',1,
   '{"schemaVersion":1,"defaultRange":"30d","widgets":[
      {"id":"overview_demo","type":"core.overview","layout":{"x":0,"y":0,"w":12,"h":5,"minW":6,"minH":4}},
      {"id":"trends_demo","type":"core.trends","layout":{"x":0,"y":5,"w":7,"h":10,"minW":4,"minH":6},"config":{"granularity":"day"}},
      {"id":"top_events_demo","type":"core.topEvents","layout":{"x":7,"y":5,"w":5,"h":10,"minW":4,"minH":6},"config":{"aggregation":"semantic","limit":10}},
      {"id":"funnel_demo","type":"core.productFunnel","layout":{"x":0,"y":15,"w":12,"h":9,"minW":6,"minH":6},"config":{"steps":["core.activation.completed","core.action.completed","core.purchase.completed"]}},
      {"id":"retention_demo","type":"core.retention","layout":{"x":0,"y":24,"w":7,"h":9,"minW":4,"minH":6},"config":{"cohortEvent":"core.activation.completed","returnEvent":"core.action.completed","days":[1,7,14,30]}},
      {"id":"counters_demo","type":"core.counters","layout":{"x":7,"y":24,"w":5,"h":9,"minW":4,"minH":4},"config":{"keys":["letters_completed","beautification_uses","shares_completed"]}}
    ]}',1,TRUE,TRUE),
  ('demo_website','overview','{"zh-CN":"数据大屏","en":"Dashboard"}','Marketing website traffic example',1,
   '{"schemaVersion":1,"defaultRange":"30d","widgets":[
      {"id":"traffic_overview_demo","type":"core.trafficOverview","layout":{"x":0,"y":0,"w":12,"h":5,"minW":6,"minH":4}},
      {"id":"traffic_trends_demo","type":"core.trafficTrends","layout":{"x":0,"y":5,"w":7,"h":10,"minW":4,"minH":6},"config":{"granularity":"day"}},
      {"id":"top_pages_demo","type":"core.topPages","layout":{"x":7,"y":5,"w":5,"h":10,"minW":4,"minH":6},"config":{"limit":8}},
      {"id":"top_referrers_demo","type":"core.topReferrers","layout":{"x":0,"y":15,"w":7,"h":8,"minW":4,"minH":6},"config":{"limit":8}},
      {"id":"website_counters_demo","type":"core.counters","layout":{"x":7,"y":15,"w":5,"h":8,"minW":4,"minH":4},"config":{"keys":["downloads","newsletter_subscribers"]}}
    ]}',1,TRUE,TRUE),
  ('demo_webapp','overview','{"zh-CN":"数据大屏","en":"Dashboard"}','SaaS WebApp product and traffic example',1,
   '{"schemaVersion":1,"defaultRange":"30d","widgets":[
      {"id":"overview_demo","type":"core.overview","layout":{"x":0,"y":0,"w":12,"h":5,"minW":6,"minH":4}},
      {"id":"trends_demo","type":"core.trends","layout":{"x":0,"y":5,"w":7,"h":10,"minW":4,"minH":6},"config":{"granularity":"day"}},
      {"id":"top_events_demo","type":"core.topEvents","layout":{"x":7,"y":5,"w":5,"h":10,"minW":4,"minH":6},"config":{"aggregation":"semantic","limit":10}},
      {"id":"funnel_demo","type":"core.productFunnel","layout":{"x":0,"y":15,"w":12,"h":9,"minW":6,"minH":6},"config":{"steps":["core.activation.completed","core.action.completed","core.purchase.completed"],"groupBy":"plan"}},
      {"id":"retention_demo","type":"core.retention","layout":{"x":0,"y":24,"w":7,"h":9,"minW":4,"minH":6},"config":{"cohortEvent":"core.activation.completed","returnEvent":"core.action.completed","days":[1,7,14,30]}},
      {"id":"counters_demo","type":"core.counters","layout":{"x":7,"y":24,"w":5,"h":9,"minW":4,"minH":4},"config":{"keys":["tasks_completed","workspaces_created","subscriptions_started"]}},
      {"id":"traffic_overview_demo","type":"core.trafficOverview","layout":{"x":0,"y":33,"w":12,"h":5,"minW":6,"minH":4}},
      {"id":"traffic_trends_demo","type":"core.trafficTrends","layout":{"x":0,"y":38,"w":7,"h":10,"minW":4,"minH":6},"config":{"granularity":"day"}},
      {"id":"top_pages_demo","type":"core.topPages","layout":{"x":7,"y":38,"w":5,"h":10,"minW":4,"minH":6},"config":{"limit":8}},
      {"id":"top_referrers_demo","type":"core.topReferrers","layout":{"x":0,"y":48,"w":12,"h":8,"minW":4,"minH":6},"config":{"limit":8}}
    ]}',1,TRUE,TRUE)
ON CONFLICT (project_id, dashboard_key) DO UPDATE SET
    display_name = EXCLUDED.display_name,
    description = EXCLUDED.description,
    schema_version = EXCLUDED.schema_version,
    definition = EXCLUDED.definition,
    revision = analytics.analytics_dashboards.revision + 1,
    is_default = TRUE,
    is_active = TRUE;
