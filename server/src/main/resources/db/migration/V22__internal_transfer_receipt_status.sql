-- V22: Допуск INTERNAL RECEIVED без этапа отгрузки в chk_transfers_status.
-- Внутрискладское перемещение проводится атомарно при создании (V15,
-- WmsTransferService.createTransfer): документ сразу получает RECEIVED без
-- shipped_at/shipped_by и транзитной ячейки. Исходный CHECK требовал shipped_at
-- для любого RECEIVED, из-за чего создание INTERNAL-перемещения падало с 500.
-- Для INTER_WAREHOUSE требование shipped_at/shipped_by при RECEIVED сохранено;
-- остальные инварианты статусов не изменены. Миграция заменяет named CHECK:
-- существующие строки остаются валидными (сервис при отгрузке всегда
-- проставляет shipped_by), поэтому NOT VALID/VALIDATE не требуется.

ALTER TABLE transfers DROP CONSTRAINT chk_transfers_status;

ALTER TABLE transfers ADD CONSTRAINT chk_transfers_status CHECK (
    (status = 'CREATED'
        AND shipped_at IS NULL AND shipped_by IS NULL
        AND received_at IS NULL AND received_by IS NULL AND received_quantity IS NULL
        AND cancelled_at IS NULL AND cancelled_by IS NULL
        AND discrepancy_quantity IS NULL AND transit_location_id IS NULL) OR
    (status IN ('SHIPPED', 'IN_TRANSIT')
        AND shipped_at IS NOT NULL AND shipped_by IS NOT NULL AND transit_location_id IS NOT NULL
        AND received_at IS NULL AND received_quantity IS NULL AND cancelled_at IS NULL) OR
    (status = 'RECEIVED'
        AND (transfer_kind = 'INTERNAL' OR (shipped_at IS NOT NULL AND shipped_by IS NOT NULL))
        AND received_at IS NOT NULL AND received_by IS NOT NULL
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
);
