-- Пакет 4: Претензии поставщику (SUP-17)

CREATE TABLE supplier_claims
(
    id                   BIGSERIAL PRIMARY KEY,
    number               VARCHAR(32)    NOT NULL UNIQUE,
    supplier_id          BIGINT         NOT NULL REFERENCES suppliers (id),
    purchase_order_id    BIGINT         REFERENCES purchase_orders (id),
    purchase_delivery_id BIGINT         REFERENCES purchase_deliveries (id),
    receipt_id           BIGINT         REFERENCES receipts (id),
    claim_type           VARCHAR(32)    NOT NULL CHECK (claim_type IN ('SHORTAGE', 'DEFECT', 'DELAY', 'PRICE_MISMATCH', 'OTHER')),
    status               VARCHAR(32)    NOT NULL CHECK (status IN ('OPEN', 'SENT', 'IN_REVIEW', 'RESOLVED', 'CLOSED', 'CANCELLED')),
    description          TEXT           NOT NULL,
    claim_amount         NUMERIC(19, 4) NOT NULL DEFAULT 0 CHECK (claim_amount >= 0),
    currency             VARCHAR(8)     NOT NULL DEFAULT 'RUB',
    resolution           TEXT,
    created_by           BIGINT         NOT NULL REFERENCES users (id),
    created_at           TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    resolved_by          BIGINT         REFERENCES users (id),
    resolved_at          TIMESTAMPTZ,
    version              BIGINT         NOT NULL DEFAULT 0
);

CREATE INDEX idx_supplier_claims_supplier ON supplier_claims (supplier_id);
CREATE INDEX idx_supplier_claims_po ON supplier_claims (purchase_order_id);
CREATE INDEX idx_supplier_claims_status ON supplier_claims (status);
