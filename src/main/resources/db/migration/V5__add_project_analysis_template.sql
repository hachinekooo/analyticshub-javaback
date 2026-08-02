ALTER TABLE analytics_projects
    ADD COLUMN analysis_template VARCHAR(24) NOT NULL DEFAULT 'app';

ALTER TABLE analytics_projects
    ADD CONSTRAINT ck_project_analysis_template
        CHECK (analysis_template IN ('app', 'website', 'webapp', 'blank'));

COMMENT ON COLUMN analytics_projects.analysis_template IS
    '项目工作台初始化模板：app、website、webapp 或 blank';
