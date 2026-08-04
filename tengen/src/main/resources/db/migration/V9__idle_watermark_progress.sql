ALTER TABLE event_stream_watermarks
    ALTER COLUMN max_occurred_at DROP NOT NULL;
