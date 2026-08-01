-- Record the latest historical projection rebuild for each counter.
ALTER TABLE ${tablePrefix}counters
    ADD COLUMN last_rebuilt_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN last_rebuild_event_count BIGINT,
    ADD CONSTRAINT ${tablePrefix}ck_ctr_rebuild_count
        CHECK (last_rebuild_event_count IS NULL OR last_rebuild_event_count >= 0);

-- Historical rebuilds always scope by project and event type. The existing
-- GIN index on properties handles optional JSONB containment conditions.
CREATE INDEX ${tablePrefix}ix_evt_project_type
    ON ${tablePrefix}events(project_id, event_type);
