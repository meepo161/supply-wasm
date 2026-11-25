-- V47: Сквозной процесс потребности без лишнего согласования (D-64):
--  * обязательный файл счёта поставщика на предложении (invoice_document_id, номер, дата);
--  * упрощённый выбор предложения в 1 клик (убрано требование >= 10 символов в selection_reason);
--  * отметка оплаты на заказе поставщику (paid_at, paid_by).

ALTER TABLE supplier_offers
    ADD COLUMN invoice_document_id BIGINT REFERENCES documents (id),
    ADD COLUMN invoice_number VARCHAR(64),
    ADD COLUMN invoice_date DATE;

ALTER TABLE supplier_offers
    DROP CONSTRAINT IF EXISTS chk_supplier_offers_selection;

ALTER TABLE supplier_offers
    ADD CONSTRAINT chk_supplier_offers_selection CHECK (
        status <> 'SELECTED' OR (
            selected_by IS NOT NULL
                AND selected_at IS NOT NULL
                AND selection_reason IS NOT NULL
                AND length(trim(selection_reason)) > 0
        )
    );

CREATE INDEX idx_supplier_offers_invoice_document
    ON supplier_offers (invoice_document_id)
    WHERE invoice_document_id IS NOT NULL;

ALTER TABLE purchase_orders
    ADD COLUMN paid_at TIMESTAMPTZ,
    ADD COLUMN paid_by BIGINT REFERENCES users (id),
    ADD CONSTRAINT chk_purchase_orders_paid CHECK (
        (paid_at IS NULL AND paid_by IS NULL) OR (paid_at IS NOT NULL AND paid_by IS NOT NULL)
    );

CREATE INDEX idx_purchase_orders_paid_at
    ON purchase_orders (paid_at)
    WHERE paid_at IS NOT NULL;
