ALTER TABLE analytics_metric_definitions
    DROP CONSTRAINT ck_metric_definitions_type;

ALTER TABLE analytics_metric_definitions
    ADD CONSTRAINT ck_metric_definitions_type CHECK (
        metric_type IN (
            'EVENT_COUNT',
            'UNIQUE_ACTORS',
            'FUNNEL_CONVERSION',
            'RETENTION',
            'PROPERTY_BREAKDOWN',
            'NUMERIC_PROPERTY_SUMMARY'
        )
    );
