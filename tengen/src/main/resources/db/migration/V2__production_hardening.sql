ALTER TABLE rules ADD COLUMN IF NOT EXISTS validation_status varchar(20);
ALTER TABLE rules ADD COLUMN IF NOT EXISTS validation_error varchar(1000);
UPDATE rules SET validation_status = 'VALID' WHERE validation_status IS NULL;
ALTER TABLE rules ALTER COLUMN validation_status SET DEFAULT 'VALID';
ALTER TABLE rules ALTER COLUMN validation_status SET NOT NULL;
ALTER TABLE rules DROP CONSTRAINT IF EXISTS rules_validation_status_check;
ALTER TABLE rules ADD CONSTRAINT rules_validation_status_check
    CHECK (validation_status IN ('VALID', 'INVALID'));

ALTER TABLE rules DROP CONSTRAINT IF EXISTS rules_trigger_mode_check;
ALTER TABLE rules ADD CONSTRAINT rules_trigger_mode_check
    CHECK (trigger_mode IS NULL OR trigger_mode IN ('EVERY_MATCH', 'EDGE', 'ONCE_PER_WINDOW'));

CREATE TABLE IF NOT EXISTS refresh_sessions (
    token_id varchar(36) PRIMARY KEY,
    token_hash varchar(64) NOT NULL UNIQUE,
    username varchar(100) NOT NULL,
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    used_at timestamptz,
    revoked_at timestamptz,
    replaced_by_token_id varchar(36)
);
CREATE INDEX IF NOT EXISTS idx_refresh_sessions_expires ON refresh_sessions (expires_at);

ALTER TABLE events ADD COLUMN IF NOT EXISTS received_at timestamptz;
UPDATE events SET received_at = now() WHERE received_at IS NULL;
ALTER TABLE events ALTER COLUMN received_at SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_events_occurred ON events (occurred_at, id);
CREATE INDEX IF NOT EXISTS idx_events_received ON events (received_at, id);
CREATE INDEX IF NOT EXISTS idx_rules_active_route
    ON rules (event_type, source, active) WHERE archived_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_webhook_outbox_terminal_created
    ON webhook_outbox (created_at, id) WHERE status IN ('DELIVERED', 'DEAD_LETTER');

CREATE OR REPLACE FUNCTION prevent_rule_delete_with_history()
RETURNS trigger AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM rule_revisions WHERE rule_id = OLD.id) THEN
        RAISE EXCEPTION 'Rule % has immutable revision history and must be archived', OLD.id;
    END IF;
    RETURN OLD;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_prevent_rule_delete_with_history ON rules;
CREATE TRIGGER trg_prevent_rule_delete_with_history
BEFORE DELETE ON rules
FOR EACH ROW EXECUTE FUNCTION prevent_rule_delete_with_history();

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fk_rule_revisions_rule'
    ) AND NOT EXISTS (
        SELECT 1 FROM rule_revisions rr
        LEFT JOIN rules r ON r.id = rr.rule_id
        WHERE r.id IS NULL
    ) THEN
        ALTER TABLE rule_revisions
            ADD CONSTRAINT fk_rule_revisions_rule
            FOREIGN KEY (rule_id) REFERENCES rules(id) ON DELETE RESTRICT;
    END IF;
END $$;
