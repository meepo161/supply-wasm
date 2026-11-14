ALTER TABLE demands
    ADD COLUMN IF NOT EXISTS idempotency_request_hash VARCHAR(64);

DROP INDEX IF EXISTS idx_demands_idempotency_key;

CREATE UNIQUE INDEX idx_demands_idempotency_key
    ON demands (created_by, idempotency_key) WHERE idempotency_key IS NOT NULL;
