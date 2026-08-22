ALTER TABLE calendar_item
    ADD COLUMN starts_on_date DATE,
    ADD COLUMN ends_on_date DATE;

ALTER TABLE calendar_item
    DROP CONSTRAINT ck_calendar_item_time_type,
    DROP CONSTRAINT ck_calendar_item_time_shape,
    ADD CONSTRAINT ck_calendar_item_time_type
        CHECK (time_type IN (
            'UTC_INSTANT',
            'UTC_POINT',
            'ZONED_LOCAL',
            'ZONED_LOCAL_POINT',
            'ALL_DAY'
        )),
    ADD CONSTRAINT ck_calendar_item_time_shape
        CHECK (
            (
                time_type = 'UTC_INSTANT'
                AND starts_at_instant IS NOT NULL
                AND ends_at_instant IS NOT NULL
                AND ends_at_instant > starts_at_instant
                AND starts_at_local IS NULL
                AND ends_at_local IS NULL
                AND zone_id IS NULL
                AND starts_on_date IS NULL
                AND ends_on_date IS NULL
            )
            OR
            (
                time_type = 'UTC_POINT'
                AND starts_at_instant IS NOT NULL
                AND ends_at_instant IS NULL
                AND starts_at_local IS NULL
                AND ends_at_local IS NULL
                AND zone_id IS NULL
                AND starts_on_date IS NULL
                AND ends_on_date IS NULL
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
                AND starts_on_date IS NULL
                AND ends_on_date IS NULL
            )
            OR
            (
                time_type = 'ZONED_LOCAL_POINT'
                AND starts_at_instant IS NULL
                AND ends_at_instant IS NULL
                AND starts_at_local IS NOT NULL
                AND ends_at_local IS NULL
                AND zone_id IS NOT NULL
                AND length(zone_id) > 0
                AND starts_on_date IS NULL
                AND ends_on_date IS NULL
            )
            OR
            (
                time_type = 'ALL_DAY'
                AND starts_at_instant IS NULL
                AND ends_at_instant IS NULL
                AND starts_at_local IS NULL
                AND ends_at_local IS NULL
                AND zone_id IS NULL
                AND starts_on_date IS NOT NULL
                AND ends_on_date IS NOT NULL
                AND ends_on_date > starts_on_date
            )
        );
