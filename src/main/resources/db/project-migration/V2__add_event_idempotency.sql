-- Project-local event idempotency registry.
-- The request key is hashed by the application before it reaches this table.
CREATE TABLE ${tablePrefix}idempotency_keys (
    project_id VARCHAR(50) NOT NULL,
    key_hash VARCHAR(64) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    event_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT ${tablePrefix}pk_idempotency PRIMARY KEY (project_id, key_hash)
);

CREATE INDEX ${tablePrefix}ix_idempotency_created
    ON ${tablePrefix}idempotency_keys(created_at DESC);
