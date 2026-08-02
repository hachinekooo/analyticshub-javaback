-- Persist the business baseline and historical boundary used by every rebuild.
ALTER TABLE ${tablePrefix}counters
    ADD COLUMN rebuild_offset BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN event_count_start_at TIMESTAMP WITH TIME ZONE;

COMMENT ON COLUMN ${tablePrefix}counters.rebuild_offset IS
    'Stable business adjustment added to the matched event count during every rebuild';
COMMENT ON COLUMN ${tablePrefix}counters.event_count_start_at IS
    'Null includes all stored events; otherwise rebuilds include only events ingested at or after this time';
