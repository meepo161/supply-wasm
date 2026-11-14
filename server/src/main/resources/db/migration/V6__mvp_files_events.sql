-- V6: MVP files/events — enrich attachment metadata, add idempotency key to Task Chat messages,
-- and add cursor indexes for messages/notifications.

-- Attachment metadata enrichment: MIME type and content hash for validation/deduplication.
-- size_bytes, uploaded_by and uploaded_at already exist in V4.
ALTER TABLE attachments
    ADD COLUMN IF NOT EXISTS mime_type VARCHAR(255),
    ADD COLUMN IF NOT EXISTS sha256 VARCHAR(64);

-- Idempotency key for Task Chat messages, with a unique index to guard against duplicate posts.
ALTER TABLE task_chat_messages
    ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(255);

CREATE UNIQUE INDEX IF NOT EXISTS idx_task_chat_messages_idempotency_key
    ON task_chat_messages (idempotency_key)
    WHERE idempotency_key IS NOT NULL;

-- Cursor indexes for stable backward pagination by (created_at, id).
CREATE INDEX IF NOT EXISTS idx_task_chat_messages_task_cursor
    ON task_chat_messages (task_id, created_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_notifications_user_cursor
    ON notifications (user_id, is_read, created_at DESC, id DESC);
