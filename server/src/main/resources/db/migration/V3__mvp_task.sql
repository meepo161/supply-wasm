CREATE TABLE tasks
(
    id            BIGSERIAL PRIMARY KEY,
    number        VARCHAR(64) NOT NULL UNIQUE,
    area          VARCHAR(32) NOT NULL CHECK (area IN ('PERSONAL', 'DEPARTMENT', 'PROJECT')),
    status        VARCHAR(32) NOT NULL,
    type          VARCHAR(64) NOT NULL,
    title         VARCHAR(500) NOT NULL,
    description   TEXT,
    initiator_id  BIGINT      NOT NULL REFERENCES users (id),
    assignee_id   BIGINT REFERENCES users (id),
    reviewer_id   BIGINT REFERENCES users (id),
    project_id    BIGINT REFERENCES projects (id),
    department_id BIGINT REFERENCES departments (id),
    due_date      TIMESTAMPTZ,
    result        TEXT,
    closed_at     TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by    BIGINT      NOT NULL REFERENCES users (id),
    version       BIGINT      NOT NULL DEFAULT 0
);

CREATE INDEX idx_tasks_number ON tasks (number);
CREATE INDEX idx_tasks_status ON tasks (status);
CREATE INDEX idx_tasks_initiator ON tasks (initiator_id);
CREATE INDEX idx_tasks_assignee ON tasks (assignee_id);
CREATE INDEX idx_tasks_project ON tasks (project_id);
CREATE INDEX idx_tasks_department ON tasks (department_id);

CREATE TABLE task_participants
(
    task_id BIGINT      NOT NULL REFERENCES tasks (id) ON DELETE CASCADE,
    user_id BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role    VARCHAR(32) NOT NULL CHECK (role IN ('ASSIGNEE', 'COEXECUTOR', 'OBSERVER', 'INITIATOR', 'REVIEWER')),
    PRIMARY KEY (task_id, user_id)
);

CREATE INDEX idx_task_participants_user ON task_participants (user_id);

CREATE TABLE task_status_history
(
    id         BIGSERIAL PRIMARY KEY,
    task_id    BIGINT      NOT NULL REFERENCES tasks (id) ON DELETE CASCADE,
    status     VARCHAR(32) NOT NULL,
    changed_by BIGINT      NOT NULL REFERENCES users (id),
    reason     TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_task_status_history_task ON task_status_history (task_id, created_at);
