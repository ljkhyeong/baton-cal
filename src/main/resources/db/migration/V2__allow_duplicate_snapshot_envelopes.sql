ALTER TABLE source_event_inbox
    DROP CONSTRAINT uq_source_event_inbox_item_revision;

CREATE INDEX ix_source_event_inbox_item_revision
    ON source_event_inbox (source_item_id, source_revision);
