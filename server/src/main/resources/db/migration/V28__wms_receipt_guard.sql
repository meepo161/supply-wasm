-- V28: Полный страж проведённой приёмки после введения поздних связей в V27.
-- Проведённый документ нельзя перезаписать или удалить. Однократное заполнение
-- заказа, поставки и номера документа остаётся разрешённым.

CREATE OR REPLACE FUNCTION wms_guard_receipt_mutation() RETURNS trigger AS $$
BEGIN
    IF OLD.status = 'POSTED' AND (
        TG_OP = 'DELETE'
            OR NEW.number IS DISTINCT FROM OLD.number
            OR NEW.source_type IS DISTINCT FROM OLD.source_type
            OR NEW.source_ref IS DISTINCT FROM OLD.source_ref
            OR NEW.warehouse_id IS DISTINCT FROM OLD.warehouse_id
            OR NEW.status IS DISTINCT FROM OLD.status
            OR NEW.expected_at IS DISTINCT FROM OLD.expected_at
            OR NEW.note IS DISTINCT FROM OLD.note
            OR NEW.posted_at IS DISTINCT FROM OLD.posted_at
            OR NEW.posted_by IS DISTINCT FROM OLD.posted_by
            OR NEW.created_at IS DISTINCT FROM OLD.created_at
            OR NEW.created_by IS DISTINCT FROM OLD.created_by
            OR NEW.version < OLD.version
            OR (OLD.purchase_order_id IS NOT NULL
                AND NEW.purchase_order_id IS DISTINCT FROM OLD.purchase_order_id)
            OR (OLD.purchase_delivery_id IS NOT NULL
                AND NEW.purchase_delivery_id IS DISTINCT FROM OLD.purchase_delivery_id)
            OR (OLD.source_document_no IS NOT NULL
                AND NEW.source_document_no IS DISTINCT FROM OLD.source_document_no)
        ) THEN
        RAISE EXCEPTION 'receipts: posted receipt cannot be rewritten or deleted';
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_receipts_guard ON receipts;

CREATE TRIGGER trg_receipts_guard
    BEFORE UPDATE OR DELETE ON receipts
    FOR EACH ROW EXECUTE FUNCTION wms_guard_receipt_mutation();
