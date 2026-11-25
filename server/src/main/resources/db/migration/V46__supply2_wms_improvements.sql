-- Улучшения WMS из supply2:
-- 1) Даты поверки (метрология) на серийных экземплярах;
-- 2) Пауза и возобновление инвентаризации (статус PAUSED).

ALTER TABLE serial_items
    ADD COLUMN calibration_date DATE,
    ADD COLUMN calibration_expiry_date DATE;

ALTER TABLE inventory_orders
    ADD COLUMN paused_at TIMESTAMPTZ,
    ADD COLUMN paused_by BIGINT REFERENCES users (id),
    ADD COLUMN paused_from_status VARCHAR(16);

ALTER TABLE inventory_orders
    DROP CONSTRAINT IF EXISTS inventory_orders_status_check;

ALTER TABLE inventory_orders
    ADD CONSTRAINT inventory_orders_status_check CHECK (status IN (
        'DRAFT', 'BOOKED', 'COUNTING', 'DISCREPANCY', 'PAUSED', 'APPROVED', 'POSTED', 'CANCELLED'
    ));

ALTER TABLE inventory_orders
    DROP CONSTRAINT IF EXISTS chk_inventory_status;

ALTER TABLE inventory_orders
    ADD CONSTRAINT chk_inventory_status CHECK (
        (status = 'DRAFT'
            AND booked_at IS NULL AND counted_at IS NULL
            AND approved_at IS NULL AND posted_at IS NULL AND cancelled_at IS NULL AND paused_at IS NULL) OR
        (status = 'BOOKED'
            AND booked_at IS NOT NULL AND booked_by IS NOT NULL AND counted_at IS NULL
            AND approved_at IS NULL AND posted_at IS NULL AND cancelled_at IS NULL AND paused_at IS NULL) OR
        (status IN ('COUNTING', 'DISCREPANCY')
            AND booked_at IS NOT NULL AND counted_at IS NOT NULL
            AND approved_at IS NULL AND posted_at IS NULL AND cancelled_at IS NULL AND paused_at IS NULL) OR
        (status = 'PAUSED'
            AND booked_at IS NOT NULL AND paused_at IS NOT NULL AND paused_by IS NOT NULL
            AND paused_from_status IN ('BOOKED', 'COUNTING', 'DISCREPANCY')
            AND approved_at IS NULL AND posted_at IS NULL AND cancelled_at IS NULL) OR
        (status = 'APPROVED'
            AND booked_at IS NOT NULL AND counted_at IS NOT NULL
            AND approved_at IS NOT NULL AND approved_by IS NOT NULL
            AND posted_at IS NULL AND cancelled_at IS NULL AND paused_at IS NULL) OR
        (status = 'POSTED'
            AND booked_at IS NOT NULL AND counted_at IS NOT NULL
            AND posted_at IS NOT NULL AND posted_by IS NOT NULL AND cancelled_at IS NULL AND paused_at IS NULL) OR
        (status = 'CANCELLED'
            AND cancelled_at IS NOT NULL AND cancelled_by IS NOT NULL AND posted_at IS NULL)
    );
