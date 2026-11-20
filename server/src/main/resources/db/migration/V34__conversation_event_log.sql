CREATE TABLE conversation_event_log
(
    id              BIGSERIAL PRIMARY KEY,
    conversation_id BIGINT      NOT NULL REFERENCES conversations (id) ON DELETE CASCADE,
    category        VARCHAR(16) NOT NULL CHECK (category IN ('MESSAGE', 'EDITED', 'REVOKED', 'REACTION', 'READ')),
    message_id      BIGINT REFERENCES conversation_messages (id) ON DELETE CASCADE,
    actor_user_id   BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_conversation_event_log_cursor ON conversation_event_log (conversation_id, id);
