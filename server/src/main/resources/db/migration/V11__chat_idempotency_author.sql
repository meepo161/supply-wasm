-- Идемпотентность сообщения уникальна по задаче, автору и ключу.
DROP INDEX IF EXISTS idx_task_chat_messages_task_idempotency_key;

CREATE UNIQUE INDEX IF NOT EXISTS idx_task_chat_messages_author_idempotency_key
    ON task_chat_messages (task_id, author_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
