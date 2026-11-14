-- V18: Ядро универсальных согласований (APPR-001).
-- Основания: раздел 13 ТЗ, D-41, L.6, M.6; рабочая база по D-44.
-- Объём первой поставки ядра:
--  * типовые настраиваемые маршруты (approval_templates + этапы) без правки кода;
--  * последовательные этапы; внутри этапа согласующие параллельны, политика
--    ALL/ANY обязана быть задана явно (decision_policy NOT NULL, без default);
--  * назначение согласующих USER/ROLE; ручное изменение состава на маршруте
--    по праву и с аудитом (аудит — серверный, таблица маршрутов хранит снимок);
--  * решения APPROVE/REJECT/RETURN с автором и временем, append-only
--    (триггер wms_forbid_mutation() из V8); комментарий обязателен для
--    REJECT/RETURN на уровне CHECK;
--  * маршрут фиксирует target_version; решение по устаревшей версии
--    отклоняется сервером (M.6);
--  * не более одного ACTIVE маршрута на объект — частичный уникальный
--    индекс отдельным CREATE UNIQUE INDEX ... WHERE (PostgreSQL не
--    поддерживает UNIQUE(...) WHERE в табличном ограничении);
--  * идемпотентный старт по (started_by, idempotency_key) с request hash
--    (конвенция V17).
-- Реальный адаптер цели первой поставки — только DEMAND; остальные типы
-- (BOM, DOCUMENT_VERSION и пр.) отклоняются сервером до появления адаптера,
-- заглушки не создаются. Условия, пороги, замещения, сроки реакции и
-- эскалации из раздела 13 ТЗ не реализованы (не утверждены для этой поставки).

CREATE TABLE approval_templates
(
    id          BIGSERIAL PRIMARY KEY,
    code        VARCHAR(64)  NOT NULL UNIQUE,
    name        VARCHAR(500) NOT NULL,
    target_type VARCHAR(32)  NOT NULL CHECK (target_type IN ('DEMAND')),
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by  BIGINT       NOT NULL REFERENCES users (id),
    version     BIGINT       NOT NULL DEFAULT 0
);

CREATE INDEX idx_approval_templates_target ON approval_templates (target_type) WHERE active;

-- Этапы шаблона: последовательность задаётся stage_no; согласующие этапа
-- работают параллельно по явной политике decision_policy.
CREATE TABLE approval_template_stages
(
    id              BIGSERIAL PRIMARY KEY,
    template_id     BIGINT       NOT NULL REFERENCES approval_templates (id) ON DELETE CASCADE,
    stage_no        INTEGER      NOT NULL CHECK (stage_no > 0),
    name            VARCHAR(255) NOT NULL,
    decision_policy VARCHAR(8)   NOT NULL CHECK (decision_policy IN ('ALL', 'ANY')),
    CONSTRAINT uq_approval_template_stages UNIQUE (template_id, stage_no)
);

CREATE TABLE approval_template_assignees
(
    id            BIGSERIAL PRIMARY KEY,
    stage_id      BIGINT     NOT NULL REFERENCES approval_template_stages (id) ON DELETE CASCADE,
    assignee_type VARCHAR(8) NOT NULL CHECK (assignee_type IN ('USER', 'ROLE')),
    user_id       BIGINT REFERENCES users (id),
    role_id       BIGINT REFERENCES roles (id),
    CONSTRAINT chk_approval_template_assignees_kind CHECK (
        (assignee_type = 'USER' AND user_id IS NOT NULL AND role_id IS NULL) OR
        (assignee_type = 'ROLE' AND role_id IS NOT NULL AND user_id IS NULL)
    )
);

CREATE INDEX idx_approval_template_assignees_stage ON approval_template_assignees (stage_id);

-- Маршрут согласования конкретного объекта. target_id полиморфен (target_type),
-- поэтому FK на целевую таблицу нет; целостность и права проверяет серверный
-- адаптер цели. target_version фиксируется при старте и не переписывается.
CREATE TABLE approval_routes
(
    id                       BIGSERIAL PRIMARY KEY,
    template_id              BIGINT      NOT NULL REFERENCES approval_templates (id),
    target_type              VARCHAR(32) NOT NULL CHECK (target_type IN ('DEMAND')),
    target_id                BIGINT      NOT NULL,
    target_version           BIGINT      NOT NULL,
    status                   VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'COMPLETED', 'REJECTED', 'RETURNED', 'CANCELLED')),
    current_stage_no         INTEGER     NOT NULL CHECK (current_stage_no > 0),
    idempotency_key          VARCHAR(64),
    idempotency_request_hash VARCHAR(64),
    started_by               BIGINT      NOT NULL REFERENCES users (id),
    started_at               TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at             TIMESTAMPTZ,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by               BIGINT      NOT NULL REFERENCES users (id),
    version                  BIGINT      NOT NULL DEFAULT 0
);

-- Не более одного активного маршрута на объект (частичный индекс отдельной командой).
CREATE UNIQUE INDEX idx_approval_routes_active_target
    ON approval_routes (target_type, target_id) WHERE status = 'ACTIVE';

CREATE UNIQUE INDEX idx_approval_routes_idempotency
    ON approval_routes (started_by, idempotency_key) WHERE idempotency_key IS NOT NULL;

CREATE INDEX idx_approval_routes_target ON approval_routes (target_type, target_id, id);
CREATE INDEX idx_approval_routes_template ON approval_routes (template_id);

-- Снимок этапов шаблона на момент старта: последующее редактирование шаблона
-- не меняет уже идущий маршрут.
CREATE TABLE approval_route_stages
(
    id              BIGSERIAL PRIMARY KEY,
    route_id        BIGINT       NOT NULL REFERENCES approval_routes (id) ON DELETE CASCADE,
    stage_no        INTEGER      NOT NULL CHECK (stage_no > 0),
    name            VARCHAR(255) NOT NULL,
    decision_policy VARCHAR(8)   NOT NULL CHECK (decision_policy IN ('ALL', 'ANY')),
    status          VARCHAR(16)  NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'ACTIVE', 'APPROVED', 'REJECTED', 'RETURNED', 'CANCELLED')),
    started_at      TIMESTAMPTZ,
    completed_at    TIMESTAMPTZ,
    CONSTRAINT uq_approval_route_stages UNIQUE (route_id, stage_no)
);

CREATE INDEX idx_approval_route_stages_route ON approval_route_stages (route_id);

-- Снимок согласующих маршрута; ручное назначение/снятие по праву меняет этот
-- снимок и аудируется на сервере.
CREATE TABLE approval_route_assignees
(
    id            BIGSERIAL PRIMARY KEY,
    route_stage_id BIGINT      NOT NULL REFERENCES approval_route_stages (id) ON DELETE CASCADE,
    assignee_type VARCHAR(8)   NOT NULL CHECK (assignee_type IN ('USER', 'ROLE')),
    user_id       BIGINT REFERENCES users (id),
    role_id       BIGINT REFERENCES roles (id),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by    BIGINT       NOT NULL REFERENCES users (id),
    CONSTRAINT chk_approval_route_assignees_kind CHECK (
        (assignee_type = 'USER' AND user_id IS NOT NULL AND role_id IS NULL) OR
        (assignee_type = 'ROLE' AND role_id IS NOT NULL AND user_id IS NULL)
    )
);

CREATE INDEX idx_approval_route_assignees_stage ON approval_route_assignees (route_stage_id);

-- Решения: append-only, автор и время обязательны; выполненное решение не
-- редактируется (L.6, M.6). Один пользователь — одно решение на этапе.
CREATE TABLE approval_decisions
(
    id             BIGSERIAL PRIMARY KEY,
    route_id       BIGINT     NOT NULL REFERENCES approval_routes (id),
    route_stage_id BIGINT     NOT NULL REFERENCES approval_route_stages (id),
    assignee_id    BIGINT REFERENCES approval_route_assignees (id),
    decided_by     BIGINT     NOT NULL REFERENCES users (id),
    decision       VARCHAR(8) NOT NULL CHECK (decision IN ('APPROVE', 'REJECT', 'RETURN')),
    comment        TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_approval_decisions_comment CHECK (
        decision = 'APPROVE' OR (comment IS NOT NULL AND length(btrim(comment)) > 0)
    )
);

CREATE UNIQUE INDEX idx_approval_decisions_user_stage
    ON approval_decisions (route_stage_id, decided_by);
CREATE INDEX idx_approval_decisions_route ON approval_decisions (route_id);
CREATE INDEX idx_approval_decisions_stage ON approval_decisions (route_stage_id);

CREATE TRIGGER trg_approval_decisions_immutable
    BEFORE UPDATE OR DELETE ON approval_decisions
    FOR EACH ROW EXECUTE FUNCTION wms_forbid_mutation();
