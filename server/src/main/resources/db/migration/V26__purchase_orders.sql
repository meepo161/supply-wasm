-- V26: Заказ поставщику, связь с потребностями и частичные поставки.
-- Основания: ТЗ §9.3–9.8, §28.2; D-28; SUP-15, SUP-16.
--
-- Объём:
--  * заказ поставщику создаётся уполномоченным снабженцем (suppliers:order)
--    из ВЫБРАННОГО инициатором предложения (D-28); без выбранного предложения
--    заказ не создаётся — так исключается обход выбора;
--  * заказ хранит поставщика, позиции, цены, количество, сроки, адрес доставки,
--    условия оплаты, договор/счёт, документы, ответственного и статус;
--  * связь многие-ко-многим: одна закупка закрывает несколько потребностей,
--    одна потребность — несколькими закупками (приложение J, п. 3);
--  * частичные поставки: количества «заказано / поставлено / осталось»;
--  * после изменения существенных условий заказ требует повторного согласования.
--
-- Чего НЕТ: оплата, счета-фактуры, претензии, бухгалтерские проводки,
-- автоматическое создание заказов. Приёмка на склад идёт через WMS-документы
-- (receipts) и здесь только отмечает факт поставки по позиции заказа.

-- ---------------------------------------------------------------------------
-- Заказы поставщику
-- ---------------------------------------------------------------------------
CREATE TABLE purchase_orders
(
    id              BIGSERIAL PRIMARY KEY,
    number          VARCHAR(64)  NOT NULL UNIQUE,
    supplier_id     BIGINT       NOT NULL REFERENCES suppliers (id),
    -- Предложение, из которого создан заказ. Одно предложение — один заказ:
    -- частично «переиспользовать» выбранное предложение нельзя.
    offer_id        BIGINT       NOT NULL UNIQUE REFERENCES supplier_offers (id),
    status          VARCHAR(32)  NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'SENT', 'PARTIALLY_DELIVERED',
                          'DELIVERED', 'CLOSED', 'CANCELLED')),
    -- Валюта заказа фиксируется при создании; смешивать валюты в одном заказе нельзя.
    currency        VARCHAR(3)   NOT NULL DEFAULT 'RUB',
    delivery_address VARCHAR(1000),
    payment_terms   VARCHAR(500),
    contract_no     VARCHAR(128),
    invoice_no      VARCHAR(128),
    note            TEXT,
    responsible_id  BIGINT       NOT NULL REFERENCES users (id),
    ordered_at      TIMESTAMPTZ,
    expected_at     DATE,
    -- Согласование: суммы порогов не придумываются (§9.7 — открытый вопрос),
    -- поэтому фиксируется только факт согласования и его автор.
    approved_by     BIGINT REFERENCES users (id),
    approved_at     TIMESTAMPTZ,
    approval_note   TEXT,
    -- Признак необходимости повторного согласования после существенного изменения.
    reapproval_required BOOLEAN NOT NULL DEFAULT FALSE,
    reapproval_reason   TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by      BIGINT       NOT NULL REFERENCES users (id),
    updated_at      TIMESTAMPTZ,
    updated_by      BIGINT REFERENCES users (id),
    version         BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT chk_purchase_orders_approval CHECK (
        status NOT IN ('APPROVED', 'SENT', 'PARTIALLY_DELIVERED', 'DELIVERED', 'CLOSED')
            OR (approved_by IS NOT NULL AND approved_at IS NOT NULL)
    ),
    CONSTRAINT chk_purchase_orders_reapproval CHECK (
        reapproval_required = FALSE OR (reapproval_reason IS NOT NULL AND length(trim(reapproval_reason)) > 0)
    )
);

CREATE INDEX idx_purchase_orders_supplier ON purchase_orders (supplier_id);
CREATE INDEX idx_purchase_orders_status ON purchase_orders (status);
CREATE INDEX idx_purchase_orders_responsible ON purchase_orders (responsible_id);
CREATE INDEX idx_purchase_orders_expected ON purchase_orders (expected_at) WHERE expected_at IS NOT NULL;

CREATE TABLE purchase_order_lines
(
    id                BIGSERIAL PRIMARY KEY,
    order_id          BIGINT         NOT NULL REFERENCES purchase_orders (id),
    line_no           INTEGER        NOT NULL CHECK (line_no > 0),
    nomenclature_id   BIGINT         NOT NULL REFERENCES nomenclature (id),
    quantity          NUMERIC(19, 4) NOT NULL CHECK (quantity > 0),
    unit              VARCHAR(32)    NOT NULL,
    unit_price        NUMERIC(19, 4) NOT NULL CHECK (unit_price >= 0),
    vat_rate          NUMERIC(5, 2)  NOT NULL DEFAULT 20
        CHECK (vat_rate >= 0 AND vat_rate <= 100),
    line_total        NUMERIC(19, 4) GENERATED ALWAYS AS (quantity * unit_price) STORED,
    -- Сколько уже поставлено. Остаток считает view, а не клиент.
    delivered_quantity NUMERIC(19, 4) NOT NULL DEFAULT 0 CHECK (delivered_quantity >= 0),
    -- Позиция предложения, из которой пришла строка: прослеживаемость
    -- предложение → заказ → позиция.
    offer_line_id     BIGINT REFERENCES supplier_offer_lines (id),
    note              TEXT,
    created_at        TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    created_by        BIGINT         NOT NULL REFERENCES users (id),
    CONSTRAINT uq_purchase_order_lines_no UNIQUE (order_id, line_no),
    CONSTRAINT uq_purchase_order_lines_offer_line UNIQUE (order_id, offer_line_id),
    CONSTRAINT chk_purchase_order_lines_delivered CHECK (delivered_quantity <= quantity)
);

CREATE INDEX idx_purchase_order_lines_order ON purchase_order_lines (order_id);
CREATE INDEX idx_purchase_order_lines_nomenclature ON purchase_order_lines (nomenclature_id);

-- Связь заказа с потребностями многие-ко-многим (§9.3, приложение J, п. 3).
-- Одна потребность может закрываться несколькими заказами и поставками.
CREATE TABLE purchase_order_demands
(
    purchase_order_id BIGINT      NOT NULL REFERENCES purchase_orders (id),
    demand_id         BIGINT      NOT NULL REFERENCES demands (id),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by        BIGINT      NOT NULL REFERENCES users (id),
    PRIMARY KEY (purchase_order_id, demand_id)
);

CREATE INDEX idx_purchase_order_demands_demand ON purchase_order_demands (demand_id);

-- История заказа: append-only.
CREATE TABLE purchase_order_history
(
    id          BIGSERIAL PRIMARY KEY,
    order_id    BIGINT      NOT NULL REFERENCES purchase_orders (id),
    action      VARCHAR(32) NOT NULL,
    from_status VARCHAR(32),
    to_status   VARCHAR(32),
    reason      TEXT,
    changed_by  BIGINT      NOT NULL REFERENCES users (id),
    changed_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_purchase_order_history_order ON purchase_order_history (order_id);

-- ---------------------------------------------------------------------------
-- Частичные поставки
-- ---------------------------------------------------------------------------
CREATE TABLE purchase_deliveries
(
    id              BIGSERIAL PRIMARY KEY,
    number          VARCHAR(64) NOT NULL UNIQUE,
    order_id        BIGINT      NOT NULL REFERENCES purchase_orders (id),
    status          VARCHAR(32) NOT NULL DEFAULT 'REGISTERED'
        CHECK (status IN ('REGISTERED', 'CANCELLED')),
    delivered_at    DATE        NOT NULL,
    delivery_note   VARCHAR(255),
    -- Документ-основание поставки (накладная/УПД). Ссылка на WMS-приёмку
    -- появится, когда приёмка начнёт ссылаться на заказ; здесь — свободная ссылка.
    source_document VARCHAR(255),
    note            TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by      BIGINT      NOT NULL REFERENCES users (id),
    updated_at      TIMESTAMPTZ,
    updated_by      BIGINT REFERENCES users (id),
    version         BIGINT      NOT NULL DEFAULT 0
);

CREATE INDEX idx_purchase_deliveries_order ON purchase_deliveries (order_id);

CREATE TABLE purchase_delivery_lines
(
    id                   BIGSERIAL PRIMARY KEY,
    delivery_id          BIGINT         NOT NULL REFERENCES purchase_deliveries (id),
    order_line_id        BIGINT         NOT NULL REFERENCES purchase_order_lines (id),
    quantity             NUMERIC(19, 4) NOT NULL CHECK (quantity > 0),
    note                 TEXT,
    created_at           TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    created_by           BIGINT         NOT NULL REFERENCES users (id),
    CONSTRAINT uq_purchase_delivery_lines UNIQUE (delivery_id, order_line_id)
);

CREATE INDEX idx_purchase_delivery_lines_delivery ON purchase_delivery_lines (delivery_id);
CREATE INDEX idx_purchase_delivery_lines_order_line ON purchase_delivery_lines (order_line_id);

-- Остаток к поставке и суммы считает база.
CREATE VIEW purchase_order_line_totals AS
SELECT l.id                                              AS line_id,
       l.order_id                                        AS order_id,
       l.quantity                                        AS ordered_quantity,
       l.delivered_quantity                              AS delivered_quantity,
       (l.quantity - l.delivered_quantity)               AS remaining_quantity,
       l.line_total                                      AS line_total
FROM purchase_order_lines l;

-- Суммы считаются так же, как в предложениях: line_total — стоимость без НДС.
CREATE VIEW purchase_order_totals AS
SELECT o.id                                              AS order_id,
       COALESCE(SUM(l.line_total), 0)                    AS net_total,
       COALESCE(SUM(l.line_total * l.vat_rate / 100), 0) AS vat_total,
       COALESCE(SUM(l.line_total * (1 + l.vat_rate / 100)), 0) AS gross_total,
       COALESCE(SUM(l.quantity), 0)                      AS ordered_quantity,
       COALESCE(SUM(l.delivered_quantity), 0)            AS delivered_quantity
FROM purchase_orders o
         LEFT JOIN purchase_order_lines l ON l.order_id = o.id
GROUP BY o.id;

-- Идемпотентность команд заказа и поставки.
CREATE TABLE purchase_idempotency_keys
(
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT       NOT NULL REFERENCES users (id),
    operation       VARCHAR(64)  NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash    VARCHAR(64)  NOT NULL,
    entity_type     VARCHAR(32)  NOT NULL,
    entity_id       BIGINT       NOT NULL,
    response_status INTEGER      NOT NULL,
    response_body   TEXT         NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_purchase_idempotency UNIQUE (user_id, operation, idempotency_key)
);

CREATE TRIGGER trg_purchase_idempotency_immutable
    BEFORE UPDATE OR DELETE ON purchase_idempotency_keys
    FOR EACH ROW EXECUTE FUNCTION wms_forbid_mutation();

CREATE TRIGGER trg_purchase_order_history_immutable
    BEFORE UPDATE OR DELETE ON purchase_order_history
    FOR EACH ROW EXECUTE FUNCTION wms_forbid_mutation();
