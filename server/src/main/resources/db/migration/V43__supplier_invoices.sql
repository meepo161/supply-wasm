-- Счёт поставщика (D-61, D-62).
--
-- Счёт ведёт снабжение: файл счёта обязателен (документ DMS типа INVOICE),
-- счёт сверяется с заказом поставщику, затем снабжение оформляет по нему
-- поступление на склад. Склад только принимает товар и проводит приёмку (D-56).
-- Суммы хранятся так, как напечатаны в документе (NUMERIC(19,2)); пересчёт
-- выполняет сервис при сверке.

CREATE TABLE supplier_invoices
(
    id                          BIGSERIAL PRIMARY KEY,
    number                      VARCHAR(64)    NOT NULL CHECK (length(trim(number)) > 0),
    invoice_date                DATE           NOT NULL,
    supplier_id                 BIGINT         NOT NULL REFERENCES suppliers (id),
    purchase_order_id           BIGINT REFERENCES purchase_orders (id),
    document_id                 BIGINT         NOT NULL REFERENCES documents (id),
    supplier_inn                VARCHAR(12),
    supplier_kpp                VARCHAR(9),
    currency                    CHAR(3)        NOT NULL DEFAULT 'RUB',
    prices_include_vat          BOOLEAN        NOT NULL DEFAULT FALSE,
    net_total                   NUMERIC(19, 2) NOT NULL CHECK (net_total >= 0),
    vat_total                   NUMERIC(19, 2) NOT NULL CHECK (vat_total >= 0),
    gross_total                 NUMERIC(19, 2) NOT NULL CHECK (gross_total >= 0),
    valid_until                 DATE,
    payment_terms               TEXT,
    prepayment_pct              NUMERIC(5, 2) CHECK (prepayment_pct IS NULL OR (prepayment_pct >= 0 AND prepayment_pct <= 100)),
    lead_time_days              INTEGER CHECK (lead_time_days IS NULL OR lead_time_days >= 0),
    lead_time_kind              VARCHAR(16) CHECK (lead_time_kind IS NULL OR lead_time_kind IN ('WORKING', 'CALENDAR')),
    delivery_terms              TEXT,
    payment_purpose             TEXT,
    supplier_manager            TEXT,
    note                        TEXT,
    status                      VARCHAR(32)    NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT', 'CHECKED', 'CANCELLED')),
    accepted_discrepancy_reason TEXT,
    checked_by                  BIGINT REFERENCES users (id),
    checked_at                  TIMESTAMPTZ,
    cancel_reason               TEXT,
    created_by                  BIGINT         NOT NULL REFERENCES users (id),
    created_at                  TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_by                  BIGINT REFERENCES users (id),
    updated_at                  TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    version                     BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT chk_supplier_invoices_cancel_reason CHECK (
        status <> 'CANCELLED' OR (cancel_reason IS NOT NULL AND length(trim(cancel_reason)) > 0)
    ),
    CONSTRAINT chk_supplier_invoices_checked CHECK (
        status <> 'CHECKED' OR (checked_by IS NOT NULL AND checked_at IS NOT NULL)
    )
);

-- Повторная регистрация того же счёта поставщика запрещена; отменённый не мешает.
CREATE UNIQUE INDEX uq_supplier_invoices_number
    ON supplier_invoices (supplier_id, number, invoice_date)
    WHERE status <> 'CANCELLED';
CREATE INDEX idx_supplier_invoices_order ON supplier_invoices (purchase_order_id)
    WHERE purchase_order_id IS NOT NULL;
CREATE INDEX idx_supplier_invoices_status ON supplier_invoices (status);

CREATE TABLE supplier_invoice_lines
(
    id                     BIGSERIAL PRIMARY KEY,
    invoice_id             BIGINT         NOT NULL REFERENCES supplier_invoices (id) ON DELETE CASCADE,
    line_no                INTEGER        NOT NULL CHECK (line_no > 0),
    supplier_item_code     VARCHAR(128),
    supplier_item_name     VARCHAR(500)   NOT NULL CHECK (length(trim(supplier_item_name)) > 0),
    nomenclature_id        BIGINT REFERENCES nomenclature (id),
    purchase_order_line_id BIGINT REFERENCES purchase_order_lines (id),
    quantity               NUMERIC(19, 4) NOT NULL CHECK (quantity > 0),
    unit                   VARCHAR(32)    NOT NULL,
    unit_price             NUMERIC(19, 4) NOT NULL CHECK (unit_price >= 0),
    -- NULL означает «Без НДС» (это не то же самое, что ставка 0 %).
    vat_rate               NUMERIC(5, 2) CHECK (vat_rate IS NULL OR vat_rate >= 0),
    vat_amount             NUMERIC(19, 2) NOT NULL CHECK (vat_amount >= 0),
    line_total             NUMERIC(19, 2) NOT NULL CHECK (line_total >= 0),
    UNIQUE (invoice_id, line_no)
);

CREATE INDEX idx_supplier_invoice_lines_order_line ON supplier_invoice_lines (purchase_order_line_id)
    WHERE purchase_order_line_id IS NOT NULL;

-- История счёта: только добавление.
CREATE TABLE supplier_invoice_history
(
    id          BIGSERIAL PRIMARY KEY,
    invoice_id  BIGINT      NOT NULL REFERENCES supplier_invoices (id),
    action      VARCHAR(32) NOT NULL,
    from_status VARCHAR(32),
    to_status   VARCHAR(32),
    reason      TEXT,
    changed_by  BIGINT      NOT NULL REFERENCES users (id),
    changed_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_supplier_invoice_history_invoice ON supplier_invoice_history (invoice_id);

-- Поступление по счёту: приёмку с основанием INVOICE создаёт снабжение из счёта.
-- Один счёт даёт ровно одну действующую приёмку (D-56).
ALTER TABLE receipts ADD COLUMN supplier_invoice_id BIGINT REFERENCES supplier_invoices (id);

CREATE UNIQUE INDEX uq_receipts_supplier_invoice
    ON receipts (supplier_invoice_id)
    WHERE supplier_invoice_id IS NOT NULL AND status <> 'CANCELLED';

ALTER TABLE receipts ADD CONSTRAINT chk_receipts_supplier_invoice CHECK (
    supplier_invoice_id IS NULL OR source_type = 'INVOICE'
);

-- Номера счетов уникальны только в пределах поставщика: у двух поставщиков может
-- быть счёт с одним номером. Для приёмок по счёту уникальность обеспечивает
-- uq_receipts_supplier_invoice, общий индекс по номеру документа их не касается.
DROP INDEX uq_receipts_source_document;
CREATE UNIQUE INDEX uq_receipts_source_document
    ON receipts (source_type, source_document_no)
    WHERE source_document_no IS NOT NULL AND status <> 'CANCELLED' AND supplier_invoice_id IS NULL;
