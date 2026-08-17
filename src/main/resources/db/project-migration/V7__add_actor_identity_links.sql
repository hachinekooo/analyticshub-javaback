-- Project-scoped analytics actor aliases.
-- Raw events retain the actor that existed when the event occurred; product
-- analytics reports resolve aliases to the canonical cloud actor at query time.

CREATE TABLE ${tablePrefix}actor_identity_links (
    binding_id UUID NOT NULL CONSTRAINT ${tablePrefix}pk_actor_identity_links PRIMARY KEY,
    project_id VARCHAR(50) NOT NULL,
    source_actor_id UUID NOT NULL,
    canonical_actor_id UUID NOT NULL,
    linked_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT ${tablePrefix}ck_actor_identity_distinct
        CHECK (source_actor_id <> canonical_actor_id),
    CONSTRAINT ${tablePrefix}uq_actor_identity_source
        UNIQUE (project_id, source_actor_id)
);

CREATE INDEX ${tablePrefix}ix_actor_identity_canonical
    ON ${tablePrefix}actor_identity_links(project_id, canonical_actor_id);

-- Privacy deletion keeps only an irreversible canonical actor digest. This
-- prevents a delayed anonymous-to-cloud link from recreating an association
-- after the active aliases have been removed.
CREATE TABLE ${tablePrefix}actor_suppressions (
    project_id VARCHAR(50) NOT NULL,
    canonical_actor_sha256 CHAR(64) NOT NULL,
    suppressed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ${tablePrefix}pk_actor_suppressions
        PRIMARY KEY (project_id, canonical_actor_sha256)
);
