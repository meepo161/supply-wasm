-- V25: Поставщики и коммерческие предложения по потребности.
-- Основания: ТЗ §9.5–9.8, §17; D-27, D-28; SUP-13, SUP-14, SUP-PROC-001 (часть 1).
--
-- Что входит в объём: карточка поставщика (реквизиты, контакты, адреса,
-- категории, документы, статус проверки), предложения поставщиков по
-- потребности (позиции, цены, валюта, срок, доставка, оплата, гарантия,
-- соответствие ТЗ, срок действия) и выбор предпочтительного предложения
-- инициатором потребности.
--
-- Чего сознательно НЕТ (по поручению пользователя и решению D-28):
-- заказы поставщику, платежи, претензии, автоматическая закупка и
-- финансовое согласование. Соответственно нет и порогов согласования:
-- они остаются открытым вопросом §9.7 и не подменяются выдуманными числами.
--
-- Инварианты:
--  * карточка поставщика версионируется; удаления нет, есть признак archived;
--  * предложение принадлежит ровно одной потребности; несколько предложений
--    по одной потребности — норма (это и есть сбор предложений);
--  * предпочтительное предложение выбирает инициатор потребности (D-28),
--    сервер проверяет, что заявленные тарифы не «пустые»: цена и сроки должны
--    быть нормально заданы. Выбор исключительно по минимальной цене невозможен
--    потому, что решение требует обоснования и проверок соответствия;
--  * строка предложения ссылается на позицию потребности, количество не может
--    превысить дефицит этой позиции;
--  * истории и ревизии предложения append-only (триггеры);
--  * команды создания и выбора идемпотентны по ключу (user_id, operation, key).

-- ---------------------------------------------------------------------------
-- Поставщики
-- ---------------------------------------------------------------------------
CREATE TABLE suppliers
(
    id             BIGSERIAL PRIMARY KEY,
    number         VARCHAR(64)  NOT NULL UNIQUE,
    name           VARCHAR(500) NOT NULL,
    full_name      VARCHAR(1000),
    kind           VARCHAR(32)  NOT NULL DEFAULT 'SUPPLIER'
        CHECK (kind IN ('SUPPLIER', 'CONTRACTOR', 'MANUFACTURER')),
    inn            VARCHAR(12),
    kpp            VARCHAR(9),
    ogrn           VARCHAR(15),
    okpo           VARCHAR(10),
    country        VARCHAR(64),
    legal_address  VARCHAR(1000),
    site           VARCHAR(255),
    email          VARCHAR(255),
    phone          VARCHAR(64),
    responsible_id BIGINT REFERENCES users (id),
    verification_status VARCHAR(32) NOT NULL DEFAULT 'NOT_VERIFIED'
        CHECK (verification_status IN ('NOT_VERIFIED', 'IN_REVIEW', 'VERIFIED', 'REJECTED')),
    verification_note   TEXT,
    verified_by    BIGINT REFERENCES users (id),
    verified_at    TIMESTAMPTZ,
    note           TEXT,
    archived       BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by     BIGINT       NOT NULL REFERENCES users (id),
    updated_at     TIMESTAMPTZ,
    updated_by     BIGINT REFERENCES users (id),
    version        BIGINT       NOT NULL DEFAULT 0,
    -- ИНН уникален среди неудалённых карточек: дубль поставщика — реальная
    -- проблема, которую ТЗ §17 требует не допускать молча.
    CONSTRAINT chk_suppliers_inn_format CHECK (inn IS NULL OR inn ~ '^[0-9]{10}$' OR inn ~ '^[0-9]{12}$'),
    CONSTRAINT chk_suppliers_verification CHECK (
        verification_status <> 'VERIFIED' OR (verified_by IS NOT NULL AND verified_at IS NOT NULL)
    )
);

CREATE UNIQUE INDEX uq_suppliers_inn ON suppliers (inn) WHERE inn IS NOT NULL AND archived = FALSE;
CREATE INDEX idx_suppliers_name ON suppliers (name);
CREATE INDEX idx_suppliers_status ON suppliers (verification_status);
CREATE INDEX idx_suppliers_responsible ON suppliers (responsible_id) WHERE responsible_id IS NOT NULL;
CREATE INDEX idx_suppliers_archived ON suppliers (archived);

-- Контактные лица поставщика: должность, каналы связи.
CREATE TABLE supplier_contacts
(
    id           BIGSERIAL PRIMARY KEY,
    supplier_id  BIGINT       NOT NULL REFERENCES suppliers (id),
    full_name    VARCHAR(255) NOT NULL,
    position     VARCHAR(255),
    email        VARCHAR(255),
    phone        VARCHAR(64),
    messenger    VARCHAR(255),
    is_primary   BOOLEAN      NOT NULL DEFAULT FALSE,
    note         TEXT,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by   BIGINT       NOT NULL REFERENCES users (id),
    version      BIGINT       NOT NULL DEFAULT 0
);

CREATE INDEX idx_supplier_contacts_supplier ON supplier_contacts (supplier_id);

-- Адреса: юридический, фактический, почтовый, адрес склада отгрузки.
CREATE TABLE supplier_addresses
(
    id          BIGSERIAL PRIMARY KEY,
    supplier_id BIGINT        NOT NULL REFERENCES suppliers (id),
    kind        VARCHAR(32)   NOT NULL DEFAULT 'LEGAL'
        CHECK (kind IN ('LEGAL', 'ACTUAL', 'POSTAL', 'WAREHOUSE', 'DELIVERY')),
    address     VARCHAR(1000) NOT NULL,
    note        TEXT,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    created_by  BIGINT        NOT NULL REFERENCES users (id)
);

CREATE INDEX idx_supplier_addresses_supplier ON supplier_addresses (supplier_id);

-- Категории закупаемой продукции. Справочник расширяемый: категория не
-- выводится из номенклатуры автоматически (это потребовало бы утверждённого
-- правила сопоставления).
CREATE TABLE supplier_categories
(
    id          BIGSERIAL PRIMARY KEY,
    code        VARCHAR(64)  NOT NULL UNIQUE,
    name        VARCHAR(255) NOT NULL,
    description TEXT,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by  BIGINT       NOT NULL REFERENCES users (id)
);

CREATE TABLE supplier_category_links
(
    supplier_id BIGINT NOT NULL REFERENCES suppliers (id),
    category_id BIGINT NOT NULL REFERENCES supplier_categories (id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by  BIGINT NOT NULL REFERENCES users (id),
    PRIMARY KEY (supplier_id, category_id)
);

-- Документы поставщика: договоры, сертификаты, лицензии. Сам файл лежит в DMS
-- (attachments), здесь — учётная карточка документа и срок её действия.
CREATE TABLE supplier_documents
(
    id           BIGSERIAL PRIMARY KEY,
    supplier_id  BIGINT        NOT NULL REFERENCES suppliers (id),
    kind         VARCHAR(32)   NOT NULL
        CHECK (kind IN ('CONTRACT', 'CERTIFICATE', 'LICENSE', 'AGREEMENT', 'OTHER')),
    title        VARCHAR(500)  NOT NULL,
    document_no  VARCHAR(128),
    valid_from   DATE,
    valid_to     DATE,
    attachment_id BIGINT REFERENCES attachments (id),
    note         TEXT,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    created_by   BIGINT        NOT NULL REFERENCES users (id),
    version      BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT chk_supplier_documents_range CHECK (valid_to IS NULL OR valid_from IS NULL OR valid_to >= valid_from)
);

CREATE INDEX idx_supplier_documents_supplier ON supplier_documents (supplier_id);
CREATE INDEX idx_supplier_documents_valid_to ON supplier_documents (valid_to) WHERE valid_to IS NOT NULL;

-- ---------------------------------------------------------------------------
-- Предложения по потребности (§9.5–9.6)
-- ---------------------------------------------------------------------------
CREATE TABLE supplier_offers
(
    id                  BIGSERIAL PRIMARY KEY,
    number              VARCHAR(64)  NOT NULL UNIQUE,
    demand_id           BIGINT       NOT NULL REFERENCES demands (id),
    supplier_id         BIGINT       NOT NULL REFERENCES suppliers (id),
    status              VARCHAR(32)  NOT NULL DEFAULT 'RECEIVED'
        CHECK (status IN ('RECEIVED', 'SHORTLISTED', 'SELECTED', 'REJECTED', 'WITHDRAWN', 'EXPIRED')),
    currency            VARCHAR(3)   NOT NULL DEFAULT 'RUB',
    -- Соответствие техническим требованиям потребности и наличие документов
    -- проверяются человеком; значения фиксируются явно, а не выводятся из цены.
    compliance          VARCHAR(32)  NOT NULL DEFAULT 'UNKNOWN'
        CHECK (compliance IN ('UNKNOWN', 'FULL', 'PARTIAL', 'DEVIATION')),
    compliance_note     TEXT,
    documents_complete  BOOLEAN      NOT NULL DEFAULT FALSE,
    delivery_days       INTEGER      CHECK (delivery_days IS NULL OR delivery_days >= 0),
    delivery_terms      VARCHAR(500),
    delivery_cost       NUMERIC(19, 4) CHECK (delivery_cost IS NULL OR delivery_cost >= 0),
    payment_terms       VARCHAR(500),
    warranty_months     INTEGER      CHECK (warranty_months IS NULL OR warranty_months >= 0),
    valid_until         DATE         NOT NULL,
    supplier_contact_name VARCHAR(255),
    supplier_contact_channel VARCHAR(255),
    note                TEXT,
    requested_by        BIGINT       NOT NULL REFERENCES users (id),
    requested_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    -- Выбор делает инициатор потребности (D-28); сервер проверяет полномочие.
    selected_by         BIGINT REFERENCES users (id),
    selected_at         TIMESTAMPTZ,
    selection_reason    TEXT,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by          BIGINT       NOT NULL REFERENCES users (id),
    updated_at          TIMESTAMPTZ,
    updated_by          BIGINT REFERENCES users (id),
    version             BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT chk_supplier_offers_selection CHECK (
        status <> 'SELECTED' OR (selected_by IS NOT NULL AND selected_at IS NOT NULL
                                 AND selection_reason IS NOT NULL AND length(trim(selection_reason)) >= 10)
    )
);

CREATE INDEX idx_supplier_offers_demand ON supplier_offers (demand_id);
CREATE INDEX idx_supplier_offers_supplier ON supplier_offers (supplier_id);
CREATE INDEX idx_supplier_offers_status ON supplier_offers (status);
CREATE INDEX idx_supplier_offers_valid_until ON supplier_offers (valid_until);

-- Одно предпочтительное предложение на потребность: частичный уникальный
-- индекс не даёт выбрать два «лучших» одновременно.
CREATE UNIQUE INDEX uq_supplier_offers_selected_per_demand
    ON supplier_offers (demand_id) WHERE status = 'SELECTED';

CREATE TABLE supplier_offer_lines
(
    id                BIGSERIAL PRIMARY KEY,
    offer_id          BIGINT         NOT NULL REFERENCES supplier_offers (id),
    line_no           INTEGER        NOT NULL CHECK (line_no > 0),
    demand_line_id    BIGINT         NOT NULL REFERENCES demand_lines (id),
    nomenclature_id   BIGINT         NOT NULL REFERENCES nomenclature (id),
    quantity          NUMERIC(19, 4) NOT NULL CHECK (quantity > 0),
    unit              VARCHAR(32)    NOT NULL,
    unit_price        NUMERIC(19, 4) NOT NULL CHECK (unit_price >= 0),
    -- Ставка НДС в процентах: 0, 10 или 20 — рабочее значение, не утверждённая
    -- бухгалтерская политика. Хранится явно, чтобы итог не «досчитывался» молча.
    vat_rate          NUMERIC(5, 2)  NOT NULL DEFAULT 20
        CHECK (vat_rate >= 0 AND vat_rate <= 100),
    -- Производные суммы хранит база: интерфейс не пересчитывает деньги сам.
    line_total        NUMERIC(19, 4) GENERATED ALWAYS AS (quantity * unit_price) STORED,
    note              TEXT,
    created_at        TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    created_by        BIGINT         NOT NULL REFERENCES users (id),
    CONSTRAINT uq_supplier_offer_lines_no UNIQUE (offer_id, line_no),
    CONSTRAINT uq_supplier_offer_lines_demand_line UNIQUE (offer_id, demand_line_id)
);

CREATE INDEX idx_supplier_offer_lines_offer ON supplier_offer_lines (offer_id);
CREATE INDEX idx_supplier_offer_lines_demand_line ON supplier_offer_lines (demand_line_id);

-- Итог по предложению считает база, а не клиент.
CREATE VIEW supplier_offer_totals AS
SELECT o.id                                    AS offer_id,
       COALESCE(SUM(l.line_total), 0)          AS net_total,
       COALESCE(SUM(l.line_total * l.vat_rate / 100), 0) AS vat_total,
       COALESCE(SUM(l.line_total * (1 + l.vat_rate / 100)), 0)
           + COALESCE(o.delivery_cost, 0)      AS gross_total
FROM supplier_offers o
         LEFT JOIN supplier_offer_lines l ON l.offer_id = o.id
GROUP BY o.id, o.delivery_cost;

-- История предложения: append-only.
CREATE TABLE supplier_offer_history
(
    id           BIGSERIAL PRIMARY KEY,
    offer_id     BIGINT      NOT NULL REFERENCES supplier_offers (id),
    action       VARCHAR(32) NOT NULL,
    from_status  VARCHAR(32),
    to_status    VARCHAR(32),
    reason       TEXT,
    changed_by   BIGINT      NOT NULL REFERENCES users (id),
    changed_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_supplier_offer_history_offer ON supplier_offer_history (offer_id);

-- Идемпотентность команд: создание предложения и выбор предложения.
CREATE TABLE supplier_idempotency_keys
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
    CONSTRAINT uq_supplier_idempotency UNIQUE (user_id, operation, idempotency_key)
);

CREATE TRIGGER trg_supplier_idempotency_immutable
    BEFORE UPDATE OR DELETE ON supplier_idempotency_keys
    FOR EACH ROW EXECUTE FUNCTION wms_forbid_mutation();

CREATE TRIGGER trg_supplier_offer_history_immutable
    BEFORE UPDATE OR DELETE ON supplier_offer_history
    FOR EACH ROW EXECUTE FUNCTION wms_forbid_mutation();
