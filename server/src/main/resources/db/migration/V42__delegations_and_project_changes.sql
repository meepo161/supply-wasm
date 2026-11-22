-- IAM-13: Делегирование полномочий
CREATE TABLE delegations
(
    id           BIGSERIAL PRIMARY KEY,
    delegator_id BIGINT      NOT NULL REFERENCES users (id),
    delegate_id  BIGINT      NOT NULL REFERENCES users (id),
    starts_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    ends_at      TIMESTAMPTZ NOT NULL,
    reason       TEXT,
    active       BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_delegation_diff_users CHECK (delegator_id <> delegate_id)
);
CREATE INDEX idx_delegations_delegator ON delegations (delegator_id, active);
CREATE INDEX idx_delegations_delegate ON delegations (delegate_id, active);

-- TASK-33: Связи задачи с объектами ERP
CREATE TABLE task_object_links
(
    id          BIGSERIAL PRIMARY KEY,
    task_id     BIGINT      NOT NULL REFERENCES tasks (id) ON DELETE CASCADE,
    object_type VARCHAR(32) NOT NULL,
    object_id   BIGINT      NOT NULL,
    note        TEXT,
    created_by  BIGINT      NOT NULL REFERENCES users (id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_task_object_link UNIQUE (task_id, object_type, object_id)
);
CREATE INDEX idx_task_object_links_task ON task_object_links (task_id);

-- PRJ-11: Запросы на изменение проекта
CREATE TABLE project_change_requests
(
    id           BIGSERIAL PRIMARY KEY,
    project_id   BIGINT       NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    title        VARCHAR(255) NOT NULL,
    reason       TEXT         NOT NULL,
    new_end_date DATE,
    status       VARCHAR(32)  NOT NULL DEFAULT 'OPEN',
    requested_by BIGINT       NOT NULL REFERENCES users (id),
    decided_by   BIGINT REFERENCES users (id),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_pcr_status CHECK (status IN ('OPEN', 'APPROVED', 'REJECTED'))
);
CREATE INDEX idx_pcr_project ON project_change_requests (project_id);

-- WMS-03: Вместимость и совместимость ячеек
ALTER TABLE storage_locations
    ADD COLUMN IF NOT EXISTS max_capacity NUMERIC(19, 4),
    ADD COLUMN IF NOT EXISTS allowed_type VARCHAR(32);

-- INV-06: Идемпотентность создания задач
ALTER TABLE tasks
    ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(128);
CREATE UNIQUE INDEX IF NOT EXISTS uq_tasks_idempotency
    ON tasks (initiator_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
