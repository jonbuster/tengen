-- Null preserves the distinction between new explicit behavior and legacy rows.
ALTER TABLE events ADD COLUMN IF NOT EXISTS watermark_applied boolean;
