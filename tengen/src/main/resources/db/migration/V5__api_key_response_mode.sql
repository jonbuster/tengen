ALTER TABLE api_keys ADD COLUMN IF NOT EXISTS response_mode varchar(20);

UPDATE api_keys
SET response_mode = 'FULL'
WHERE response_mode IS NULL;

ALTER TABLE api_keys ALTER COLUMN response_mode SET DEFAULT 'COMPACT';
ALTER TABLE api_keys ALTER COLUMN response_mode SET NOT NULL;

ALTER TABLE api_keys DROP CONSTRAINT IF EXISTS api_keys_response_mode_check;
ALTER TABLE api_keys ADD CONSTRAINT api_keys_response_mode_check
    CHECK (response_mode IN ('FULL', 'COMPACT'));
