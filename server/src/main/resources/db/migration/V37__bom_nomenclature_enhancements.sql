-- Пакет 2: НСИ и BOM (BOM-03, BOM-04, BOM-19)

ALTER TABLE nomenclature
    ADD COLUMN IF NOT EXISTS min_stock NUMERIC(19, 4) NOT NULL DEFAULT 0 CHECK (min_stock >= 0),
    ADD COLUMN IF NOT EXISTS safety_stock NUMERIC(19, 4) NOT NULL DEFAULT 0 CHECK (safety_stock >= 0),
    ADD COLUMN IF NOT EXISTS lead_time_days INTEGER NOT NULL DEFAULT 0 CHECK (lead_time_days >= 0);

CREATE TABLE nomenclature_unit_conversions
(
    id              BIGSERIAL PRIMARY KEY,
    nomenclature_id BIGINT         NOT NULL REFERENCES nomenclature (id) ON DELETE CASCADE,
    from_unit       VARCHAR(32)    NOT NULL,
    to_unit         VARCHAR(32)    NOT NULL,
    factor          NUMERIC(19, 6) NOT NULL CHECK (factor > 0),
    created_by      BIGINT         NOT NULL REFERENCES users (id),
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_nomenclature_unit_conv UNIQUE (nomenclature_id, from_unit, to_unit)
);

CREATE TABLE nomenclature_analogs
(
    id                      BIGSERIAL PRIMARY KEY,
    nomenclature_id         BIGINT         NOT NULL REFERENCES nomenclature (id) ON DELETE CASCADE,
    analog_nomenclature_id  BIGINT         NOT NULL REFERENCES nomenclature (id) ON DELETE CASCADE,
    conversion_factor       NUMERIC(19, 6) NOT NULL DEFAULT 1 CHECK (conversion_factor > 0),
    applicability_condition TEXT,
    priority                INTEGER        NOT NULL DEFAULT 1 CHECK (priority >= 1),
    active                  BOOLEAN        NOT NULL DEFAULT TRUE,
    created_by              BIGINT         NOT NULL REFERENCES users (id),
    created_at              TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_nomenclature_analog_distinct CHECK (nomenclature_id <> analog_nomenclature_id),
    CONSTRAINT uq_nomenclature_analog UNIQUE (nomenclature_id, analog_nomenclature_id)
);

CREATE INDEX idx_nomenclature_analogs_nom ON nomenclature_analogs (nomenclature_id);
