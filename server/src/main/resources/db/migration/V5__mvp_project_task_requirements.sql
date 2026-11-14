-- V5: MVP-02/SPEC project and task requirements
-- Add project card fields, project type/status constraints, task priority/blocking/dictionary, area and reviewer checks.

-- Projects: additional fields
ALTER TABLE projects
    ADD COLUMN full_name VARCHAR(500),
    ADD COLUMN project_type VARCHAR(32),
    ADD COLUMN customer VARCHAR(255),
    ADD COLUMN planned_start_at TIMESTAMPTZ,
    ADD COLUMN planned_end_at TIMESTAMPTZ,
    ADD COLUMN priority VARCHAR(16) NOT NULL DEFAULT 'NORMAL',
    ADD COLUMN opening_reason TEXT,
    ADD COLUMN opened_by BIGINT REFERENCES users (id),
    ADD COLUMN opened_at TIMESTAMPTZ;

-- Project type allowed values (ORDER_DEVELOPMENT, REPEAT_PRODUCTION, MODERNIZATION, INTERNAL; REPAIR excluded from MVP)
ALTER TABLE projects
    ADD CONSTRAINT chk_projects_type CHECK (project_type IS NULL OR project_type IN ('ORDER_DEVELOPMENT', 'REPEAT_PRODUCTION', 'MODERNIZATION', 'INTERNAL'));

-- Map existing project statuses to the working SPEC set, then constrain
UPDATE projects
SET status = CASE status
    WHEN 'PLANNED' THEN 'PLANNED'
    WHEN 'IN_PROGRESS' THEN 'IN_PROGRESS'
    WHEN 'ON_HOLD' THEN 'ON_HOLD'
    WHEN 'COMPLETED' THEN 'COMPLETED'
    WHEN 'CLOSED' THEN 'CLOSED'
    WHEN 'CANCELLED' THEN 'CANCELLED'
    WHEN 'Подготовка' THEN 'DRAFT'
    WHEN 'На согласовании' THEN 'PLANNED'
    WHEN 'Запланирован' THEN 'PLANNED'
    WHEN 'В работе' THEN 'IN_PROGRESS'
    WHEN 'Приостановлен' THEN 'ON_HOLD'
    WHEN 'Требует внимания' THEN 'ON_HOLD'
    WHEN 'На проверке' THEN 'COMPLETED'
    WHEN 'Завершён' THEN 'COMPLETED'
    WHEN 'Закрыт' THEN 'CLOSED'
    WHEN 'Отменён' THEN 'CANCELLED'
    ELSE 'DRAFT'
END
WHERE status NOT IN ('DRAFT', 'PLANNED', 'IN_PROGRESS', 'ON_HOLD', 'COMPLETED', 'CLOSED', 'CANCELLED');

ALTER TABLE projects
    ADD CONSTRAINT chk_projects_status CHECK (status IN ('DRAFT', 'PLANNED', 'IN_PROGRESS', 'ON_HOLD', 'COMPLETED', 'CLOSED', 'CANCELLED'));

CREATE INDEX idx_projects_type ON projects (project_type);
CREATE INDEX idx_projects_priority ON projects (priority);
CREATE INDEX idx_projects_status ON projects (status);

-- Task work types dictionary
CREATE TABLE task_work_types
(
    code            VARCHAR(64) PRIMARY KEY,
    name            VARCHAR(255) NOT NULL,
    requires_review BOOLEAN      NOT NULL DEFAULT FALSE,
    active          BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

INSERT INTO task_work_types (code, name, requires_review, active) VALUES
('GENERAL', 'Общая', false, true),
('REVIEWED', 'С проверкой', true, true);

-- Preserve existing task type values in the dictionary before adding the foreign key
INSERT INTO task_work_types (code, name, requires_review, active)
SELECT DISTINCT t.type, t.type, false, true
FROM tasks t
WHERE t.type NOT IN (SELECT code FROM task_work_types);

-- Tasks: additional fields
ALTER TABLE tasks
    ADD COLUMN priority VARCHAR(16) NOT NULL DEFAULT 'NORMAL',
    ADD COLUMN acceptance_criteria TEXT,
    ADD COLUMN planned_start_at TIMESTAMPTZ,
    ADD COLUMN blocked_reason TEXT,
    ADD COLUMN blocked_by BIGINT REFERENCES users (id),
    ADD COLUMN blocked_at TIMESTAMPTZ,
    ADD COLUMN unblock_responsible_id BIGINT REFERENCES users (id);

CREATE INDEX idx_tasks_priority ON tasks (priority);
CREATE INDEX idx_tasks_blocked_by ON tasks (blocked_by);
CREATE INDEX idx_tasks_unblock_responsible ON tasks (unblock_responsible_id);

-- Link task type to the dictionary
ALTER TABLE tasks
    ADD CONSTRAINT fk_tasks_work_type FOREIGN KEY (type) REFERENCES task_work_types (code);

-- Map existing task statuses to the working SPEC set, then constrain
UPDATE tasks
SET status = CASE status
    WHEN 'ASSIGNED' THEN 'ASSIGNED'
    WHEN 'IN_PROGRESS' THEN 'IN_PROGRESS'
    WHEN 'BLOCKED' THEN 'BLOCKED'
    WHEN 'ON_REVIEW' THEN 'ON_REVIEW'
    WHEN 'REWORK' THEN 'REWORK'
    WHEN 'CLOSED' THEN 'CLOSED'
    WHEN 'CANCELLED' THEN 'CANCELLED'
    WHEN 'Черновик' THEN 'NEW'
    WHEN 'Новая' THEN 'NEW'
    WHEN 'Назначена' THEN 'ASSIGNED'
    WHEN 'Принята' THEN 'ASSIGNED'
    WHEN 'В работе' THEN 'IN_PROGRESS'
    WHEN 'Ожидает информации' THEN 'BLOCKED'
    WHEN 'Заблокирована' THEN 'BLOCKED'
    WHEN 'На проверке' THEN 'ON_REVIEW'
    WHEN 'Возвращена на доработку' THEN 'REWORK'
    WHEN 'Выполнена' THEN 'CLOSED'
    WHEN 'Закрыта' THEN 'CLOSED'
    WHEN 'Отменена' THEN 'CANCELLED'
    ELSE 'NEW'
END
WHERE status NOT IN ('NEW', 'ASSIGNED', 'IN_PROGRESS', 'BLOCKED', 'ON_REVIEW', 'REWORK', 'CLOSED', 'CANCELLED');

ALTER TABLE tasks
    ADD CONSTRAINT chk_tasks_status CHECK (status IN ('NEW', 'ASSIGNED', 'IN_PROGRESS', 'BLOCKED', 'ON_REVIEW', 'REWORK', 'CLOSED', 'CANCELLED'));

-- Area consistency: PROJECT requires project_id, DEPARTMENT requires department_id, PERSONAL forbids both
ALTER TABLE tasks
    ADD CONSTRAINT chk_tasks_area_links CHECK (
        (area = 'PROJECT' AND project_id IS NOT NULL AND department_id IS NULL) OR
        (area = 'DEPARTMENT' AND department_id IS NOT NULL AND project_id IS NULL) OR
        (area = 'PERSONAL' AND project_id IS NULL AND department_id IS NULL)
    );

-- Reviewer must differ from assignee when both are set
ALTER TABLE tasks
    ADD CONSTRAINT chk_tasks_reviewer_not_assignee CHECK (
        reviewer_id IS NULL OR assignee_id IS NULL OR reviewer_id <> assignee_id
    );
