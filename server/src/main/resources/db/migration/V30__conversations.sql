CREATE TABLE conversations
(
    id         BIGSERIAL PRIMARY KEY,
    kind       VARCHAR(16)  NOT NULL CHECK (kind IN ('DIRECT', 'GROUP', 'PROJECT')),
    title      VARCHAR(500),
    project_id BIGINT       UNIQUE REFERENCES projects (id) ON DELETE CASCADE,
    direct_key VARCHAR(80)  UNIQUE,
    created_by BIGINT       NOT NULL REFERENCES users (id),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CHECK ((kind = 'PROJECT') = (project_id IS NOT NULL)),
    CHECK ((kind = 'DIRECT') = (direct_key IS NOT NULL))
);

CREATE TABLE conversation_participants
(
    conversation_id BIGINT      NOT NULL REFERENCES conversations (id) ON DELETE CASCADE,
    user_id         BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    joined_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (conversation_id, user_id)
);

CREATE INDEX idx_conversation_participants_user ON conversation_participants (user_id, conversation_id);

CREATE TABLE conversation_messages
(
    id              BIGSERIAL PRIMARY KEY,
    conversation_id BIGINT       NOT NULL REFERENCES conversations (id) ON DELETE CASCADE,
    author_id       BIGINT       NOT NULL REFERENCES users (id),
    content         TEXT         NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    UNIQUE (conversation_id, author_id, idempotency_key)
);

CREATE INDEX idx_conversation_messages_conversation ON conversation_messages (conversation_id, id);
