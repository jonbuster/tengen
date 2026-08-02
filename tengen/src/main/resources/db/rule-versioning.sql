-- Rule versioning migration.
-- Run with the application and webhook worker stopped. It is safe to run again.
-- Legacy rules were intentionally removed before this migration; preserved
-- outbox history is assigned revision 1.

ALTER TABLE rules ADD COLUMN IF NOT EXISTS revision integer;
ALTER TABLE rules ADD COLUMN IF NOT EXISTS archived_at timestamptz;
UPDATE rules SET revision = 1 WHERE revision IS NULL OR revision < 1;
ALTER TABLE rules ALTER COLUMN revision SET DEFAULT 1;
ALTER TABLE rules ALTER COLUMN revision SET NOT NULL;

ALTER TABLE rule_events ADD COLUMN IF NOT EXISTS rule_revision integer;
UPDATE rule_events re
SET rule_revision = COALESCE(r.revision, 1)
FROM rules r
WHERE re.rule_id = r.id AND re.rule_revision IS NULL;
UPDATE rule_events SET rule_revision = 1 WHERE rule_revision IS NULL OR rule_revision < 1;
ALTER TABLE rule_events ALTER COLUMN rule_revision SET DEFAULT 1;
ALTER TABLE rule_events ALTER COLUMN rule_revision SET NOT NULL;
CREATE INDEX IF NOT EXISTS idx_rule_events_rule_revision_group_occurred
    ON rule_events (rule_id, rule_revision, group_key, occurred_at);

ALTER TABLE rule_action_state ADD COLUMN IF NOT EXISTS rule_revision integer;
UPDATE rule_action_state state
SET rule_revision = COALESCE(r.revision, 1)
FROM rules r
WHERE state.rule_id = r.id AND state.rule_revision IS NULL;
UPDATE rule_action_state SET rule_revision = 1 WHERE rule_revision IS NULL OR rule_revision < 1;
ALTER TABLE rule_action_state ALTER COLUMN rule_revision SET DEFAULT 1;
ALTER TABLE rule_action_state ALTER COLUMN rule_revision SET NOT NULL;
ALTER TABLE rule_action_state DROP CONSTRAINT IF EXISTS uk_rule_action_state_rule_scope;
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'uk_rule_action_state_rule_revision_scope'
    ) THEN
        ALTER TABLE rule_action_state
            ADD CONSTRAINT uk_rule_action_state_rule_revision_scope
            UNIQUE (rule_id, rule_revision, scope_key);
    END IF;
END $$;

ALTER TABLE rule_action_windows ADD COLUMN IF NOT EXISTS rule_revision integer;
UPDATE rule_action_windows state
SET rule_revision = COALESCE(r.revision, 1)
FROM rules r
WHERE state.rule_id = r.id AND state.rule_revision IS NULL;
UPDATE rule_action_windows SET rule_revision = 1 WHERE rule_revision IS NULL OR rule_revision < 1;
ALTER TABLE rule_action_windows ALTER COLUMN rule_revision SET DEFAULT 1;
ALTER TABLE rule_action_windows ALTER COLUMN rule_revision SET NOT NULL;
ALTER TABLE rule_action_windows DROP CONSTRAINT IF EXISTS uk_rule_action_window_scope_start;
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'uk_rule_action_window_rule_revision_scope_start'
    ) THEN
        ALTER TABLE rule_action_windows
            ADD CONSTRAINT uk_rule_action_window_rule_revision_scope_start
            UNIQUE (rule_id, rule_revision, scope_key, window_start);
    END IF;
END $$;

ALTER TABLE webhook_outbox ADD COLUMN IF NOT EXISTS rule_revision integer;
UPDATE webhook_outbox SET rule_revision = 1 WHERE rule_revision IS NULL OR rule_revision < 1;
ALTER TABLE webhook_outbox ALTER COLUMN rule_revision SET DEFAULT 1;
ALTER TABLE webhook_outbox ALTER COLUMN rule_revision SET NOT NULL;
CREATE INDEX IF NOT EXISTS idx_webhook_outbox_rule_revision_created
    ON webhook_outbox (rule_id, rule_revision, created_at);

CREATE TABLE IF NOT EXISTS rule_revisions (
    id bigserial PRIMARY KEY,
    rule_id bigint NOT NULL,
    revision integer NOT NULL,
    change_type varchar(20) NOT NULL,
    actor varchar(100) NOT NULL,
    changed_at timestamptz NOT NULL DEFAULT now(),
    restored_from_revision integer,
    snapshot_schema_version integer NOT NULL DEFAULT 1,
    snapshot jsonb NOT NULL,
    CONSTRAINT uk_rule_revisions_rule_revision UNIQUE (rule_id, revision)
);
CREATE INDEX IF NOT EXISTS idx_rule_revisions_rule_changed
    ON rule_revisions (rule_id, changed_at);

