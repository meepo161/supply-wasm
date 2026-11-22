-- Пакет 5-6: Комплектация/расход материалов производства, инженерные изменения (D-34),
-- шаблоны документов и расширение целей маршрутов согласования.

CREATE TABLE production_order_materials
(
    id                  BIGSERIAL PRIMARY KEY,
    production_order_id BIGINT         NOT NULL REFERENCES production_orders (id) ON DELETE CASCADE,
    nomenclature_id     BIGINT         NOT NULL REFERENCES nomenclature (id),
    planned_qty         NUMERIC(19, 4) NOT NULL CHECK (planned_qty >= 0),
    allocated_qty       NUMERIC(19, 4) NOT NULL DEFAULT 0 CHECK (allocated_qty >= 0),
    consumed_qty        NUMERIC(19, 4) NOT NULL DEFAULT 0 CHECK (consumed_qty >= 0),
    unit                VARCHAR(32)    NOT NULL,
    warehouse_id        BIGINT         REFERENCES warehouses (id),
    reservation_id      BIGINT         REFERENCES reservations (id),
    issue_order_id      BIGINT         REFERENCES issue_orders (id),
    updated_at          TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    version             BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT uq_prod_order_material UNIQUE (production_order_id, nomenclature_id)
);

CREATE TABLE engineering_change_impacts
(
    id                  BIGSERIAL PRIMARY KEY,
    production_order_id BIGINT      NOT NULL REFERENCES production_orders (id) ON DELETE CASCADE,
    old_bom_revision_id BIGINT      NOT NULL REFERENCES product_revisions (id),
    new_bom_revision_id BIGINT      NOT NULL REFERENCES product_revisions (id),
    impact_summary      TEXT        NOT NULL,
    material_decision   VARCHAR(32) NOT NULL CHECK (material_decision IN ('PENDING', 'RETURN_EXCESS', 'RESERVE_ADDITIONAL', 'REPLACE_ON_FLOOR', 'NO_CHANGE')),
    decision_comment    TEXT,
    status              VARCHAR(32) NOT NULL CHECK (status IN ('OPEN', 'ASSESSED', 'APPROVED', 'APPLIED', 'REJECTED')),
    created_by          BIGINT      NOT NULL REFERENCES users (id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    approved_by         BIGINT      REFERENCES users (id),
    approved_at         TIMESTAMPTZ,
    version             BIGINT      NOT NULL DEFAULT 0
);

CREATE TABLE document_templates
(
    id              BIGSERIAL PRIMARY KEY,
    code            VARCHAR(64)  NOT NULL UNIQUE,
    name            VARCHAR(255) NOT NULL,
    doc_type        VARCHAR(64)  NOT NULL,
    confidentiality VARCHAR(32)  NOT NULL DEFAULT 'INTERNAL',
    description     TEXT,
    active          BOOLEAN      NOT NULL DEFAULT TRUE,
    created_by      BIGINT       NOT NULL REFERENCES users (id),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

ALTER TABLE approval_templates DROP CONSTRAINT IF EXISTS chk_approval_templates_target_type;
ALTER TABLE approval_templates DROP CONSTRAINT IF EXISTS approval_templates_target_type_check;
ALTER TABLE approval_templates
    ADD CONSTRAINT chk_approval_templates_target_type CHECK (
        target_type IN ('DEMAND', 'WRITE_OFF', 'PURCHASE_ORDER', 'DOCUMENT', 'PRODUCTION_ORDER')
    );

ALTER TABLE approval_routes DROP CONSTRAINT IF EXISTS chk_approval_routes_target_type;
ALTER TABLE approval_routes DROP CONSTRAINT IF EXISTS approval_routes_target_type_check;
ALTER TABLE approval_routes
    ADD CONSTRAINT chk_approval_routes_target_type CHECK (
        target_type IN ('DEMAND', 'WRITE_OFF', 'PURCHASE_ORDER', 'DOCUMENT', 'PRODUCTION_ORDER')
    );
