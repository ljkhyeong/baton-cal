DROP INDEX ix_source_event_inbox_season_received;

DROP INDEX ix_calendar_subscription_season_status;

DROP INDEX ix_calendar_item_season_order;
CREATE INDEX ix_calendar_item_season
    ON calendar_item (season_id);

ALTER TABLE calendar_item
    DROP CONSTRAINT ck_calendar_item_payload_hash,
    DROP COLUMN payload_hash;

ALTER TABLE season_feed_projection
    DROP COLUMN rebuilt_at;

ALTER TABLE calendar_subscription
    DROP CONSTRAINT ck_calendar_subscription_status_timestamp,
    DROP CONSTRAINT ck_calendar_subscription_rotated_at,
    DROP CONSTRAINT ck_calendar_subscription_revoked_at,
    DROP COLUMN created_at,
    DROP COLUMN rotated_at,
    DROP COLUMN revoked_at;

-- calendar_subscription은 projection을 만든 뒤에만 추가되므로 누락 투영을 DB가 막는다.
ALTER TABLE calendar_subscription
    ADD CONSTRAINT fk_calendar_subscription_projection
        FOREIGN KEY (season_id)
        REFERENCES season_feed_projection (season_id);
