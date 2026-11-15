-- V29: Планирование проекта — этапы, контрольные точки и риски
-- (раздел 5.3 и 5.5 ТЗ; задачи PRJ-08, PRJ-09, PRJ-10).
--
-- Структура ТЗ: проект -> этап -> контрольная точка -> задача. Здесь заводятся
-- первые два уровня и риски проекта. Связь «этап -> задача» не вводится: у
-- задачи проекта уже есть project_id, а привязка к этапу появится вместе с
-- зависимостями задач (PRJ-12), чтобы не заводить поле, которым ничего не
-- управляет.
--
-- Проценты выполнения НЕ хранятся: методика расчёта не утверждена (M.4,
-- PRJ-18), поэтому показывается состояние этапа, а не выдуманный процент.
-- Критичность риска тоже не вычисляется из вероятности и влияния: все три
-- значения задаёт ответственный, иначе пришлось бы изобретать матрицу.

-- ---------------------------------------------------------------------------
-- Этапы проекта.
-- ---------------------------------------------------------------------------
CREATE TABLE project_stages
(
    id                  BIGSERIAL PRIMARY KEY,
    project_id          BIGINT       NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    name                VARCHAR(255) NOT NULL,
    description         TEXT,
    sequence            INTEGER      NOT NULL CHECK (sequence > 0),
    status              VARCHAR(16)  NOT NULL DEFAULT 'PLANNED' CHECK (status IN (
        'PLANNED', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED'
    )),
    planned_start_at    TIMESTAMPTZ,
    planned_end_at      TIMESTAMPTZ,
    actual_start_at     TIMESTAMPTZ,
    actual_end_at       TIMESTAMPTZ,
    responsible_id      BIGINT REFERENCES users (id),
    completion_criteria TEXT,
    cancel_reason       TEXT,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by          BIGINT       NOT NULL REFERENCES users (id),
    version             BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_project_stages_project_sequence UNIQUE (project_id, sequence),
    CONSTRAINT chk_project_stages_dates CHECK (
        planned_start_at IS NULL OR planned_end_at IS NULL
            OR planned_start_at <= planned_end_at
    ),
    CONSTRAINT chk_project_stages_status_timestamps CHECK (
        (status = 'PLANNED'
            AND actual_start_at IS NULL AND actual_end_at IS NULL AND cancel_reason IS NULL) OR
        (status = 'IN_PROGRESS'
            AND actual_start_at IS NOT NULL AND actual_end_at IS NULL AND cancel_reason IS NULL) OR
        (status = 'COMPLETED'
            AND actual_start_at IS NOT NULL AND actual_end_at IS NOT NULL AND cancel_reason IS NULL) OR
        (status = 'CANCELLED' AND cancel_reason IS NOT NULL)
    )
);

CREATE INDEX idx_project_stages_project ON project_stages (project_id, sequence);
CREATE INDEX idx_project_stages_status ON project_stages (project_id, status);

-- ---------------------------------------------------------------------------
-- Контрольные точки проекта. Этап необязателен: контрольная точка может быть
-- точкой проекта целиком.
-- ---------------------------------------------------------------------------
CREATE TABLE project_milestones
(
    id              BIGSERIAL PRIMARY KEY,
    project_id      BIGINT       NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    stage_id        BIGINT REFERENCES project_stages (id),
    name            VARCHAR(255) NOT NULL,
    description     TEXT,
    planned_at      TIMESTAMPTZ,
    actual_at       TIMESTAMPTZ,
    status          VARCHAR(16)  NOT NULL DEFAULT 'PLANNED' CHECK (status IN (
        'PLANNED', 'REACHED', 'MISSED', 'CANCELLED'
    )),
    responsible_id  BIGINT REFERENCES users (id),
    note            TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by      BIGINT       NOT NULL REFERENCES users (id),
    version         BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT chk_project_milestones_status_timestamps CHECK (
        (status = 'PLANNED' AND actual_at IS NULL) OR
        (status IN ('REACHED', 'MISSED') AND actual_at IS NOT NULL) OR
        (status = 'CANCELLED')
    )
);

CREATE INDEX idx_project_milestones_project ON project_milestones (project_id, planned_at);
CREATE INDEX idx_project_milestones_stage ON project_milestones (stage_id);

-- ---------------------------------------------------------------------------
-- Риски проекта (5.5 ТЗ). Связанные объекты: этап и задача проекта.
-- ---------------------------------------------------------------------------
CREATE TABLE project_risks
(
    id              BIGSERIAL PRIMARY KEY,
    project_id      BIGINT       NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    title           VARCHAR(255) NOT NULL,
    description     TEXT,
    category        VARCHAR(16)  NOT NULL DEFAULT 'OTHER' CHECK (category IN (
        'TECHNICAL', 'SUPPLY', 'SCHEDULE', 'FINANCIAL', 'ORGANIZATIONAL', 'OTHER'
    )),
    probability     VARCHAR(16)  NOT NULL CHECK (probability IN ('LOW', 'MEDIUM', 'HIGH')),
    impact          VARCHAR(16)  NOT NULL CHECK (impact IN ('LOW', 'MEDIUM', 'HIGH')),
    criticality     VARCHAR(16)  NOT NULL CHECK (criticality IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    status          VARCHAR(16)  NOT NULL DEFAULT 'OPEN' CHECK (status IN (
        'OPEN', 'MITIGATING', 'CLOSED', 'ACCEPTED'
    )),
    responsible_id  BIGINT REFERENCES users (id),
    response_plan   TEXT,
    due_date        DATE,
    stage_id        BIGINT REFERENCES project_stages (id),
    task_id         BIGINT REFERENCES tasks (id),
    outcome         TEXT,
    closed_at       TIMESTAMPTZ,
    closed_by       BIGINT REFERENCES users (id),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by      BIGINT       NOT NULL REFERENCES users (id),
    version         BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT chk_project_risks_closed CHECK (
        (status IN ('OPEN', 'MITIGATING') AND closed_at IS NULL AND closed_by IS NULL) OR
        (status IN ('CLOSED', 'ACCEPTED')
            AND closed_at IS NOT NULL AND closed_by IS NOT NULL AND outcome IS NOT NULL)
    )
);

CREATE INDEX idx_project_risks_project ON project_risks (project_id, status);
CREATE INDEX idx_project_risks_criticality ON project_risks (project_id, criticality);
CREATE INDEX idx_project_risks_stage ON project_risks (stage_id);
CREATE INDEX idx_project_risks_task ON project_risks (task_id);
