-- V23: Списание со склада (раздел 8.10 ТЗ, задачи WMS-33 / WMS-34).
-- Списание — отдельный документ write_off_orders со строками write_off_lines.
-- Проведение (POST) атомарно списывает свободный остаток (physical - reserved),
-- пишет неизменяемые движения stock_movement с movement_type = 'WRITE_OFF' и
-- фиксирует, требовалось ли согласование. Списание сверх лимита
-- (wms_write_off_limits, по умолчанию wms_settings.write_off_default_limit)
-- требует либо завершённого маршрута согласования (ApprovalTargetPort WRITE_OFF),
-- либо права wms:write_off_over_limit с обязательным основанием — проверяется
-- сервером в транзакции проведения. Проведённый документ неизменяем,
-- удаление запрещено всегда (append-only).

CREATE TABLE write_off_orders
(
    id                BIGSERIAL PRIMARY KEY,
    number            VARCHAR(64)    NOT NULL UNIQUE,
    warehouse_id      BIGINT         NOT NULL REFERENCES warehouses (id),
    reason_type       VARCHAR(32)    NOT NULL CHECK (reason_type IN (
        'DAMAGE', 'DEFECT', 'EXPIRY', 'LOSS', 'NATURAL_WASTAGE', 'TECHNOLOGICAL', 'OTHER'
    )),
    reason_note       TEXT           NOT NULL,
    note              TEXT,
    status            VARCHAR(16)    NOT NULL DEFAULT 'DRAFT' CHECK (status IN (
        'DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'POSTED', 'CANCELLED'
    )),
    total_quantity    NUMERIC(19, 4) NOT NULL DEFAULT 0 CHECK (total_quantity >= 0),
    over_limit        BOOLEAN        NOT NULL DEFAULT FALSE,
    limit_quantity    NUMERIC(19, 4) CHECK (limit_quantity IS NULL OR limit_quantity >= 0),
    -- Маршрут согласования фиксируется, если он был известен на момент отправки;
    -- обязательным он не является, потому что маршрут создаётся отдельным API.
    approval_route_id BIGINT REFERENCES approval_routes (id),
    approved_at       TIMESTAMPTZ,
    approved_by       BIGINT REFERENCES users (id),
    posted_at         TIMESTAMPTZ,
    posted_by         BIGINT REFERENCES users (id),
    cancelled_at      TIMESTAMPTZ,
    cancelled_by      BIGINT REFERENCES users (id),
    cancel_reason     TEXT,
    created_at        TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    created_by        BIGINT         NOT NULL REFERENCES users (id),
    version           BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT chk_write_off_reason_note CHECK (btrim(reason_note) <> ''),
    CONSTRAINT chk_write_off_reason_other CHECK (
        reason_type <> 'OTHER' OR length(btrim(reason_note)) >= 10
    ),
    CONSTRAINT chk_write_off_status CHECK (
        (status = 'DRAFT'
            AND posted_at IS NULL AND posted_by IS NULL AND cancelled_at IS NULL
            AND approved_at IS NULL) OR
        (status = 'PENDING_APPROVAL'
            AND posted_at IS NULL AND cancelled_at IS NULL AND approved_at IS NULL
            AND over_limit = TRUE AND limit_quantity IS NOT NULL) OR
        (status = 'APPROVED'
            AND approved_at IS NOT NULL AND approved_by IS NOT NULL
            AND posted_at IS NULL AND cancelled_at IS NULL
            AND over_limit = TRUE AND limit_quantity IS NOT NULL) OR
        (status = 'POSTED'
            AND posted_at IS NOT NULL AND posted_by IS NOT NULL AND cancelled_at IS NULL) OR
        (status = 'CANCELLED'
            AND cancelled_at IS NOT NULL AND cancelled_by IS NOT NULL
            AND cancel_reason IS NOT NULL AND posted_at IS NULL)
    )
);

CREATE INDEX idx_write_off_orders_number ON write_off_orders (number);
CREATE INDEX idx_write_off_orders_warehouse ON write_off_orders (warehouse_id, status);
CREATE INDEX idx_write_off_orders_status ON write_off_orders (status);
CREATE INDEX idx_write_off_orders_route ON write_off_orders (approval_route_id)
    WHERE approval_route_id IS NOT NULL;

-- Строка списания: позиция, ячейка списания, партия/серия и количество.
-- Принадлежность ячейки складу документа проверяет сервер в транзакции.
CREATE TABLE write_off_lines
(
    id              BIGSERIAL PRIMARY KEY,
    write_off_id    BIGINT         NOT NULL REFERENCES write_off_orders (id),
    line_no         INTEGER        NOT NULL CHECK (line_no > 0),
    nomenclature_id BIGINT         NOT NULL REFERENCES nomenclature (id),
    location_id     BIGINT         NOT NULL REFERENCES storage_locations (id),
    lot_id          BIGINT REFERENCES lots (id),
    serial_id       BIGINT REFERENCES serial_items (id),
    quantity        NUMERIC(19, 4) NOT NULL CHECK (quantity > 0),
    note            TEXT,
    CONSTRAINT uq_write_off_lines_order_line UNIQUE (write_off_id, line_no)
);

CREATE INDEX idx_write_off_lines_order ON write_off_lines (write_off_id);
CREATE INDEX idx_write_off_lines_nomenclature ON write_off_lines (nomenclature_id);

-- Лимит списания без согласования задаётся на склад; при отсутствии строки
-- применяется значение wms_settings.write_off_default_limit.
CREATE TABLE wms_write_off_limits
(
    id             BIGSERIAL PRIMARY KEY,
    warehouse_id   BIGINT         NOT NULL UNIQUE REFERENCES warehouses (id),
    limit_quantity NUMERIC(19, 4) NOT NULL CHECK (limit_quantity >= 0),
    updated_at     TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_by     BIGINT REFERENCES users (id)
);

CREATE TABLE wms_settings
(
    key        VARCHAR(64) PRIMARY KEY,
    value      VARCHAR(255) NOT NULL,
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_by BIGINT REFERENCES users (id)
);

INSERT INTO wms_settings (key, value)
VALUES ('write_off_default_limit', '0')
ON CONFLICT (key) DO NOTHING;

-- Проведённый и отменённый документ списания неизменяем, удаление запрещено.
CREATE OR REPLACE FUNCTION wms_guard_write_off_mutation() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'DELETE: write-off orders are append-only';
    END IF;
    IF OLD.status IN ('POSTED', 'CANCELLED') THEN
        RAISE EXCEPTION 'UPDATE: write-off order in status % cannot be changed', OLD.status;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_write_off_orders_mutation_guard
    BEFORE UPDATE OR DELETE ON write_off_orders
    FOR EACH ROW EXECUTE FUNCTION wms_guard_write_off_mutation();

-- Строки списания меняются, только пока документ черновик.
CREATE OR REPLACE FUNCTION wms_guard_write_off_line_mutation() RETURNS trigger AS $$
DECLARE
    parent_status VARCHAR(16);
BEGIN
    SELECT status INTO parent_status
    FROM write_off_orders
    WHERE id = COALESCE(NEW.write_off_id, OLD.write_off_id);
    IF parent_status <> 'DRAFT' THEN
        RAISE EXCEPTION '%: lines of a non-draft write-off cannot be changed', TG_OP;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_write_off_lines_mutation_guard
    BEFORE INSERT OR UPDATE OR DELETE ON write_off_lines
    FOR EACH ROW EXECUTE FUNCTION wms_guard_write_off_line_mutation();

-- Идемпотентность команд списания (создание, отправка на согласование,
-- проведение, отмена): ключ уникален в рамках пользователя и операции.
CREATE TABLE wms_write_off_idempotency_keys
(
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT       NOT NULL REFERENCES users (id),
    operation       VARCHAR(64)  NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash    VARCHAR(64)  NOT NULL,
    write_off_id    BIGINT       NOT NULL REFERENCES write_off_orders (id),
    response_status INTEGER      NOT NULL,
    response_body   TEXT         NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_wms_write_off_idempotency UNIQUE (user_id, operation, idempotency_key)
);

CREATE TRIGGER trg_wms_write_off_idempotency_immutable
    BEFORE UPDATE OR DELETE ON wms_write_off_idempotency_keys
    FOR EACH ROW EXECUTE FUNCTION wms_forbid_mutation();

-- Маршрут согласования теперь поддерживает цель WRITE_OFF, поэтому
-- ограничение target_type в approval_templates и approval_routes
-- пересоздаётся (имена inline-CHECK из V18 генерировались PostgreSQL).
ALTER TABLE approval_templates DROP CONSTRAINT IF EXISTS approval_templates_target_type_check;
ALTER TABLE approval_templates
    ADD CONSTRAINT chk_approval_templates_target_type CHECK (target_type IN ('DEMAND', 'WRITE_OFF'));

ALTER TABLE approval_routes DROP CONSTRAINT IF EXISTS approval_routes_target_type_check;
ALTER TABLE approval_routes
    ADD CONSTRAINT chk_approval_routes_target_type CHECK (target_type IN ('DEMAND', 'WRITE_OFF'));
