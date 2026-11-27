-- Мессенджер уровня Telegram / Битрикс24:
-- 1. Закреплённые сообщения, треды, тихие и отложенные сообщения
-- 2. Каналы компании и Избранное (Saved Messages)
-- 3. Опросы (polls)
-- 4. Статус присутствия пользователей (presence)

ALTER TABLE conversations
    DROP CONSTRAINT IF EXISTS conversations_kind_check;

ALTER TABLE conversations
    ADD CONSTRAINT conversations_kind_check CHECK (kind IN ('DIRECT', 'GROUP', 'PROJECT', 'CHANNEL'));

ALTER TABLE conversations
    ADD COLUMN IF NOT EXISTS is_saved_messages BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS description VARCHAR(1000);

ALTER TABLE conversation_participants
    ADD COLUMN IF NOT EXISTS role VARCHAR(16) NOT NULL DEFAULT 'MEMBER';

ALTER TABLE conversation_messages
    ADD COLUMN IF NOT EXISTS thread_root_id BIGINT REFERENCES conversation_messages (id) ON DELETE CASCADE,
    ADD COLUMN IF NOT EXISTS is_silent BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS scheduled_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS delivered BOOLEAN NOT NULL DEFAULT TRUE;

CREATE INDEX IF NOT EXISTS idx_conv_messages_thread_root
    ON conversation_messages (thread_root_id, id)
    WHERE thread_root_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_conv_messages_scheduled
    ON conversation_messages (delivered, scheduled_at)
    WHERE delivered = FALSE;

CREATE TABLE IF NOT EXISTS conversation_pinned_messages
(
    id              BIGSERIAL PRIMARY KEY,
    conversation_id BIGINT      NOT NULL REFERENCES conversations (id) ON DELETE CASCADE,
    message_id      BIGINT      NOT NULL REFERENCES conversation_messages (id) ON DELETE CASCADE,
    pinned_by       BIGINT      NOT NULL REFERENCES users (id),
    pinned_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (conversation_id, message_id)
);

CREATE INDEX IF NOT EXISTS idx_conv_pins_conv
    ON conversation_pinned_messages (conversation_id, pinned_at DESC);

CREATE TABLE IF NOT EXISTS conversation_polls
(
    id              BIGSERIAL PRIMARY KEY,
    conversation_id BIGINT       NOT NULL REFERENCES conversations (id) ON DELETE CASCADE,
    message_id      BIGINT       NOT NULL REFERENCES conversation_messages (id) ON DELETE CASCADE,
    question        VARCHAR(500) NOT NULL,
    is_anonymous    BOOLEAN      NOT NULL DEFAULT FALSE,
    is_multiple     BOOLEAN      NOT NULL DEFAULT FALSE,
    created_by      BIGINT       NOT NULL REFERENCES users (id),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    closed_at       TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS conversation_poll_options
(
    id         BIGSERIAL PRIMARY KEY,
    poll_id    BIGINT       NOT NULL REFERENCES conversation_polls (id) ON DELETE CASCADE,
    label      VARCHAR(300) NOT NULL,
    sort_order INT          NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS conversation_poll_votes
(
    poll_id    BIGINT      NOT NULL REFERENCES conversation_polls (id) ON DELETE CASCADE,
    option_id  BIGINT      NOT NULL REFERENCES conversation_poll_options (id) ON DELETE CASCADE,
    user_id    BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (option_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_conv_polls_message
    ON conversation_polls (message_id);

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS presence_status VARCHAR(16) NOT NULL DEFAULT 'ONLINE',
    ADD COLUMN IF NOT EXISTS last_seen_at TIMESTAMPTZ;
