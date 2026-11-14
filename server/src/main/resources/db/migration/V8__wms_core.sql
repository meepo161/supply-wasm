-- V8: Ядро WMS — склады, места хранения, номенклатура, партии/серии, остатки,
-- неизменяемый журнал движений, резервы с историей и приёмка (receipts/receipt_lines).
-- Только оперативный и управленческий учёт (D-18): без бухгалтерских проводок,
-- закупок, производства и Android. Чистая база, начальные остатки вводятся
-- документированной приёмкой INITIAL_STOCK после инвентаризации (D-25).
-- Инварианты схемы: резерв не является выдачей и не превышает доступное (D-30);
-- доступный остаток неотрицателен; карантин/брак не выдаются (моделируются
-- назначением места хранения QUARANTINE/DEFECT, исключение — на сервере);
-- проведённые операции не удаляются и не переписываются (триггеры запрета);
-- стабильный внутренний ID отделён от бизнес-номера.

-- Склады
CREATE TABLE warehouses
(
    id         BIGSERIAL PRIMARY KEY,
    code       VARCHAR(64)  NOT NULL UNIQUE,
    name       VARCHAR(255) NOT NULL,
    address    VARCHAR(500),
    active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by BIGINT       NOT NULL REFERENCES users (id),
    version    BIGINT       NOT NULL DEFAULT 0
);

CREATE INDEX idx_warehouses_code ON warehouses (code);

-- Места хранения: зоны и ячейки склада. parent_id связывает ячейку с зоной.
-- kind: ZONE — зона (помещение, приёмка, отгрузка, карантин, брак), CELL — ячейка.
-- purpose: STORAGE, RECEIVING, SHIPPING, QUARANTINE, DEFECT, TEMPORARY.
CREATE TABLE storage_locations
(
    id           BIGSERIAL PRIMARY KEY,
    warehouse_id BIGINT       NOT NULL REFERENCES warehouses (id),
    parent_id    BIGINT REFERENCES storage_locations (id),
    code         VARCHAR(64)  NOT NULL,
    name         VARCHAR(255) NOT NULL,
    kind         VARCHAR(16)  NOT NULL CHECK (kind IN ('ZONE', 'CELL')),
    purpose      VARCHAR(16)  NOT NULL DEFAULT 'STORAGE'
        CHECK (purpose IN ('STORAGE', 'RECEIVING', 'SHIPPING', 'QUARANTINE', 'DEFECT', 'TEMPORARY')),
    capacity     NUMERIC(19, 4) CHECK (capacity IS NULL OR capacity > 0),
    active       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by   BIGINT       NOT NULL REFERENCES users (id),
    version      BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_storage_locations_warehouse_code UNIQUE (warehouse_id, code),
    CONSTRAINT uq_storage_locations_id_warehouse UNIQUE (id, warehouse_id),
    CONSTRAINT chk_storage_locations_parent CHECK (parent_id IS NULL OR parent_id <> id)
);

CREATE INDEX idx_storage_locations_parent ON storage_locations (parent_id);
CREATE INDEX idx_storage_locations_warehouse ON storage_locations (warehouse_id, active);

-- Номенклатура. Типы — по разделу 10.1 ТЗ.
CREATE TABLE nomenclature
(
    id             BIGSERIAL PRIMARY KEY,
    code           VARCHAR(64)  NOT NULL UNIQUE,
    name           VARCHAR(500) NOT NULL,
    description    TEXT,
    type           VARCHAR(32)  NOT NULL CHECK (type IN (
        'RAW_MATERIAL', 'MATERIAL', 'PURCHASED_COMPONENT', 'STANDARD_PRODUCT',
        'OWN_UNIT', 'ASSEMBLY', 'FINISHED_PRODUCT', 'TOOL',
        'EQUIPMENT', 'CONSUMABLE', 'SERVICE', 'KIT'
    )),
    unit           VARCHAR(32)  NOT NULL,
    lot_tracked    BOOLEAN      NOT NULL DEFAULT FALSE,
    serial_tracked BOOLEAN      NOT NULL DEFAULT FALSE,
    archived       BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by     BIGINT       NOT NULL REFERENCES users (id),
    version        BIGINT       NOT NULL DEFAULT 0
);

CREATE INDEX idx_nomenclature_code ON nomenclature (code);
CREATE INDEX idx_nomenclature_type ON nomenclature (type);
CREATE INDEX idx_nomenclature_archived ON nomenclature (archived);

-- Штрихкоды номенклатуры (D-26): несколько однозначно разрешаемых кодов,
-- включая единицу и упаковку. Штрихкод глобально уникален.
CREATE TABLE nomenclature_barcodes
(
    id                  BIGSERIAL PRIMARY KEY,
    nomenclature_id     BIGINT        NOT NULL REFERENCES nomenclature (id),
    barcode             VARCHAR(128)  NOT NULL UNIQUE,
    package_type        VARCHAR(16)   NOT NULL DEFAULT 'UNIT' CHECK (package_type IN ('UNIT', 'PACKAGE')),
    quantity_in_package NUMERIC(19, 4) NOT NULL DEFAULT 1 CHECK (quantity_in_package > 0),
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    created_by          BIGINT        NOT NULL REFERENCES users (id)
);

CREATE INDEX idx_nomenclature_barcodes_nomenclature ON nomenclature_barcodes (nomenclature_id);

-- Партии (8.12 ТЗ): номер уникален в рамках номенклатуры.
CREATE TABLE lots
(
    id               BIGSERIAL PRIMARY KEY,
    nomenclature_id  BIGINT       NOT NULL REFERENCES nomenclature (id),
    lot_number       VARCHAR(128) NOT NULL,
    manufacture_date DATE,
    expiry_date      DATE,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by       BIGINT       NOT NULL REFERENCES users (id),
    CONSTRAINT uq_lots_nomenclature_number UNIQUE (nomenclature_id, lot_number),
    CONSTRAINT chk_lots_dates CHECK (
        manufacture_date IS NULL OR expiry_date IS NULL OR expiry_date >= manufacture_date
    )
);

CREATE INDEX idx_lots_expiry ON lots (expiry_date) WHERE expiry_date IS NOT NULL;

-- Серийные объекты: серийный номер уникален в рамках номенклатуры (инвариант 14 ТЗ).
CREATE TABLE serial_items
(
    id              BIGSERIAL PRIMARY KEY,
    nomenclature_id BIGINT       NOT NULL REFERENCES nomenclature (id),
    lot_id          BIGINT REFERENCES lots (id),
    serial_number   VARCHAR(128) NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by      BIGINT       NOT NULL REFERENCES users (id),
    CONSTRAINT uq_serial_items_nomenclature_number UNIQUE (nomenclature_id, serial_number)
);

-- Остатки: оптимизированная проекция, сверяемая с журналом stock_movement (раздел 27 ТЗ).
-- available_qty — вычисляемая колонка physical - reserved; отрицательное значение
-- запрещено ограничением. Карантин/брак исключаются из доступности на сервере
-- по назначению ячейки (purpose IN ('QUARANTINE','DEFECT')).
-- Для серийной позиции физический остаток строки ровно 1.
CREATE TABLE stock_balance
(
    id             BIGSERIAL PRIMARY KEY,
    warehouse_id   BIGINT        NOT NULL,
    location_id    BIGINT        NOT NULL,
    nomenclature_id BIGINT       NOT NULL REFERENCES nomenclature (id),
    lot_id         BIGINT REFERENCES lots (id),
    serial_id      BIGINT REFERENCES serial_items (id),
    physical_qty   NUMERIC(19, 4) NOT NULL DEFAULT 0 CHECK (physical_qty >= 0),
    reserved_qty   NUMERIC(19, 4) NOT NULL DEFAULT 0 CHECK (reserved_qty >= 0),
    available_qty  NUMERIC(19, 4) GENERATED ALWAYS AS (physical_qty - reserved_qty) STORED,
    updated_at     TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    version        BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT fk_stock_balance_location FOREIGN KEY (location_id, warehouse_id)
        REFERENCES storage_locations (id, warehouse_id),
    CONSTRAINT chk_stock_balance_not_negative CHECK (physical_qty >= reserved_qty),
    CONSTRAINT chk_stock_balance_serial_qty CHECK (
        serial_id IS NULL OR (physical_qty = 1 AND reserved_qty IN (0, 1))
    )
);

-- Ключ учётной позиции; COALESCE заменяет NULL, чтобы уникальность работала.
CREATE UNIQUE INDEX idx_stock_balance_key ON stock_balance
    (warehouse_id, location_id, nomenclature_id, COALESCE(lot_id, 0), COALESCE(serial_id, 0));
CREATE INDEX idx_stock_balance_nomenclature ON stock_balance (nomenclature_id, warehouse_id);
CREATE INDEX idx_stock_balance_lot ON stock_balance (lot_id) WHERE lot_id IS NOT NULL;
CREATE INDEX idx_stock_balance_serial ON stock_balance (serial_id) WHERE serial_id IS NOT NULL;

-- Неизменяемый журнал движений (первичные операции, раздел 27 ТЗ).
-- UPDATE и DELETE запрещены триггером trg_stock_movement_immutable.
-- Основание — полиморфная ссылка (document_type + document_id), например
-- document_type = 'RECEIPT' и document_id = receipts.id; целостность основания
-- проверяет сервер в транзакции проведения.
CREATE TABLE stock_movement
(
    id               BIGSERIAL PRIMARY KEY,
    movement_type    VARCHAR(32)   NOT NULL CHECK (movement_type IN (
        'RECEIPT', 'ISSUE', 'RETURN', 'WRITE_OFF',
        'TRANSFER', 'ADJUSTMENT_IN', 'ADJUSTMENT_OUT'
    )),
    nomenclature_id  BIGINT        NOT NULL REFERENCES nomenclature (id),
    lot_id           BIGINT REFERENCES lots (id),
    serial_id        BIGINT REFERENCES serial_items (id),
    from_location_id BIGINT REFERENCES storage_locations (id),
    to_location_id   BIGINT REFERENCES storage_locations (id),
    quantity         NUMERIC(19, 4) NOT NULL CHECK (quantity > 0),
    document_type    VARCHAR(64)   NOT NULL,
    document_id      BIGINT        NOT NULL,
    note             TEXT,
    created_by       BIGINT        NOT NULL REFERENCES users (id),
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_stock_movement_locations CHECK (
        (movement_type = 'TRANSFER'
            AND from_location_id IS NOT NULL AND to_location_id IS NOT NULL
            AND from_location_id <> to_location_id) OR
        (movement_type IN ('RECEIPT', 'RETURN', 'ADJUSTMENT_IN')
            AND to_location_id IS NOT NULL AND from_location_id IS NULL) OR
        (movement_type IN ('ISSUE', 'WRITE_OFF', 'ADJUSTMENT_OUT')
            AND from_location_id IS NOT NULL AND to_location_id IS NULL)
    )
);

CREATE INDEX idx_stock_movement_journal ON stock_movement (created_at, id);
CREATE INDEX idx_stock_movement_nomenclature ON stock_movement (nomenclature_id, created_at);
CREATE INDEX idx_stock_movement_document ON stock_movement (document_type, document_id);
CREATE INDEX idx_stock_movement_from_location ON stock_movement (from_location_id) WHERE from_location_id IS NOT NULL;
CREATE INDEX idx_stock_movement_to_location ON stock_movement (to_location_id) WHERE to_location_id IS NOT NULL;
CREATE INDEX idx_stock_movement_lot ON stock_movement (lot_id) WHERE lot_id IS NOT NULL;
CREATE INDEX idx_stock_movement_serial ON stock_movement (serial_id) WHERE serial_id IS NOT NULL;

-- Резервы (D-30): резерв не является выдачей, уменьшает доступное количество
-- через stock_balance.reserved_qty и не может превышать доступное (проверяется
-- сервером в транзакции с блокировкой строки остатка). Создание, изменение
-- и снятие фиксируются в reservation_history.
-- Основания: проект, производственный заказ, задел, ремонт, задача подразделения,
-- прочее. Ссылочные поля заполняются по типу: PROJECT -> project_id,
-- DEPARTMENT_TASK -> task_id; остальные основания пока без FK (сущности
-- производственных заказов и ремонтов в схеме отсутствуют).
CREATE TABLE reservations
(
    id              BIGSERIAL PRIMARY KEY,
    number          VARCHAR(64)   NOT NULL UNIQUE,
    reason_type     VARCHAR(32)   NOT NULL CHECK (reason_type IN (
        'PROJECT', 'PRODUCTION_ORDER', 'STOCKPILE', 'REPAIR', 'DEPARTMENT_TASK', 'OTHER'
    )),
    project_id      BIGINT REFERENCES projects (id),
    task_id         BIGINT REFERENCES tasks (id),
    reason_note     TEXT,
    nomenclature_id BIGINT        NOT NULL REFERENCES nomenclature (id),
    warehouse_id    BIGINT        NOT NULL,
    location_id     BIGINT,
    lot_id          BIGINT REFERENCES lots (id),
    serial_id       BIGINT REFERENCES serial_items (id),
    quantity        NUMERIC(19, 4) NOT NULL CHECK (quantity > 0),
    issued_quantity NUMERIC(19, 4) NOT NULL DEFAULT 0,
    status          VARCHAR(32)   NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'FULFILLED', 'RELEASED', 'CANCELLED')),
    owner_id        BIGINT        NOT NULL REFERENCES users (id),
    expires_at      TIMESTAMPTZ,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    created_by      BIGINT        NOT NULL REFERENCES users (id),
    version         BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT fk_reservations_location FOREIGN KEY (location_id, warehouse_id)
        REFERENCES storage_locations (id, warehouse_id),
    CONSTRAINT chk_reservations_issued CHECK (issued_quantity >= 0 AND issued_quantity <= quantity),
    CONSTRAINT chk_reservations_reason CHECK (
        (reason_type = 'PROJECT' AND project_id IS NOT NULL AND task_id IS NULL) OR
        (reason_type = 'DEPARTMENT_TASK' AND task_id IS NOT NULL AND project_id IS NULL) OR
        (reason_type IN ('PRODUCTION_ORDER', 'STOCKPILE', 'REPAIR', 'OTHER')
            AND project_id IS NULL AND task_id IS NULL)
    )
);

CREATE INDEX idx_reservations_number ON reservations (number);
CREATE INDEX idx_reservations_status ON reservations (status);
CREATE INDEX idx_reservations_project ON reservations (project_id) WHERE project_id IS NOT NULL;
CREATE INDEX idx_reservations_task ON reservations (task_id) WHERE task_id IS NOT NULL;
CREATE INDEX idx_reservations_nomenclature ON reservations (nomenclature_id, warehouse_id);
CREATE INDEX idx_reservations_expires ON reservations (expires_at) WHERE expires_at IS NOT NULL;

-- История резервов: неизменяемая (триггер trg_reservation_history_immutable).
CREATE TABLE reservation_history
(
    id              BIGSERIAL PRIMARY KEY,
    reservation_id  BIGINT         NOT NULL REFERENCES reservations (id),
    action          VARCHAR(32)    NOT NULL CHECK (action IN (
        'CREATED', 'CHANGED', 'ISSUED', 'FULFILLED', 'RELEASED', 'CANCELLED'
    )),
    from_status     VARCHAR(32),
    to_status       VARCHAR(32),
    quantity_before NUMERIC(19, 4),
    quantity_after  NUMERIC(19, 4),
    reason          TEXT,
    changed_by      BIGINT         NOT NULL REFERENCES users (id),
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_reservation_history_reservation ON reservation_history (reservation_id, created_at);

-- Приёмка (8.4 ТЗ, D-29): ожидаемая поставка, сверка, фактическое количество,
-- проведение кладовщиком без обязательного ОТК на первом этапе.
-- Частичная приёмка — несколько проведённых receipts на одно основание
-- (source_type + source_ref). Проведённый документ неизменяем
-- (триггер trg_receipts_posted_immutable), исправление — корректировкой.
CREATE TABLE receipts
(
    id          BIGSERIAL PRIMARY KEY,
    number      VARCHAR(64)  NOT NULL UNIQUE,
    source_type VARCHAR(32)  NOT NULL CHECK (source_type IN (
        'PURCHASE_ORDER', 'RETURN', 'TRANSFER', 'INITIAL_STOCK', 'OTHER'
    )),
    source_ref  VARCHAR(255),
    warehouse_id BIGINT      NOT NULL REFERENCES warehouses (id),
    status      VARCHAR(32)  NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT', 'IN_PROGRESS', 'POSTED', 'CANCELLED')),
    expected_at TIMESTAMPTZ,
    note        TEXT,
    posted_at   TIMESTAMPTZ,
    posted_by   BIGINT REFERENCES users (id),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by  BIGINT       NOT NULL REFERENCES users (id),
    version     BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT chk_receipts_posted CHECK (
        (status = 'POSTED' AND posted_at IS NOT NULL AND posted_by IS NOT NULL) OR
        (status <> 'POSTED' AND posted_at IS NULL AND posted_by IS NULL)
    )
);

CREATE INDEX idx_receipts_number ON receipts (number);
CREATE INDEX idx_receipts_status ON receipts (status);
CREATE INDEX idx_receipts_warehouse ON receipts (warehouse_id);
CREATE INDEX idx_receipts_source ON receipts (source_type, source_ref) WHERE source_ref IS NOT NULL;

-- Позиции приёмки: ожидаемое и фактическое количество, партия и целевая ячейка
-- при необходимости. Фактическое может отличаться от ожидаемого (расхождения
-- фиксируются процессом, излишек не попадает в свободный остаток без решения).
CREATE TABLE receipt_lines
(
    id                BIGSERIAL PRIMARY KEY,
    receipt_id        BIGINT         NOT NULL REFERENCES receipts (id),
    line_no           INTEGER        NOT NULL CHECK (line_no > 0),
    nomenclature_id   BIGINT         NOT NULL REFERENCES nomenclature (id),
    expected_quantity NUMERIC(19, 4) NOT NULL CHECK (expected_quantity >= 0),
    received_quantity NUMERIC(19, 4) NOT NULL DEFAULT 0 CHECK (received_quantity >= 0),
    lot_id            BIGINT REFERENCES lots (id),
    location_id       BIGINT REFERENCES storage_locations (id),
    note              TEXT,
    CONSTRAINT uq_receipt_lines_receipt_line UNIQUE (receipt_id, line_no)
);

CREATE INDEX idx_receipt_lines_receipt ON receipt_lines (receipt_id);
CREATE INDEX idx_receipt_lines_nomenclature ON receipt_lines (nomenclature_id);
CREATE INDEX idx_receipt_lines_lot ON receipt_lines (lot_id) WHERE lot_id IS NOT NULL;

-- Серийные номера, принятые по позиции приёмки (сценарий 40).
CREATE TABLE receipt_line_serials
(
    receipt_line_id BIGINT NOT NULL REFERENCES receipt_lines (id),
    serial_id       BIGINT NOT NULL REFERENCES serial_items (id),
    PRIMARY KEY (receipt_line_id, serial_id)
);

CREATE INDEX idx_receipt_line_serials_serial ON receipt_line_serials (serial_id);

-- Запрет UPDATE/DELETE на неизменяемых журналах.
CREATE OR REPLACE FUNCTION wms_forbid_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '%: table % is append-only', TG_OP, TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_stock_movement_immutable
    BEFORE UPDATE OR DELETE ON stock_movement
    FOR EACH ROW EXECUTE FUNCTION wms_forbid_mutation();

CREATE TRIGGER trg_reservation_history_immutable
    BEFORE UPDATE OR DELETE ON reservation_history
    FOR EACH ROW EXECUTE FUNCTION wms_forbid_mutation();

-- Запрет изменения и удаления проведённой приёмки и её строк.
CREATE OR REPLACE FUNCTION wms_forbid_posted_receipt_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '%: posted receipt cannot be changed or deleted', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_receipts_posted_immutable
    BEFORE UPDATE OR DELETE ON receipts
    FOR EACH ROW WHEN (OLD.status = 'POSTED')
    EXECUTE FUNCTION wms_forbid_posted_receipt_mutation();

CREATE OR REPLACE FUNCTION wms_guard_receipt_line_mutation() RETURNS trigger AS $$
DECLARE
    parent_status VARCHAR(32);
BEGIN
    SELECT status INTO parent_status
    FROM receipts
    WHERE id = COALESCE(NEW.receipt_id, OLD.receipt_id);
    IF parent_status = 'POSTED' THEN
        RAISE EXCEPTION '%: lines of a posted receipt cannot be changed or deleted', TG_OP;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_receipt_lines_posted_guard
    BEFORE INSERT OR UPDATE OR DELETE ON receipt_lines
    FOR EACH ROW EXECUTE FUNCTION wms_guard_receipt_line_mutation();

CREATE OR REPLACE FUNCTION wms_guard_receipt_line_serial_mutation() RETURNS trigger AS $$
DECLARE
    parent_status VARCHAR(32);
BEGIN
    SELECT r.status INTO parent_status
    FROM receipts r
    JOIN receipt_lines rl ON rl.receipt_id = r.id
    WHERE rl.id = COALESCE(NEW.receipt_line_id, OLD.receipt_line_id);
    IF parent_status = 'POSTED' THEN
        RAISE EXCEPTION '%: serials of a posted receipt cannot be changed or deleted', TG_OP;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_receipt_line_serials_posted_guard
    BEFORE INSERT OR UPDATE OR DELETE ON receipt_line_serials
    FOR EACH ROW EXECUTE FUNCTION wms_guard_receipt_line_serial_mutation();
