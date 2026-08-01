-- Add optimistic concurrency to privacy work orders and introduce a generic,
-- append-only activity stream plus a transactional notification outbox.
ALTER TABLE ${tablePrefix}privacy_requests
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT ${tablePrefix}ck_privacy_version CHECK (version >= 0);

CREATE TABLE ${tablePrefix}work_order_activities (
    id BIGSERIAL CONSTRAINT ${tablePrefix}pk_wo_activity PRIMARY KEY,
    activity_id VARCHAR(64) NOT NULL
        CONSTRAINT ${tablePrefix}uq_wo_activity UNIQUE,
    project_id VARCHAR(50) NOT NULL,
    work_order_type VARCHAR(32) NOT NULL,
    work_order_id VARCHAR(64) NOT NULL,
    activity_type VARCHAR(64) NOT NULL,
    from_status VARCHAR(32),
    to_status VARCHAR(32),
    actor VARCHAR(64),
    details JSONB,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX ${tablePrefix}ix_woa_order
    ON ${tablePrefix}work_order_activities(
        project_id,
        work_order_type,
        work_order_id,
        created_at DESC
    );

-- Activities are an audit log. Corrections must be represented by a new row,
-- never by changing or deleting an existing row.
CREATE FUNCTION ${schema}.${tablePrefix}woa_immutable()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $function$
BEGIN
    RAISE EXCEPTION 'work order activities are immutable';
END;
$function$;

CREATE TRIGGER ${tablePrefix}tr_woa_immutable
    BEFORE UPDATE OR DELETE ON ${tablePrefix}work_order_activities
    FOR EACH ROW EXECUTE FUNCTION ${schema}.${tablePrefix}woa_immutable();

-- Preserve a complete timeline for requests that predate this migration.
INSERT INTO ${tablePrefix}work_order_activities (
    activity_id,
    project_id,
    work_order_type,
    work_order_id,
    activity_type,
    to_status,
    actor,
    details,
    created_at
)
SELECT
    md5(project_id || ':' || request_id || ':created'),
    project_id,
    'PRIVACY_REQUEST',
    request_id,
    'WORK_ORDER_CREATED',
    status,
    'requester',
    jsonb_build_object('source', source, 'migrationBackfill', TRUE),
    requested_at
FROM ${tablePrefix}privacy_requests;

-- New public submissions continue to use the collection service unchanged;
-- this trigger records their creation in the same project transaction.
CREATE FUNCTION ${schema}.${tablePrefix}privacy_create_activity()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $function$
BEGIN
    INSERT INTO ${schema}.${tablePrefix}work_order_activities (
        activity_id,
        project_id,
        work_order_type,
        work_order_id,
        activity_type,
        to_status,
        actor,
        details,
        created_at
    ) VALUES (
        md5(NEW.project_id || ':' || NEW.request_id || ':created'),
        NEW.project_id,
        'PRIVACY_REQUEST',
        NEW.request_id,
        'WORK_ORDER_CREATED',
        NEW.status,
        'requester',
        jsonb_build_object('source', NEW.source),
        NEW.requested_at
    );
    RETURN NEW;
END;
$function$;

CREATE TRIGGER ${tablePrefix}tr_privacy_created
    AFTER INSERT ON ${tablePrefix}privacy_requests
    FOR EACH ROW EXECUTE FUNCTION ${schema}.${tablePrefix}privacy_create_activity();

CREATE TABLE ${tablePrefix}work_order_outbox (
    id BIGSERIAL CONSTRAINT ${tablePrefix}pk_wo_outbox PRIMARY KEY,
    notification_id VARCHAR(64) NOT NULL
        CONSTRAINT ${tablePrefix}uq_wo_outbox UNIQUE,
    project_id VARCHAR(50) NOT NULL,
    work_order_type VARCHAR(32) NOT NULL,
    work_order_id VARCHAR(64) NOT NULL,
    channel VARCHAR(16) NOT NULL DEFAULT 'EMAIL',
    recipient VARCHAR(255) NOT NULL,
    subject VARCHAR(120) NOT NULL,
    content TEXT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    max_attempts INTEGER NOT NULL DEFAULT 5,
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    claimed_at TIMESTAMP WITH TIME ZONE,
    claimed_by VARCHAR(64),
    last_delivery_status VARCHAR(32),
    last_error VARCHAR(500),
    sent_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT ${tablePrefix}ck_wo_out_status
        CHECK (status IN ('PENDING', 'PROCESSING', 'RETRY', 'SENT', 'DEAD')),
    CONSTRAINT ${tablePrefix}ck_wo_out_attempts
        CHECK (attempt_count >= 0 AND max_attempts > 0),
    CONSTRAINT ${tablePrefix}ck_wo_out_channel
        CHECK (channel IN ('EMAIL'))
);

CREATE INDEX ${tablePrefix}ix_woo_claim
    ON ${tablePrefix}work_order_outbox(
        project_id,
        status,
        next_attempt_at,
        created_at
    );

CREATE INDEX ${tablePrefix}ix_woo_order
    ON ${tablePrefix}work_order_outbox(
        project_id,
        work_order_type,
        work_order_id,
        created_at DESC
    );
