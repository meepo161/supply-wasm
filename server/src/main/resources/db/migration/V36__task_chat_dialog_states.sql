CREATE TABLE task_chat_dialog_states
(
    task_id    BIGINT      NOT NULL REFERENCES tasks (id) ON DELETE CASCADE,
    user_id    BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    muted      BOOLEAN     NOT NULL DEFAULT FALSE,
    archived   BOOLEAN     NOT NULL DEFAULT FALSE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (task_id, user_id)
);

CREATE INDEX idx_task_chat_dialog_states_user ON task_chat_dialog_states (user_id, task_id);
