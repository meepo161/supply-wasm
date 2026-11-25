ALTER TABLE supplier_offers
    DROP CONSTRAINT IF EXISTS supplier_offers_status_check;

ALTER TABLE supplier_offers
    ADD CONSTRAINT supplier_offers_status_check CHECK (
        status IN ('RECEIVED', 'SHORTLISTED', 'NEEDS_REVISION', 'SELECTED', 'REJECTED', 'WITHDRAWN', 'EXPIRED')
    ),
    ADD COLUMN IF NOT EXISTS revision_note TEXT;

ALTER TABLE demand_lines
    ADD COLUMN IF NOT EXISTS stock_reserved_quantity NUMERIC(19, 4) NOT NULL DEFAULT 0
        CHECK (stock_reserved_quantity >= 0);

ALTER TABLE receipt_lines
    ADD COLUMN IF NOT EXISTS auto_reserved_quantity NUMERIC(19, 4) NOT NULL DEFAULT 0
        CHECK (auto_reserved_quantity >= 0);
