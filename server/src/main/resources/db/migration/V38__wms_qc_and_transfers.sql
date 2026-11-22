-- Пакет 3: Контроль качества при приёмке WMS (WMS-37) и расхождения перемещений (WMS-25)

CREATE TABLE wms_receipt_qc
(
    id           BIGSERIAL PRIMARY KEY,
    receipt_id   BIGINT      NOT NULL UNIQUE REFERENCES receipts (id),
    status       VARCHAR(32) NOT NULL CHECK (status IN ('PENDING', 'PASSED', 'PARTIAL', 'FAILED')),
    inspector_id BIGINT      REFERENCES users (id),
    inspected_at TIMESTAMPTZ,
    comment      TEXT,
    version      BIGINT      NOT NULL DEFAULT 0,
    created_by   BIGINT      NOT NULL REFERENCES users (id),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE wms_receipt_qc_lines
(
    id              BIGSERIAL PRIMARY KEY,
    qc_id           BIGINT         NOT NULL REFERENCES wms_receipt_qc (id) ON DELETE CASCADE,
    receipt_line_id BIGINT         NOT NULL REFERENCES receipt_lines (id),
    accepted_qty    NUMERIC(19, 4) NOT NULL DEFAULT 0 CHECK (accepted_qty >= 0),
    quarantine_qty  NUMERIC(19, 4) NOT NULL DEFAULT 0 CHECK (quarantine_qty >= 0),
    defect_qty      NUMERIC(19, 4) NOT NULL DEFAULT 0 CHECK (defect_qty >= 0),
    defect_reason   TEXT,
    CONSTRAINT uq_wms_receipt_qc_line UNIQUE (qc_id, receipt_line_id)
);

ALTER TABLE transfers
    ADD COLUMN IF NOT EXISTS discrepancy_reason TEXT;
