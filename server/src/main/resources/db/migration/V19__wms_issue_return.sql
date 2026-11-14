-- V19: Выдача и возврат WMS (разделы 8.8–8.9 ТЗ, задача WMS-ISSUE-001).
-- Списание (8.10) и инвентаризация (8.11) вне объёма — отдельные операции.
-- Выдача: DRAFT -> ISSUED (передача получателю) -> RECEIVED | SHORTAGE
-- (подтверждение получения проводит расход атомарно через stock_movement ISSUE),
-- REFUSED (отказ получателя с причиной) и CANCELLED (только из DRAFT).
-- Выдача из резерва уменьшает physical и reserved согласованно и обновляет
-- reservations.issued_quantity (CHECK не даёт превысить quantity); строки без
-- резерва допускаются только при праве wms:issue_without_reservation.
-- Возврат связан со строкой исходной выдачи, принимается в карантинную ячейку
-- (purpose = 'QUARANTINE') и не увеличивает доступный остаток до явного
-- решения GOOD/DEFECT с правом wms:inspect_returns.

-- Документ выдачи
CREATE TABLE issue_orders
(
    id             BIGSERIAL PRIMARY KEY,
    number         VARCHAR(64)   NOT NULL UNIQUE,
    warehouse_id   BIGINT        NOT NULL REFERENCES warehouses (id),
    reason_type    VARCHAR(32)   NOT NULL CHECK (reason_type IN (
        'PROJECT', 'PRODUCTION_ORDER', 'STOCKPILE', 'REPAIR', 'DEPARTMENT_TASK', 'OTHER'
    )),
    project_id     BIGINT REFERENCES projects (id),
    task_id        BIGINT REFERENCES tasks (id),
    reason_note    TEXT,
    recipient_id   BIGINT        NOT NULL REFERENCES users (id),
    status         VARCHAR(16)   NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT', 'ISSUED', 'RECEIVED', 'SHORTAGE', 'REFUSED', 'CANCELLED')),
    note           TEXT,
    issued_at      TIMESTAMPTZ,
    issued_by      BIGINT REFERENCES users (id),
    confirmed_at   TIMESTAMPTZ,
    confirmed_by   BIGINT REFERENCES users (id),
    refusal_reason TEXT,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    created_by     BIGINT        NOT NULL REFERENCES users (id),
    version        BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT chk_issue_orders_reason CHECK (
        (reason_type = 'PROJECT' AND project_id IS NOT NULL AND task_id IS NULL) OR
        (reason_type = 'DEPARTMENT_TASK' AND task_id IS NOT NULL AND project_id IS NULL) OR
        (reason_type IN ('PRODUCTION_ORDER', 'STOCKPILE', 'REPAIR', 'OTHER')
            AND project_id IS NULL AND task_id IS NULL)
    ),
    CONSTRAINT chk_issue_orders_status CHECK (
        (status = 'DRAFT'
            AND issued_at IS NULL AND confirmed_at IS NULL AND refusal_reason IS NULL) OR
        (status = 'ISSUED'
            AND issued_at IS NOT NULL AND issued_by IS NOT NULL
            AND confirmed_at IS NULL AND refusal_reason IS NULL) OR
        (status IN ('RECEIVED', 'SHORTAGE')
            AND issued_at IS NOT NULL AND issued_by IS NOT NULL
            AND confirmed_at IS NOT NULL AND confirmed_by IS NOT NULL
            AND refusal_reason IS NULL) OR
        (status = 'REFUSED'
            AND refusal_reason IS NOT NULL AND confirmed_at IS NULL) OR
        (status = 'CANCELLED'
            AND issued_at IS NULL AND confirmed_at IS NULL AND refusal_reason IS NULL)
    )
);

CREATE INDEX idx_issue_orders_number ON issue_orders (number);
CREATE INDEX idx_issue_orders_status ON issue_orders (status);
CREATE INDEX idx_issue_orders_warehouse ON issue_orders (warehouse_id, status);
CREATE INDEX idx_issue_orders_project ON issue_orders (project_id) WHERE project_id IS NOT NULL;

-- Позиции выдачи: запрошенное и фактически проведённое количество,
-- необязательная ссылка на резерв (issued_quantity резерва растёт при
-- проведении и не превышает quantity — CHECK на reservations).
CREATE TABLE issue_lines
(
    id                 BIGSERIAL PRIMARY KEY,
    issue_order_id     BIGINT         NOT NULL REFERENCES issue_orders (id),
    line_no            INTEGER        NOT NULL CHECK (line_no > 0),
    nomenclature_id    BIGINT         NOT NULL REFERENCES nomenclature (id),
    requested_quantity NUMERIC(19, 4) NOT NULL CHECK (requested_quantity > 0),
    reservation_id     BIGINT REFERENCES reservations (id),
    issued_quantity    NUMERIC(19, 4) NOT NULL DEFAULT 0 CHECK (issued_quantity >= 0),
    note               TEXT,
    CONSTRAINT uq_issue_lines_order_line UNIQUE (issue_order_id, line_no),
    CONSTRAINT chk_issue_lines_issued CHECK (issued_quantity <= requested_quantity)
);

CREATE INDEX idx_issue_lines_order ON issue_lines (issue_order_id);
CREATE INDEX idx_issue_lines_reservation ON issue_lines (reservation_id) WHERE reservation_id IS NOT NULL;

-- Отбор по ячейкам (частичный, фактическое количество) до проведения.
CREATE TABLE issue_picks
(
    id            BIGSERIAL PRIMARY KEY,
    issue_line_id BIGINT         NOT NULL REFERENCES issue_lines (id),
    location_id   BIGINT         NOT NULL REFERENCES storage_locations (id),
    lot_id        BIGINT REFERENCES lots (id),
    serial_id     BIGINT REFERENCES serial_items (id),
    quantity      NUMERIC(19, 4) NOT NULL CHECK (quantity > 0),
    picked_by     BIGINT         NOT NULL REFERENCES users (id),
    created_at    TIMESTAMPTZ    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_issue_picks_line ON issue_picks (issue_line_id);

-- Возврат: связан с исходным документом выдачи, принимается в карантин.
CREATE TABLE return_orders
(
    id                     BIGSERIAL PRIMARY KEY,
    number                 VARCHAR(64)  NOT NULL UNIQUE,
    issue_order_id         BIGINT       NOT NULL REFERENCES issue_orders (id),
    warehouse_id           BIGINT       NOT NULL REFERENCES warehouses (id),
    quarantine_location_id BIGINT       NOT NULL REFERENCES storage_locations (id),
    status                 VARCHAR(16)  NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT', 'RECEIVED', 'INSPECTED')),
    returned_by            BIGINT       NOT NULL REFERENCES users (id),
    reason                 TEXT         NOT NULL,
    received_at            TIMESTAMPTZ,
    received_by            BIGINT REFERENCES users (id),
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by             BIGINT       NOT NULL REFERENCES users (id),
    version                BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT chk_return_orders_status CHECK (
        (status = 'DRAFT' AND received_at IS NULL AND received_by IS NULL) OR
        (status IN ('RECEIVED', 'INSPECTED')
            AND received_at IS NOT NULL AND received_by IS NOT NULL)
    )
);

CREATE INDEX idx_return_orders_number ON return_orders (number);
CREATE INDEX idx_return_orders_issue ON return_orders (issue_order_id);
CREATE INDEX idx_return_orders_status ON return_orders (status);

-- Строки возврата: частичный возврат без превышения выданного проверяется
-- сервером с блокировкой строки выдачи. Решение о пригодности фиксируется
-- на строке с правом wms:inspect_returns.
CREATE TABLE return_lines
(
    id                 BIGSERIAL PRIMARY KEY,
    return_order_id    BIGINT         NOT NULL REFERENCES return_orders (id),
    issue_line_id      BIGINT         NOT NULL REFERENCES issue_lines (id),
    quantity           NUMERIC(19, 4) NOT NULL CHECK (quantity > 0),
    lot_id             BIGINT REFERENCES lots (id),
    serial_id          BIGINT REFERENCES serial_items (id),
    inspection         VARCHAR(8)     NOT NULL DEFAULT 'PENDING'
        CHECK (inspection IN ('PENDING', 'GOOD', 'DEFECT')),
    inspected_at       TIMESTAMPTZ,
    inspected_by       BIGINT REFERENCES users (id),
    target_location_id BIGINT REFERENCES storage_locations (id),
    inspection_note    TEXT,
    CONSTRAINT chk_return_lines_inspection CHECK (
        (inspection = 'PENDING' AND inspected_at IS NULL AND inspected_by IS NULL) OR
        (inspection IN ('GOOD', 'DEFECT')
            AND inspected_at IS NOT NULL AND inspected_by IS NOT NULL)
    )
);

CREATE INDEX idx_return_lines_order ON return_lines (return_order_id);
CREATE INDEX idx_return_lines_issue_line ON return_lines (issue_line_id);

-- Идемпотентность команд выдачи/возврата: отдельная таблица, потому что
-- wms_idempotency_keys.transfer_id специфичен для transfers. Ключ уникален
-- в рамках пользователя и операции и привязан к хешу тела запроса.
CREATE TABLE wms_issue_idempotency_keys
(
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT       NOT NULL REFERENCES users (id),
    operation       VARCHAR(64)  NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash    VARCHAR(64)  NOT NULL,
    entity_type     VARCHAR(16)  NOT NULL CHECK (entity_type IN ('ISSUE', 'RETURN')),
    entity_id       BIGINT       NOT NULL,
    response_status INTEGER      NOT NULL,
    response_body   TEXT         NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_wms_issue_idempotency UNIQUE (user_id, operation, idempotency_key)
);

CREATE TRIGGER trg_wms_issue_idempotency_immutable
    BEFORE UPDATE OR DELETE ON wms_issue_idempotency_keys
    FOR EACH ROW EXECUTE FUNCTION wms_forbid_mutation();

-- Терминальные документы выдачи неизменяемы; удаление запрещено всегда.
CREATE OR REPLACE FUNCTION wms_guard_issue_order_mutation() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'DELETE: issue orders are append-only';
    END IF;
    IF OLD.status IN ('RECEIVED', 'SHORTAGE', 'REFUSED', 'CANCELLED') THEN
        RAISE EXCEPTION 'UPDATE: issue order in terminal status % cannot be changed', OLD.status;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_issue_orders_mutation_guard
    BEFORE UPDATE OR DELETE ON issue_orders
    FOR EACH ROW EXECUTE FUNCTION wms_guard_issue_order_mutation();

-- Строки и отборы выдачи меняются, только пока документ не проведён.
CREATE OR REPLACE FUNCTION wms_guard_issue_line_mutation() RETURNS trigger AS $$
DECLARE
    parent_status VARCHAR(16);
BEGIN
    SELECT status INTO parent_status
    FROM issue_orders
    WHERE id = COALESCE(NEW.issue_order_id, OLD.issue_order_id);
    IF TG_OP = 'INSERT' AND parent_status <> 'DRAFT' THEN
        RAISE EXCEPTION 'INSERT: lines of a non-draft issue order cannot be added';
    END IF;
    IF TG_OP = 'DELETE' THEN
        IF parent_status <> 'DRAFT' THEN
            RAISE EXCEPTION 'DELETE: lines of a non-draft issue order cannot be removed';
        END IF;
        RETURN OLD;
    END IF;
    -- UPDATE: фактическое количество проставляется при подтверждении (ISSUED).
    IF parent_status NOT IN ('DRAFT', 'ISSUED') THEN
        RAISE EXCEPTION 'UPDATE: lines of issue order in status % cannot be changed', parent_status;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_issue_lines_mutation_guard
    BEFORE INSERT OR UPDATE OR DELETE ON issue_lines
    FOR EACH ROW EXECUTE FUNCTION wms_guard_issue_line_mutation();

CREATE OR REPLACE FUNCTION wms_guard_issue_pick_mutation() RETURNS trigger AS $$
DECLARE
    parent_status VARCHAR(16);
BEGIN
    SELECT io.status INTO parent_status
    FROM issue_orders io
    JOIN issue_lines il ON il.issue_order_id = io.id
    WHERE il.id = COALESCE(NEW.issue_line_id, OLD.issue_line_id);
    IF TG_OP = 'UPDATE' THEN
        RAISE EXCEPTION 'UPDATE: issue picks are append-only while draft, delete instead';
    END IF;
    IF parent_status <> 'DRAFT' THEN
        RAISE EXCEPTION '%: picks of a non-draft issue order cannot be changed', TG_OP;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_issue_picks_mutation_guard
    BEFORE INSERT OR UPDATE OR DELETE ON issue_picks
    FOR EACH ROW EXECUTE FUNCTION wms_guard_issue_pick_mutation();

-- Возвраты: закрытый (INSPECTED) документ неизменяем, удаление запрещено.
CREATE OR REPLACE FUNCTION wms_guard_return_order_mutation() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'DELETE: return orders are append-only';
    END IF;
    IF OLD.status = 'INSPECTED' THEN
        RAISE EXCEPTION 'UPDATE: inspected return order cannot be changed';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_return_orders_mutation_guard
    BEFORE UPDATE OR DELETE ON return_orders
    FOR EACH ROW EXECUTE FUNCTION wms_guard_return_order_mutation();

-- Строки возврата: добавление только в DRAFT, удаление запрещено,
-- UPDATE — только решение о пригодности (inspection-поля) при RECEIVED.
CREATE OR REPLACE FUNCTION wms_guard_return_line_mutation() RETURNS trigger AS $$
DECLARE
    parent_status VARCHAR(16);
BEGIN
    SELECT status INTO parent_status
    FROM return_orders
    WHERE id = COALESCE(NEW.return_order_id, OLD.return_order_id);
    IF TG_OP = 'INSERT' THEN
        IF parent_status <> 'DRAFT' THEN
            RAISE EXCEPTION 'INSERT: lines of a non-draft return order cannot be added';
        END IF;
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'DELETE: return lines are append-only';
    END IF;
    IF parent_status <> 'RECEIVED' OR OLD.inspection <> 'PENDING' THEN
        RAISE EXCEPTION 'UPDATE: return line inspection already decided or return not received';
    END IF;
    IF NEW.return_order_id <> OLD.return_order_id
        OR NEW.issue_line_id <> OLD.issue_line_id
        OR NEW.quantity <> OLD.quantity
        OR COALESCE(NEW.lot_id, 0) <> COALESCE(OLD.lot_id, 0)
        OR COALESCE(NEW.serial_id, 0) <> COALESCE(OLD.serial_id, 0) THEN
        RAISE EXCEPTION 'UPDATE: only inspection fields of a return line can be changed';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_return_lines_mutation_guard
    BEFORE INSERT OR UPDATE OR DELETE ON return_lines
    FOR EACH ROW EXECUTE FUNCTION wms_guard_return_line_mutation();
