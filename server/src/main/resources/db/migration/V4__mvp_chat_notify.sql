CREATE TABLE task_chat_messages
(
    id         BIGSERIAL PRIMARY KEY,
    task_id    BIGINT       NOT NULL REFERENCES tasks (id) ON DELETE CASCADE,
    author_id  BIGINT REFERENCES users (id) ON DELETE SET NULL,
    type       VARCHAR(16)  NOT NULL DEFAULT 'USER' CHECK (type IN ('USER', 'SYSTEM')),
    content    TEXT         NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_task_chat_messages_task ON task_chat_messages (task_id, created_at);

CREATE TABLE notifications
(
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    type        VARCHAR(64)  NOT NULL,
    entity_type VARCHAR(64)  NOT NULL,
    entity_id   BIGINT       NOT NULL,
    title       VARCHAR(500) NOT NULL,
    is_read     BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_notifications_user ON notifications (user_id, is_read, created_at);
CREATE INDEX idx_notifications_entity ON notifications (entity_type, entity_id);

CREATE TABLE attachments
(
    id           BIGSERIAL PRIMARY KEY,
    entity_type  VARCHAR(64)  NOT NULL,
    entity_id    BIGINT       NOT NULL,
    file_name    VARCHAR(500) NOT NULL,
    storage_path VARCHAR(1000) NOT NULL,
    size_bytes   BIGINT,
    uploaded_by  BIGINT       NOT NULL REFERENCES users (id),
    uploaded_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_attachments_entity ON attachments (entity_type, entity_id);
