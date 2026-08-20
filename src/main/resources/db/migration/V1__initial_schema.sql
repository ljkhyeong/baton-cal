CREATE TABLE source_event_inbox (
    event_id UUID PRIMARY KEY,
    payload_hash CHAR(64) NOT NULL,
    source_item_id UUID NOT NULL,
    season_id UUID NOT NULL,
    source_revision INTEGER NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_source_event_inbox_payload_hash
        CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_source_event_inbox_revision
        CHECK (source_revision BETWEEN 0 AND 2147483647),
    CONSTRAINT uq_source_event_inbox_item_revision
        UNIQUE (source_item_id, source_revision)
);

CREATE INDEX ix_source_event_inbox_season_received
    ON source_event_inbox (season_id, received_at);

-- A transaction takes SELECT ... FOR UPDATE on this row before replacing a
-- season projection. Keeping the lock separate from the optional feed row also
-- serializes the first projection build.
CREATE TABLE season_projection_lock (
    season_id UUID PRIMARY KEY
);

CREATE TABLE calendar_item (
    source_item_id UUID PRIMARY KEY,
    season_id UUID NOT NULL,
    revision INTEGER NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    summary TEXT NOT NULL,
    description TEXT,
    location TEXT,
    time_type VARCHAR(24) NOT NULL,
    starts_at_instant TIMESTAMPTZ,
    ends_at_instant TIMESTAMPTZ,
    starts_at_local TIMESTAMP WITHOUT TIME ZONE,
    ends_at_local TIMESTAMP WITHOUT TIME ZONE,
    zone_id VARCHAR(255),
    source_updated_at TIMESTAMPTZ NOT NULL,
    accepted_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_calendar_item_revision
        CHECK (revision BETWEEN 0 AND 2147483647),
    CONSTRAINT ck_calendar_item_payload_hash
        CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_calendar_item_status
        CHECK (status IN ('ACTIVE', 'CANCELLED')),
    CONSTRAINT ck_calendar_item_summary
        CHECK (length(summary) > 0),
    CONSTRAINT ck_calendar_item_time_type
        CHECK (time_type IN ('UTC_INSTANT', 'ZONED_LOCAL')),
    CONSTRAINT ck_calendar_item_time_shape
        CHECK (
            (
                time_type = 'UTC_INSTANT'
                AND starts_at_instant IS NOT NULL
                AND ends_at_instant IS NOT NULL
                AND ends_at_instant > starts_at_instant
                AND starts_at_local IS NULL
                AND ends_at_local IS NULL
                AND zone_id IS NULL
            )
            OR
            (
                time_type = 'ZONED_LOCAL'
                AND starts_at_instant IS NULL
                AND ends_at_instant IS NULL
                AND starts_at_local IS NOT NULL
                AND ends_at_local IS NOT NULL
                AND ends_at_local > starts_at_local
                AND zone_id IS NOT NULL
                AND length(zone_id) > 0
            )
        )
);

CREATE INDEX ix_calendar_item_season_order
    ON calendar_item (season_id, source_item_id);

CREATE TABLE season_feed_projection (
    season_id UUID PRIMARY KEY,
    representation BYTEA NOT NULL,
    etag TEXT NOT NULL,
    last_modified TIMESTAMPTZ NOT NULL,
    item_count INTEGER NOT NULL,
    rebuilt_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_season_feed_projection_etag
        CHECK (length(etag) > 0),
    CONSTRAINT ck_season_feed_projection_item_count
        CHECK (item_count >= 0)
);

CREATE TABLE calendar_subscription (
    id UUID PRIMARY KEY,
    season_id UUID NOT NULL,
    token_hash CHAR(64) NOT NULL UNIQUE,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    rotated_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    CONSTRAINT ck_calendar_subscription_token_hash
        CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_calendar_subscription_status
        CHECK (status IN ('ACTIVE', 'REVOKED')),
    CONSTRAINT ck_calendar_subscription_status_timestamp
        CHECK (
            (status = 'ACTIVE' AND revoked_at IS NULL)
            OR (status = 'REVOKED' AND revoked_at IS NOT NULL)
        ),
    CONSTRAINT ck_calendar_subscription_rotated_at
        CHECK (rotated_at IS NULL OR rotated_at >= created_at),
    CONSTRAINT ck_calendar_subscription_revoked_at
        CHECK (revoked_at IS NULL OR revoked_at >= created_at)
);

CREATE INDEX ix_calendar_subscription_season_status
    ON calendar_subscription (season_id, status);
