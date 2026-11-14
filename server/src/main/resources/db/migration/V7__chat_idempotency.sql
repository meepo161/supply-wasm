-- V7: Task Chat idempotency is scoped per task.
-- The same idempotency_key is allowed in different tasks; the uniqueness guard moves
-- from the global column to the (task_id, idempotency_key) pair.

DROP INDEX IF EXISTS idx_task_chat_messages_idempotency_key;

CREATE UNIQUE INDEX IF NOT EXISTS idx_task_chat_messages_task_idempotency_key
    ON task_chat_messages (task_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
