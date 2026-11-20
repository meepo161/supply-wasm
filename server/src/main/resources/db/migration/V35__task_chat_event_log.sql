CREATE TABLE task_chat_event_log
(
    id            BIGSERIAL PRIMARY KEY,
    task_id       BIGINT      NOT NULL REFERENCES tasks (id) ON DELETE CASCADE,
    category      VARCHAR(16) NOT NULL CHECK (category IN ('MESSAGE', 'EDITED', 'REVOKED', 'REACTION', 'READ', 'PIN')),
    message_id    BIGINT REFERENCES task_chat_messages (id) ON DELETE CASCADE,
    actor_user_id BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    is_pinned     BOOLEAN,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CHECK ((category = 'PIN') = (is_pinned IS NOT NULL))
);

CREATE INDEX idx_task_chat_event_log_cursor ON task_chat_event_log (task_id, id);
