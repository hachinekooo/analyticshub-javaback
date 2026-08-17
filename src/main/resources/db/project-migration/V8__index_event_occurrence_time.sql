-- Product reports use client occurrence time; server created_at remains the
-- ingestion timestamp for delivery diagnostics.
CREATE INDEX ${tablePrefix}ix_evt_proj_ts
    ON ${tablePrefix}events(project_id, event_timestamp DESC);

CREATE INDEX ${tablePrefix}ix_evt_proj_dev_ts
    ON ${tablePrefix}events(project_id, device_id, event_timestamp DESC);
