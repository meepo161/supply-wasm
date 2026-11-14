-- V15: Перемещения WMS (раздел 8.6 ТЗ, задача WMS-OPS-001).
-- Внутрискладское перемещение атомарно (одна запись stock_movement TRANSFER),
-- межскладское — документ со статусами CREATED -> SHIPPED -> IN_TRANSIT -> RECEIVED,
-- DISCREPANCY при расхождении и CANCELLED только до отправки.
-- Инварианты схемы: материал не числится доступным на двух складах одновременно
-- (на время перевозки остаток уходит в место хранения purpose = 'TEMPORARY'
-- исходного склада, недоступное для обычных операций); только свободный остаток
-- (physical - reserved) может быть отгружен — проверяется сервером с блокировкой
-- строки stock_balance; журнал transfer_events неизменяем; терминальные документы
-- (RECEIVED, CANCELLED) неизменяемы, DISCREPANCY остаётся изменяемым для будущего
-- разрешения корректировкой (вне объёма этой задачи). CHECK stock_movement не
-- ослабляется: все перемещения проходят как TRANSFER from_location -> to_location.

-- Документ перемещения. Для INTERNAL целевой склад равен исходному, документ
-- проводится атомарно при создании и сразу получает статус RECEIVED.
CREATE TABLE transfers
(
    id                   BIGSERIAL PRIMARY KEY,
    number               VARCHAR(64)    NOT NULL UNIQUE,
    transfer_kind        VARCHAR(16)    NOT NULL CHECK (transfer_kind IN ('INTERNAL', 'INTER_WAREHOUSE')),
    from_warehouse_id    BIGINT         NOT NULL REFERENCES warehouses (id),
    from_location_id     BIGINT         NOT NULL,
    to_warehouse_id      BIGINT         NOT NULL REFERENCES warehouses (id),
    to_location_id       BIGINT         NOT NULL,
    transit_location_id  BIGINT REFERENCES storage_locations (id),
    nomenclature_id      BIGINT         NOT NULL REFERENCES nomenclature (id),
    lot_id               BIGINT REFERENCES lots (id),
    serial_id            BIGINT REFERENCES serial_items (id),
    quantity             NUMERIC(19, 4) NOT NULL CHECK (quantity > 0),
    received_quantity    NUMERIC(19, 4) CHECK (received_quantity IS NULL OR received_quantity >= 0),
    discrepancy_quantity NUMERIC(19, 4) CHECK (discrepancy_quantity IS NULL OR discrepancy_quantity > 0),
    status               VARCHAR(16)    NOT NULL DEFAULT 'CREATED'
        CHECK (status IN ('CREATED', 'SHIPPED', 'IN_TRANSIT', 'RECEIVED', 'DISCREPANCY', 'CANCELLED')),
    note                 TEXT,
    shipped_at           TIMESTAMPTZ,
    shipped_by           BIGINT REFERENCES users (id),
    received_at          TIMESTAMPTZ,
    received_by          BIGINT REFERENCES users (id),
    cancelled_at         TIMESTAMPTZ,
    cancelled_by         BIGINT REFERENCES users (id),
    cancel_reason        TEXT,
    created_at           TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    created_by           BIGINT         NOT NULL REFERENCES users (id),
    version              BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT fk_transfers_from_location FOREIGN KEY (from_location_id, from_warehouse_id)
        REFERENCES storage_locations (id, warehouse_id),
    CONSTRAINT fk_transfers_to_location FOREIGN KEY (to_location_id, to_warehouse_id)
        REFERENCES storage_locations (id, warehouse_id),
    CONSTRAINT chk_transfers_kind CHECK (
        (transfer_kind = 'INTERNAL'
            AND from_warehouse_id = to_warehouse_id AND from_location_id <> to_location_id) OR
        (transfer_kind = 'INTER_WAREHOUSE' AND from_warehouse_id <> to_warehouse_id)
    ),
    CONSTRAINT chk_transfers_status CHECK (
        (status = 'CREATED'
            AND shipped_at IS NULL AND shipped_by IS NULL
            AND received_at IS NULL AND received_by IS NULL AND received_quantity IS NULL
            AND cancelled_at IS NULL AND cancelled_by IS NULL
            AND discrepancy_quantity IS NULL AND transit_location_id IS NULL) OR
        (status IN ('SHIPPED', 'IN_TRANSIT')
            AND shipped_at IS NOT NULL AND shipped_by IS NOT NULL AND transit_location_id IS NOT NULL
            AND received_at IS NULL AND received_quantity IS NULL AND cancelled_at IS NULL) OR
        (status = 'RECEIVED'
            AND shipped_at IS NOT NULL AND received_at IS NOT NULL AND received_by IS NOT NULL
            AND received_quantity IS NOT NULL AND received_quantity = quantity
            AND discrepancy_quantity IS NULL AND cancelled_at IS NULL) OR
        (status = 'DISCREPANCY'
            AND shipped_at IS NOT NULL AND received_at IS NOT NULL AND received_by IS NOT NULL
            AND received_quantity IS NOT NULL AND received_quantity < quantity
            AND discrepancy_quantity IS NOT NULL AND discrepancy_quantity = quantity - received_quantity
            AND cancelled_at IS NULL) OR
        (status = 'CANCELLED'
            AND cancelled_at IS NOT NULL AND cancelled_by IS NOT NULL
            AND shipped_at IS NULL AND received_at IS NULL
            AND received_quantity IS NULL AND transit_location_id IS NULL)
    ),
    CONSTRAINT chk_transfers_serial CHECK (serial_id IS NULL OR quantity = 1)
);

CREATE INDEX idx_transfers_number ON transfers (number);
CREATE INDEX idx_transfers_status ON transfers (status);
CREATE INDEX idx_transfers_from_warehouse ON transfers (from_warehouse_id, status);
CREATE INDEX idx_transfers_to_warehouse ON transfers (to_warehouse_id, status);
CREATE INDEX idx_transfers_nomenclature ON transfers (nomenclature_id);

-- Явный журнал перемещений: автор, время, количество и расхождение.
-- Неизменяемый (trg_transfer_events_immutable).
CREATE TABLE transfer_events
(
    id                   BIGSERIAL PRIMARY KEY,
    transfer_id          BIGINT         NOT NULL REFERENCES transfers (id),
    action               VARCHAR(16)    NOT NULL CHECK (action IN (
        'CREATED', 'SHIPPED', 'IN_TRANSIT', 'RECEIVED', 'DISCREPANCY', 'CANCELLED'
    )),
    from_status          VARCHAR(16),
    to_status            VARCHAR(16),
    quantity             NUMERIC(19, 4),
    discrepancy_quantity NUMERIC(19, 4),
    note                 TEXT,
    changed_by           BIGINT         NOT NULL REFERENCES users (id),
    created_at           TIMESTAMPTZ    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_transfer_events_transfer ON transfer_events (transfer_id, created_at);

-- Идемпотентность складских команд: ключ уникален в рамках пользователя и
-- операции, привязан к хешу тела запроса. Повтор с тем же телом возвращает
-- сохранённый ответ без повторного движения; повтор с другим телом отклоняется
-- сервером. Таблица append-only.
CREATE TABLE wms_idempotency_keys
(
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT       NOT NULL REFERENCES users (id),
    operation       VARCHAR(64)  NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash    VARCHAR(64)  NOT NULL,
    transfer_id     BIGINT REFERENCES transfers (id),
    response_status INTEGER      NOT NULL,
    response_body   TEXT         NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_wms_idempotency UNIQUE (user_id, operation, idempotency_key)
);

CREATE TRIGGER trg_transfer_events_immutable
    BEFORE UPDATE OR DELETE ON transfer_events
    FOR EACH ROW EXECUTE FUNCTION wms_forbid_mutation();

CREATE TRIGGER trg_wms_idempotency_keys_immutable
    BEFORE UPDATE OR DELETE ON wms_idempotency_keys
    FOR EACH ROW EXECUTE FUNCTION wms_forbid_mutation();

-- Документы перемещения нельзя удалять; терминальные (RECEIVED, CANCELLED)
-- нельзя изменять. DISCREPANCY остаётся изменяемым для будущего разрешения.
CREATE OR REPLACE FUNCTION wms_guard_transfer_mutation() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'DELETE: transfers are append-only';
    END IF;
    IF OLD.status IN ('RECEIVED', 'CANCELLED') THEN
        RAISE EXCEPTION 'UPDATE: transfer in terminal status % cannot be changed', OLD.status;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_transfers_mutation_guard
    BEFORE UPDATE OR DELETE ON transfers
    FOR EACH ROW EXECUTE FUNCTION wms_guard_transfer_mutation();
