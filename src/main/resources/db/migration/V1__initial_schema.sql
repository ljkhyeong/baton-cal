-- 같은 내용을 다른 eventId로 다시 보낸 전달도 모두 남긴다. 같은 원본 개정 번호의 동일성은
-- 시즌 잠금 안에서 payload_hash로 판정한다.
CREATE TABLE source_event_inbox (
    event_id UUID PRIMARY KEY,
    payload_hash CHAR(64) NOT NULL,
    source_item_id UUID NOT NULL,
    source_revision INTEGER NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_source_event_inbox_payload_hash
        CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_source_event_inbox_revision
        CHECK (source_revision >= 0)
);

CREATE INDEX ix_source_event_inbox_item_revision
    ON source_event_inbox (source_item_id, source_revision);

-- 시간 형태마다 사용하는 열이 다르다. 형태별 필수 열은 CASE의 비교식이 NOT NULL까지 보장하고,
-- 시간 열 전체의 NOT NULL 개수가 필수 열 수와 같아야 하므로 나머지 시간 열은 비어 있다.
CREATE TABLE calendar_item (
    source_item_id UUID PRIMARY KEY,
    season_id UUID NOT NULL,
    revision INTEGER NOT NULL,
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
    starts_on_date DATE,
    ends_on_date DATE,
    source_updated_at TIMESTAMPTZ NOT NULL,
    accepted_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_calendar_item_revision
        CHECK (revision >= 0),
    CONSTRAINT ck_calendar_item_status
        CHECK (status IN ('ACTIVE', 'CANCELLED')),
    CONSTRAINT ck_calendar_item_summary
        CHECK (length(summary) > 0),
    CONSTRAINT ck_calendar_item_time_shape
        CHECK (
            num_nonnulls(
                starts_at_instant, ends_at_instant, starts_at_local, ends_at_local,
                zone_id, starts_on_date, ends_on_date
            ) = CASE time_type WHEN 'UTC_POINT' THEN 1 WHEN 'ZONED_LOCAL' THEN 3 ELSE 2 END
            AND (
                CASE time_type
                    WHEN 'UTC_INSTANT' THEN ends_at_instant > starts_at_instant
                    WHEN 'UTC_POINT' THEN starts_at_instant IS NOT NULL
                    WHEN 'ZONED_LOCAL' THEN ends_at_local > starts_at_local AND length(zone_id) > 0
                    WHEN 'ZONED_LOCAL_POINT' THEN starts_at_local IS NOT NULL AND length(zone_id) > 0
                    WHEN 'ALL_DAY' THEN ends_on_date > starts_on_date
                END
            ) IS TRUE
        )
);

CREATE INDEX ix_calendar_item_season
    ON calendar_item (season_id);

CREATE TABLE season_feed_projection (
    season_id UUID PRIMARY KEY,
    representation BYTEA NOT NULL,
    etag TEXT NOT NULL,
    last_modified TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_season_feed_projection_etag
        CHECK (length(etag) > 0)
);

-- 구독은 시즌 투영을 만든 뒤에만 추가되므로 투영 없는 구독을 DB가 막는다.
CREATE TABLE calendar_subscription (
    id UUID PRIMARY KEY,
    season_id UUID NOT NULL,
    token_hash CHAR(64) NOT NULL UNIQUE,
    credential_generation UUID NOT NULL,
    status VARCHAR(16) NOT NULL,
    CONSTRAINT ck_calendar_subscription_token_hash
        CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_calendar_subscription_status
        CHECK (status IN ('ACTIVE', 'REVOKED')),
    CONSTRAINT fk_calendar_subscription_projection
        FOREIGN KEY (season_id)
        REFERENCES season_feed_projection (season_id)
);

CREATE TABLE season_calendar_metadata (
    season_id UUID PRIMARY KEY,
    revision INTEGER NOT NULL CHECK (revision >= 0),
    display_name TEXT NOT NULL CHECK (length(display_name) BETWEEN 1 AND 512),
    accepted_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE recovery_season_manifest (
    recovery_id UUID NOT NULL,
    season_id UUID NOT NULL,
    item_count INTEGER NOT NULL CHECK (item_count >= 0),
    item_digest CHAR(64) NOT NULL CHECK (item_digest ~ '^[0-9a-f]{64}$'),
    metadata_revision INTEGER CHECK (metadata_revision >= 0),
    metadata_digest CHAR(64) CHECK (metadata_digest ~ '^[0-9a-f]{64}$'),
    verified_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (recovery_id, season_id),
    -- 시즌 이름 개정 번호와 다이제스트는 함께 저장하거나 함께 비운다.
    CONSTRAINT ck_recovery_season_manifest_metadata
        CHECK (num_nulls(metadata_revision, metadata_digest) <> 1)
);

CREATE TABLE recovery_run_completion (
    recovery_id UUID PRIMARY KEY,
    season_count INTEGER NOT NULL CHECK (season_count >= 0),
    season_digest CHAR(64) NOT NULL CHECK (season_digest ~ '^[0-9a-f]{64}$'),
    completed_at TIMESTAMPTZ NOT NULL
);
