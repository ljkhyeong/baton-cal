CREATE TABLE recovery_season_manifest (
    recovery_id UUID NOT NULL,
    season_id UUID NOT NULL,
    item_count INTEGER NOT NULL CHECK (item_count >= 0),
    item_digest CHAR(64) NOT NULL CHECK (item_digest ~ '^[0-9a-f]{64}$'),
    metadata_revision INTEGER,
    metadata_digest CHAR(64),
    verified_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (recovery_id, season_id),
    CONSTRAINT ck_recovery_season_manifest_metadata
        CHECK (
            (metadata_revision IS NULL AND metadata_digest IS NULL)
            OR (
                metadata_revision >= 0
                AND metadata_digest ~ '^[0-9a-f]{64}$'
            )
        )
);

CREATE TABLE recovery_run_completion (
    recovery_id UUID PRIMARY KEY,
    season_count INTEGER NOT NULL CHECK (season_count >= 0),
    season_digest CHAR(64) NOT NULL CHECK (season_digest ~ '^[0-9a-f]{64}$'),
    completed_at TIMESTAMPTZ NOT NULL
);
