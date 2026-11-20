ALTER TABLE task_chat_messages
    ADD COLUMN reply_to_id BIGINT REFERENCES task_chat_messages (id) ON DELETE SET NULL;

CREATE INDEX idx_task_chat_messages_reply ON task_chat_messages (reply_to_id)
    WHERE reply_to_id IS NOT NULL;

CREATE TABLE task_chat_read_states
(
    task_id               BIGINT      NOT NULL REFERENCES tasks (id) ON DELETE CASCADE,
    user_id               BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    last_read_message_id  BIGINT      NOT NULL DEFAULT 0,
    last_read_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (task_id, user_id)
);

CREATE TABLE task_chat_mentions
(
    message_id        BIGINT      NOT NULL REFERENCES task_chat_messages (id) ON DELETE CASCADE,
    mentioned_user_id BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (message_id, mentioned_user_id)
);

CREATE INDEX idx_task_chat_mentions_user ON task_chat_mentions (mentioned_user_id, message_id);

CREATE TABLE task_chat_reactions
(
    message_id BIGINT      NOT NULL REFERENCES task_chat_messages (id) ON DELETE CASCADE,
    user_id    BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    emoji      VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (message_id, user_id, emoji)
);

CREATE INDEX idx_task_chat_reactions_message ON task_chat_reactions (message_id);

CREATE TABLE task_chat_dialog_pins
(
    task_id   BIGINT      NOT NULL REFERENCES tasks (id) ON DELETE CASCADE,
    user_id   BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    pinned_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (task_id, user_id)
);

CREATE INDEX idx_task_chat_dialog_pins_user ON task_chat_dialog_pins (user_id, task_id);
