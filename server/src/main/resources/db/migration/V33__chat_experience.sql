ALTER TABLE conversation_messages
    ADD COLUMN reply_to_id BIGINT REFERENCES conversation_messages (id) ON DELETE SET NULL,
    ADD COLUMN edited_at TIMESTAMPTZ,
    ADD COLUMN revoked_at TIMESTAMPTZ,
    ADD COLUMN source_kind VARCHAR(16) CHECK (source_kind IN ('DIRECT', 'GROUP', 'PROJECT', 'TASK')),
    ADD COLUMN source_message_id BIGINT,
    ADD COLUMN source_author_id BIGINT REFERENCES users (id) ON DELETE SET NULL,
    ADD COLUMN source_created_at TIMESTAMPTZ,
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_conversation_messages_source
        CHECK ((source_kind IS NULL) = (source_message_id IS NULL));

CREATE INDEX idx_conversation_messages_reply ON conversation_messages (reply_to_id)
    WHERE reply_to_id IS NOT NULL;
CREATE INDEX idx_conversation_messages_source ON conversation_messages (source_kind, source_message_id)
    WHERE source_message_id IS NOT NULL;

CREATE TABLE conversation_message_revisions
(
    id               BIGSERIAL PRIMARY KEY,
    message_id       BIGINT      NOT NULL REFERENCES conversation_messages (id) ON DELETE CASCADE,
    previous_content TEXT        NOT NULL,
    edited_by        BIGINT      NOT NULL REFERENCES users (id),
    edited_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_conversation_message_revisions_message ON conversation_message_revisions (message_id, id);

CREATE TABLE conversation_reactions
(
    message_id BIGINT      NOT NULL REFERENCES conversation_messages (id) ON DELETE CASCADE,
    user_id    BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    emoji      VARCHAR(16) NOT NULL CHECK (BTRIM(emoji) <> ''),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (message_id, user_id, emoji)
);

CREATE INDEX idx_conversation_reactions_user ON conversation_reactions (user_id, message_id);

CREATE TABLE conversation_mentions
(
    message_id        BIGINT      NOT NULL REFERENCES conversation_messages (id) ON DELETE CASCADE,
    mentioned_user_id BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (message_id, mentioned_user_id)
);

CREATE INDEX idx_conversation_mentions_user ON conversation_mentions (mentioned_user_id, message_id);

CREATE TABLE conversation_read_states
(
    conversation_id      BIGINT      NOT NULL REFERENCES conversations (id) ON DELETE CASCADE,
    user_id              BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    last_read_message_id BIGINT      NOT NULL DEFAULT 0,
    last_read_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (conversation_id, user_id)
);

CREATE INDEX idx_conversation_read_states_user ON conversation_read_states (user_id, conversation_id);

CREATE TABLE conversation_dialog_states
(
    conversation_id BIGINT      NOT NULL REFERENCES conversations (id) ON DELETE CASCADE,
    user_id         BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    pinned          BOOLEAN     NOT NULL DEFAULT FALSE,
    muted           BOOLEAN     NOT NULL DEFAULT FALSE,
    archived        BOOLEAN     NOT NULL DEFAULT FALSE,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (conversation_id, user_id)
);

CREATE INDEX idx_conversation_dialog_states_user ON conversation_dialog_states (user_id, conversation_id);

ALTER TABLE task_chat_messages
    ADD COLUMN edited_at TIMESTAMPTZ,
    ADD COLUMN revoked_at TIMESTAMPTZ,
    ADD COLUMN source_kind VARCHAR(16) CHECK (source_kind IN ('DIRECT', 'GROUP', 'PROJECT', 'TASK')),
    ADD COLUMN source_message_id BIGINT,
    ADD COLUMN source_author_id BIGINT REFERENCES users (id) ON DELETE SET NULL,
    ADD COLUMN source_created_at TIMESTAMPTZ,
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_task_chat_messages_source
        CHECK ((source_kind IS NULL) = (source_message_id IS NULL));

CREATE INDEX idx_task_chat_messages_source ON task_chat_messages (source_kind, source_message_id)
    WHERE source_message_id IS NOT NULL;

CREATE TABLE task_chat_message_revisions
(
    id               BIGSERIAL PRIMARY KEY,
    message_id       BIGINT      NOT NULL REFERENCES task_chat_messages (id) ON DELETE CASCADE,
    previous_content TEXT        NOT NULL,
    edited_by        BIGINT      NOT NULL REFERENCES users (id),
    edited_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_task_chat_message_revisions_message ON task_chat_message_revisions (message_id, id);

CREATE TABLE chat_push_subscriptions
(
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    endpoint   TEXT        NOT NULL UNIQUE,
    p256dh     TEXT        NOT NULL CHECK (BTRIM(p256dh) <> ''),
    auth       TEXT        NOT NULL CHECK (BTRIM(auth) <> ''),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_chat_push_subscriptions_user ON chat_push_subscriptions (user_id);
