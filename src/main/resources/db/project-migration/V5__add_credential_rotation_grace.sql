-- Keep the previously authenticated device credential for a short grace
-- window. This lets a client safely retry credential rotation when the first
-- response was lost, without reopening unauthenticated re-registration.
ALTER TABLE ${tablePrefix}devices
    ADD COLUMN previous_api_key VARCHAR(100),
    ADD COLUMN previous_secret_key VARCHAR(100),
    ADD COLUMN previous_credentials_expires_at TIMESTAMP WITH TIME ZONE;

CREATE INDEX ${tablePrefix}ix_devices_previous_key
    ON ${tablePrefix}devices(project_id, previous_api_key)
    WHERE previous_api_key IS NOT NULL;
