ALTER TABLE calendar_subscription
    ADD COLUMN credential_generation UUID NOT NULL
        DEFAULT '00000000-0000-0000-0000-000000000001';

ALTER TABLE calendar_subscription
    ALTER COLUMN credential_generation DROP DEFAULT;
