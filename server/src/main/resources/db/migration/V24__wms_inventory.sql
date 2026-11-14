-- V24: Инвентаризация WMS (раздел 8.11 ТЗ, задачи WMS-35 / WMS-36).
-- Процесс: задание (DRAFT) -> фиксация учётного среза (BOOKED) -> подсчёт
-- комиссией (COUNTING) -> повторный пересчёт спорных позиций -> ведомость
-- расхождений (DISCREPANCY) -> согласование (APPROVED) -> корректирующие
-- движения (POSTED); отмена возможна до проведения (CANCELLED).
--
-- Область инвентаризации: склад целиком, зона, ячейка или выбранная
-- номенклатура (scope_type WAREHOUSE / ZONE / CELL / NOMENCLATURE).
-- Учётный срез фиксируется снимком inventory_snapshot (physical_qty на момент
-- старта): дальнейшие движения в области не блокируются, расхождение считается
-- как факт минус срез. Корректирующие движения (ADJUSTMENT_IN / ADJUSTMENT_OUT)
-- пишутся при проведении, проведённый документ неизменяем.

CREATE TABLE inventory_orders
(
    id               BIGSERIAL PRIMARY KEY,
    number           VARCHAR(64)   NOT NULL UNIQUE,
    warehouse_id     BIGINT        NOT NULL REFERENCES warehouses (id),
    scope_type       VARCHAR(16)   NOT NULL CHECK (scope_type IN (
        'WAREHOUSE', 'ZONE', 'CELL', 'NOMENCLATURE'
    )),
    scope_location_id BIGINT REFERENCES storage_locations (id),
    scope_nomenclature_id BIGINT REFERENCES nomenclature (id),
    status           VARCHAR(16)   NOT NULL DEFAULT 'DRAFT' CHECK (status IN (
        'DRAFT', 'BOOKED', 'COUNTING', 'DISCREPANCY', 'APPROVED', 'POSTED', 'CANCELLED'
    )),
    note             TEXT,
    booked_at        TIMESTAMPTZ,
    booked_by        BIGINT REFERENCES users (id),
    counted_at       TIMESTAMPTZ,
    counted_by       BIGINT REFERENCES users (id),
    approved_at      TIMESTAMPTZ,
    approved_by      BIGINT REFERENCES users (id),
    posted_at        TIMESTAMPTZ,
    posted_by        BIGINT REFERENCES users (id),
    cancelled_at     TIMESTAMPTZ,
    cancelled_by     BIGINT REFERENCES users (id),
    cancel_reason    TEXT,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    created_by       BIGINT        NOT NULL REFERENCES users (id),
    version          BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT chk_inventory_scope CHECK (
        (scope_type = 'WAREHOUSE' AND scope_location_id IS NULL AND scope_nomenclature_id IS NULL) OR
        (scope_type = 'ZONE' AND scope_location_id IS NOT NULL AND scope_nomenclature_id IS NULL) OR
        (scope_type = 'CELL' AND scope_location_id IS NOT NULL AND scope_nomenclature_id IS NULL) OR
        (scope_type = 'NOMENCLATURE'
            AND scope_nomenclature_id IS NOT NULL AND scope_location_id IS NULL)
    ),
    CONSTRAINT chk_inventory_status CHECK (
        (status = 'DRAFT'
            AND booked_at IS NULL AND counted_at IS NULL
            AND approved_at IS NULL AND posted_at IS NULL AND cancelled_at IS NULL) OR
        (status = 'BOOKED'
            AND booked_at IS NOT NULL AND booked_by IS NOT NULL AND counted_at IS NULL
            AND approved_at IS NULL AND posted_at IS NULL AND cancelled_at IS NULL) OR
        (status IN ('COUNTING', 'DISCREPANCY')
            AND booked_at IS NOT NULL AND counted_at IS NOT NULL
            AND approved_at IS NULL AND posted_at IS NULL AND cancelled_at IS NULL) OR
        (status = 'APPROVED'
            AND booked_at IS NOT NULL AND counted_at IS NOT NULL
            AND approved_at IS NOT NULL AND approved_by IS NOT NULL
            AND posted_at IS NULL AND cancelled_at IS NULL) OR
        (status = 'POSTED'
            AND booked_at IS NOT NULL AND counted_at IS NOT NULL
            AND posted_at IS NOT NULL AND posted_by IS NOT NULL AND cancelled_at IS NULL) OR
        (status = 'CANCELLED'
            AND cancelled_at IS NOT NULL AND cancelled_by IS NOT NULL AND posted_at IS NULL)
    )
);

CREATE INDEX idx_inventory_orders_warehouse ON inventory_orders (warehouse_id, status);
CREATE INDEX idx_inventory_orders_status ON inventory_orders (status);

-- Учётный срез: остаток на момент старта инвентаризации (physical_qty).
-- Строка снимается в момент перехода DRAFT -> BOOKED и далее неизменяема.
CREATE TABLE inventory_snapshot
(
    id              BIGSERIAL PRIMARY KEY,
    inventory_id    BIGINT         NOT NULL REFERENCES inventory_orders (id),
    location_id     BIGINT         NOT NULL REFERENCES storage_locations (id),
    nomenclature_id BIGINT         NOT NULL REFERENCES nomenclature (id),
    lot_id          BIGINT REFERENCES lots (id),
    serial_id       BIGINT REFERENCES serial_items (id),
    book_quantity   NUMERIC(19, 4) NOT NULL CHECK (book_quantity >= 0),
    CONSTRAINT uq_inventory_snapshot_key UNIQUE (
        inventory_id, location_id, nomenclature_id, lot_id, serial_id
    )
);

CREATE INDEX idx_inventory_snapshot_inventory ON inventory_snapshot (inventory_id);

-- Подсчёт комиссией: фактическое количество по позиции среза.
-- Recount — повторный пересчёт спорной позиции (флаг recount), последний
-- подсчёт выигрывает, история сохраняется.
CREATE TABLE inventory_counts
(
    id              BIGSERIAL PRIMARY KEY,
    inventory_id    BIGINT         NOT NULL REFERENCES inventory_orders (id),
    snapshot_id     BIGINT         NOT NULL REFERENCES inventory_snapshot (id),
    counted_quantity NUMERIC(19, 4) NOT NULL CHECK (counted_quantity >= 0),
    recount         BOOLEAN        NOT NULL DEFAULT FALSE,
    note            TEXT,
    counted_by      BIGINT         NOT NULL REFERENCES users (id),
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_inventory_counts_inventory ON inventory_counts (inventory_id, snapshot_id);

-- Ведомость расхождений (8.11, WMS-36): материализуется при закрытии подсчёта.
-- discrepancy = counted - book; положительное — излишек, отрицательное — недостача.
-- Таблица append-only: пересчёт спорной позиции добавляет новую строку, при
-- чтении и проведении берётся последняя строка по каждой позиции среза.
CREATE TABLE inventory_discrepancies
(
    id              BIGSERIAL PRIMARY KEY,
    inventory_id    BIGINT         NOT NULL REFERENCES inventory_orders (id),
    snapshot_id     BIGINT         NOT NULL REFERENCES inventory_snapshot (id),
    location_id     BIGINT         NOT NULL REFERENCES storage_locations (id),
    nomenclature_id BIGINT         NOT NULL REFERENCES nomenclature (id),
    lot_id          BIGINT REFERENCES lots (id),
    serial_id       BIGINT REFERENCES serial_items (id),
    book_quantity   NUMERIC(19, 4) NOT NULL,
    counted_quantity NUMERIC(19, 4) NOT NULL,
    discrepancy     NUMERIC(19, 4) NOT NULL,
    -- Направление: SURPLUS (излишек), SHORTAGE (недостача); пусто — совпадение.
    kind            VARCHAR(16) CHECK (kind IN ('SURPLUS', 'SHORTAGE')),
    note            TEXT
);

CREATE INDEX idx_inventory_discrepancies_inventory ON inventory_discrepancies (inventory_id, snapshot_id, id);

CREATE OR REPLACE FUNCTION wms_guard_inventory_mutation() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'DELETE: inventory documents are append-only';
    END IF;
    IF OLD.status IN ('POSTED', 'CANCELLED') THEN
        RAISE EXCEPTION 'UPDATE: inventory in status % cannot be changed', OLD.status;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_inventory_orders_mutation_guard
    BEFORE UPDATE OR DELETE ON inventory_orders
    FOR EACH ROW EXECUTE FUNCTION wms_guard_inventory_mutation();

CREATE OR REPLACE FUNCTION wms_guard_inventory_snapshot_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '%: inventory snapshot is immutable', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_inventory_snapshot_immutable
    BEFORE UPDATE OR DELETE ON inventory_snapshot
    FOR EACH ROW EXECUTE FUNCTION wms_guard_inventory_snapshot_mutation();

-- Подсчёт добавляется, только пока документ в BOOKED/COUNTING/DISCREPANCY.
CREATE OR REPLACE FUNCTION wms_guard_inventory_count_mutation() RETURNS trigger AS $$
DECLARE
    parent_status VARCHAR(16);
BEGIN
    SELECT status INTO parent_status
    FROM inventory_orders
    WHERE id = COALESCE(NEW.inventory_id, OLD.inventory_id);
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION '%: inventory counts are append-only', TG_OP;
    END IF;
    IF parent_status NOT IN ('BOOKED', 'COUNTING', 'DISCREPANCY') THEN
        RAISE EXCEPTION 'INSERT: counts are not allowed for inventory in status %', parent_status;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_inventory_counts_guard
    BEFORE INSERT OR UPDATE OR DELETE ON inventory_counts
    FOR EACH ROW EXECUTE FUNCTION wms_guard_inventory_count_mutation();

CREATE OR REPLACE FUNCTION wms_guard_inventory_discrepancy_mutation() RETURNS trigger AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION '%: inventory discrepancies are append-only', TG_OP;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_inventory_discrepancies_guard
    BEFORE UPDATE OR DELETE ON inventory_discrepancies
    FOR EACH ROW EXECUTE FUNCTION wms_guard_inventory_discrepancy_mutation();

CREATE TABLE wms_inventory_idempotency_keys
(
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT       NOT NULL REFERENCES users (id),
    operation       VARCHAR(64)  NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash    VARCHAR(64)  NOT NULL,
    inventory_id    BIGINT       NOT NULL REFERENCES inventory_orders (id),
    response_status INTEGER      NOT NULL,
    response_body   TEXT         NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_wms_inventory_idempotency UNIQUE (user_id, operation, idempotency_key)
);

CREATE TRIGGER trg_wms_inventory_idempotency_immutable
    BEFORE UPDATE OR DELETE ON wms_inventory_idempotency_keys
    FOR EACH ROW EXECUTE FUNCTION wms_forbid_mutation();
