CREATE TABLE season_calendar_metadata (
    season_id UUID PRIMARY KEY,
    revision INTEGER NOT NULL CHECK (revision >= 0),
    display_name TEXT NOT NULL CHECK (length(display_name) BETWEEN 1 AND 512),
    accepted_at TIMESTAMPTZ NOT NULL
);
