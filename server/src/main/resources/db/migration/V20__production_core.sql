-- V20: Production core — production orders, stages, physical instance cards,
-- actual configuration, tests, remarks and nonconformities.
--
-- Sources: TZ §15–16, appendices J/K/L, decisions D-31–D-42.
-- Constraints:
-- * FK only to existing tables (projects, product_models, product_revisions,
--   users, departments, nomenclature, lots, serial_items, bom_lines,
--   document_versions, tasks).
-- * No automatic WMS postings, no production API/UI, no approval routes.
-- * Partial uniqueness uses CREATE UNIQUE INDEX ... WHERE, not table-level
--   UNIQUE(...) WHERE.
-- * Instance cards are NOT auto-created with the order; the server creates them
--   only upon actual completion of manufacturing.

-- ---------------------------------------------------------------------------
-- Production order.
-- ---------------------------------------------------------------------------
CREATE TABLE production_orders
(
    id              BIGSERIAL PRIMARY KEY,
    number          VARCHAR(64)   NOT NULL UNIQUE,
    project_id      BIGINT        NOT NULL REFERENCES projects (id),
    product_model_id BIGINT       NOT NULL REFERENCES product_models (id),
    bom_revision_id BIGINT        NOT NULL REFERENCES product_revisions (id),
    status          VARCHAR(32)   NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT', 'PLANNED', 'IN_PROGRESS', 'ON_HOLD', 'COMPLETED', 'CLOSED', 'CANCELLED')),
    planned_quantity INTEGER      NOT NULL CHECK (planned_quantity > 0),
    actual_quantity INTEGER       NOT NULL DEFAULT 0 CHECK (actual_quantity >= 0),
    planned_start_at TIMESTAMPTZ,
    planned_end_at  TIMESTAMPTZ,
    actual_start_at TIMESTAMPTZ,
    actual_end_at   TIMESTAMPTZ,
    responsible_id  BIGINT        NOT NULL REFERENCES users (id),
    department_id   BIGINT REFERENCES departments (id),
    priority        VARCHAR(16)   NOT NULL DEFAULT 'NORMAL',
    version         BIGINT        NOT NULL DEFAULT 0,
    created_by      BIGINT        NOT NULL REFERENCES users (id),
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_production_orders_planned_dates CHECK (
        planned_start_at IS NULL OR planned_end_at IS NULL OR planned_end_at >= planned_start_at
    ),
    CONSTRAINT chk_production_orders_actual_dates CHECK (
        actual_start_at IS NULL OR actual_end_at IS NULL OR actual_end_at >= actual_start_at
    ),
    CONSTRAINT chk_production_orders_quantity CHECK (actual_quantity <= planned_quantity)
);

CREATE INDEX idx_production_orders_number ON production_orders (number);
CREATE INDEX idx_production_orders_project ON production_orders (project_id);
CREATE INDEX idx_production_orders_model ON production_orders (product_model_id);
CREATE INDEX idx_production_orders_revision ON production_orders (bom_revision_id);
CREATE INDEX idx_production_orders_status ON production_orders (status);

-- ---------------------------------------------------------------------------
-- Production order stages.
-- ---------------------------------------------------------------------------
CREATE TABLE production_order_stages
(
    id                    BIGSERIAL PRIMARY KEY,
    order_id              BIGINT        NOT NULL REFERENCES production_orders (id),
    stage_type            VARCHAR(32)   NOT NULL
        CHECK (stage_type IN (
            'PREPARATION', 'KITTING', 'ASSEMBLY', 'MOUNTING', 'SETUP',
            'TESTING', 'CONTROL', 'REMARK_FIX', 'ACCEPTANCE', 'COMPLETION'
        )),
    name                  VARCHAR(255)  NOT NULL,
    sequence              INTEGER       NOT NULL CHECK (sequence > 0),
    status                VARCHAR(32)   NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'IN_PROGRESS', 'COMPLETED', 'SKIPPED')),
    planned_start_at      TIMESTAMPTZ,
    planned_end_at        TIMESTAMPTZ,
    actual_start_at       TIMESTAMPTZ,
    actual_end_at         TIMESTAMPTZ,
    responsible_id        BIGINT REFERENCES users (id),
    required_materials_ok BOOLEAN       NOT NULL DEFAULT FALSE,
    required_documents_ok BOOLEAN       NOT NULL DEFAULT FALSE,
    skip_reason           TEXT,
    notes                 TEXT,
    version               BIGINT        NOT NULL DEFAULT 0,
    created_by            BIGINT        NOT NULL REFERENCES users (id),
    created_at            TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_production_order_stages_order_sequence UNIQUE (order_id, sequence)
);

CREATE INDEX idx_production_order_stages_order ON production_order_stages (order_id, sequence);
CREATE INDEX idx_production_order_stages_status ON production_order_stages (status);

-- ---------------------------------------------------------------------------
-- Stage assignees.
-- ---------------------------------------------------------------------------
CREATE TABLE production_stage_assignees
(
    stage_id   BIGINT      NOT NULL REFERENCES production_order_stages (id),
    user_id    BIGINT      NOT NULL REFERENCES users (id),
    role       VARCHAR(16) NOT NULL CHECK (role IN ('EXECUTOR', 'CONTROLLER', 'REVIEWER')),
    created_by BIGINT      NOT NULL REFERENCES users (id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (stage_id, user_id, role)
);

-- ---------------------------------------------------------------------------
-- Physical product instance. Created by the server only when manufacturing
-- of a unit is actually completed.
-- ---------------------------------------------------------------------------
CREATE TABLE product_instances
(
    id              BIGSERIAL PRIMARY KEY,
    order_id        BIGINT        NOT NULL REFERENCES production_orders (id),
    project_id      BIGINT        NOT NULL REFERENCES projects (id),
    product_model_id BIGINT       NOT NULL REFERENCES product_models (id),
    bom_revision_id BIGINT        NOT NULL REFERENCES product_revisions (id),
    serial_number   VARCHAR(128)  NOT NULL,
    status          VARCHAR(32)   NOT NULL DEFAULT 'MANUFACTURED'
        CHECK (status IN ('MANUFACTURED', 'TESTING', 'ACCEPTED', 'REJECTED', 'SHIPPED')),
    manufactured_at TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    accepted_at     TIMESTAMPTZ,
    shipped_at      TIMESTAMPTZ,
    version         BIGINT        NOT NULL DEFAULT 0,
    created_by      BIGINT        NOT NULL REFERENCES users (id),
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_product_instances_serial_number UNIQUE (serial_number)
);

CREATE INDEX idx_product_instances_order ON product_instances (order_id);
CREATE INDEX idx_product_instances_project ON product_instances (project_id);
CREATE INDEX idx_product_instances_model ON product_instances (product_model_id);
CREATE INDEX idx_product_instances_status ON product_instances (status);

-- ---------------------------------------------------------------------------
-- Actual instance configuration: installed components.
-- ---------------------------------------------------------------------------
CREATE TABLE instance_components
(
    id                BIGSERIAL PRIMARY KEY,
    instance_id       BIGINT         NOT NULL REFERENCES product_instances (id),
    nomenclature_id   BIGINT         NOT NULL REFERENCES nomenclature (id),
    lot_id            BIGINT REFERENCES lots (id),
    serial_id         BIGINT REFERENCES serial_items (id),
    quantity          NUMERIC(19, 4) NOT NULL CHECK (quantity > 0),
    source_bom_line_id BIGINT REFERENCES bom_lines (id),
    substitute_reason TEXT,
    installed_by      BIGINT         NOT NULL REFERENCES users (id),
    installed_at      TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_instance_components_serial_qty CHECK (
        serial_id IS NULL OR quantity = 1
    )
);

CREATE INDEX idx_instance_components_instance ON instance_components (instance_id);
CREATE INDEX idx_instance_components_nomenclature ON instance_components (nomenclature_id);
CREATE INDEX idx_instance_components_serial ON instance_components (serial_id) WHERE serial_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- Documents and software applied to the instance.
-- ---------------------------------------------------------------------------
CREATE TABLE instance_documents
(
    id                  BIGSERIAL PRIMARY KEY,
    instance_id         BIGINT      NOT NULL REFERENCES product_instances (id),
    document_version_id BIGINT      NOT NULL REFERENCES document_versions (id),
    doc_kind            VARCHAR(32) NOT NULL
        CHECK (doc_kind IN ('MANUAL', 'DRAWING', 'SPECIFICATION', 'TEST_PROTOCOL', 'CERTIFICATE', 'OTHER')),
    version             BIGINT      NOT NULL DEFAULT 0,
    created_by          BIGINT      NOT NULL REFERENCES users (id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_instance_documents_instance ON instance_documents (instance_id);
CREATE INDEX idx_instance_documents_version ON instance_documents (document_version_id);

CREATE UNIQUE INDEX uq_instance_documents_instance_kind_version
    ON instance_documents (instance_id, doc_kind, document_version_id);

CREATE TABLE instance_software
(
    id           BIGSERIAL PRIMARY KEY,
    instance_id  BIGINT      NOT NULL REFERENCES product_instances (id),
    name         VARCHAR(255) NOT NULL,
    version      VARCHAR(64)  NOT NULL,
    build        VARCHAR(64),
    installed_by BIGINT       NOT NULL REFERENCES users (id),
    installed_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by   BIGINT       NOT NULL REFERENCES users (id),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_instance_software_instance ON instance_software (instance_id);

CREATE UNIQUE INDEX uq_instance_software_instance_name_version_build
    ON instance_software (instance_id, name, version, COALESCE(build, ''));

-- ---------------------------------------------------------------------------
-- Instance tests. Result is GOOD/NOT_GOOD under a personal account.
-- Corrections are made by creating a new test row with supersedes_test_id;
-- accepted decisions are never overwritten.
-- ---------------------------------------------------------------------------
CREATE TABLE instance_tests
(
    id                        BIGSERIAL PRIMARY KEY,
    instance_id               BIGINT      NOT NULL REFERENCES product_instances (id),
    order_id                  BIGINT REFERENCES production_orders (id),
    protocol_document_version_id BIGINT REFERENCES document_versions (id),
    performed_by              BIGINT      NOT NULL REFERENCES users (id),
    performed_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    result                    VARCHAR(16) NOT NULL CHECK (result IN ('GOOD', 'NOT_GOOD')),
    notes                     TEXT,
    supersedes_test_id        BIGINT REFERENCES instance_tests (id),
    version                   BIGINT      NOT NULL DEFAULT 0,
    created_by                BIGINT      NOT NULL REFERENCES users (id),
    created_at                TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_instance_tests_no_self_supersede CHECK (supersedes_test_id IS NULL OR supersedes_test_id <> id)
);

CREATE INDEX idx_instance_tests_instance ON instance_tests (instance_id, performed_at);
CREATE INDEX idx_instance_tests_supersedes ON instance_tests (supersedes_test_id) WHERE supersedes_test_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- Instance remarks. blocks_shipment is explicit; severity does not auto-drive
-- shipment blocking by job title.
-- ---------------------------------------------------------------------------
CREATE TABLE instance_remarks
(
    id                              BIGSERIAL PRIMARY KEY,
    instance_id                     BIGINT      NOT NULL REFERENCES product_instances (id),
    test_id                         BIGINT REFERENCES instance_tests (id),
    description                     TEXT        NOT NULL,
    severity                        VARCHAR(16) NOT NULL CHECK (severity IN ('CRITICAL', 'MINOR')),
    blocks_shipment                 BOOLEAN     NOT NULL DEFAULT TRUE,
    allowed_by                      BIGINT REFERENCES users (id),
    allowed_at                      TIMESTAMPTZ,
    allowance_document_version_id   BIGINT REFERENCES document_versions (id),
    customer_consent_document_version_id BIGINT REFERENCES document_versions (id),
    corrective_task_id              BIGINT REFERENCES tasks (id),
    status                          VARCHAR(32) NOT NULL DEFAULT 'OPEN'
        CHECK (status IN ('OPEN', 'RESOLVED', 'CLOSED', 'CANCELLED')),
    version                         BIGINT      NOT NULL DEFAULT 0,
    created_by                      BIGINT      NOT NULL REFERENCES users (id),
    created_at                      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_instance_remarks_instance ON instance_remarks (instance_id, status);
CREATE INDEX idx_instance_remarks_blocks_shipment ON instance_remarks (instance_id, blocks_shipment, status)
    WHERE blocks_shipment = TRUE AND status = 'OPEN';

-- ---------------------------------------------------------------------------
-- Remark history.
-- ---------------------------------------------------------------------------
CREATE TABLE instance_remark_history
(
    id         BIGSERIAL PRIMARY KEY,
    remark_id  BIGINT      NOT NULL REFERENCES instance_remarks (id),
    action     VARCHAR(32) NOT NULL CHECK (action IN (
        'CREATED', 'STATUS_CHANGED', 'ALLOWED', 'CORRECTIVE_TASK_LINKED', 'CLOSED'
    )),
    from_status VARCHAR(32),
    to_status   VARCHAR(32),
    reason      TEXT,
    changed_by  BIGINT      NOT NULL REFERENCES users (id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_instance_remark_history_remark ON instance_remark_history (remark_id, created_at);

-- ---------------------------------------------------------------------------
-- Nonconformities (TZ §16). Polymorphic object reference only where necessary.
-- ---------------------------------------------------------------------------
CREATE TABLE nonconformities
(
    id                BIGSERIAL PRIMARY KEY,
    number            VARCHAR(64)   NOT NULL UNIQUE,
    category          VARCHAR(64)   NOT NULL,
    description       TEXT          NOT NULL,
    detected_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    author_id         BIGINT        NOT NULL REFERENCES users (id),
    detected_by_id    BIGINT REFERENCES users (id),
    severity          VARCHAR(16)   NOT NULL CHECK (severity IN ('CRITICAL', 'MAJOR', 'MINOR')),
    object_type       VARCHAR(32)   NOT NULL
        CHECK (object_type IN ('MATERIAL', 'SUPPLY', 'PRODUCTION', 'INSTANCE', 'DOCUMENT', 'PROCESS')),
    object_id         BIGINT        NOT NULL,
    lot_id            BIGINT REFERENCES lots (id),
    serial_id         BIGINT REFERENCES serial_items (id),
    photos            JSONB,
    temporary_measures TEXT,
    responsible_id    BIGINT REFERENCES users (id),
    status            VARCHAR(32)   NOT NULL DEFAULT 'REGISTERED'
        CHECK (status IN (
            'REGISTERED', 'EVALUATION', 'ASSIGNED', 'ROOT_CAUSE_ANALYSIS',
            'CORRECTIVE_ACTION', 'EFFECTIVENESS_CHECK', 'CLOSED'
        )),
    corrective_task_id BIGINT REFERENCES tasks (id),
    version           BIGINT        NOT NULL DEFAULT 0,
    created_by        BIGINT        NOT NULL REFERENCES users (id),
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_nonconformities_number ON nonconformities (number);
CREATE INDEX idx_nonconformities_status ON nonconformities (status);
CREATE INDEX idx_nonconformities_object ON nonconformities (object_type, object_id);
CREATE INDEX idx_nonconformities_responsible ON nonconformities (responsible_id) WHERE responsible_id IS NOT NULL;
CREATE INDEX idx_nonconformities_task ON nonconformities (corrective_task_id) WHERE corrective_task_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- Nonconformity history.
-- ---------------------------------------------------------------------------
CREATE TABLE nonconformity_history
(
    id              BIGSERIAL PRIMARY KEY,
    nonconformity_id BIGINT      NOT NULL REFERENCES nonconformities (id),
    action          VARCHAR(32) NOT NULL CHECK (action IN (
        'CREATED', 'STATUS_CHANGED', 'ASSIGNED', 'CORRECTIVE_ACTION_LINKED', 'CLOSED'
    )),
    from_status     VARCHAR(32),
    to_status       VARCHAR(32),
    reason          TEXT,
    changed_by      BIGINT      NOT NULL REFERENCES users (id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_nonconformity_history_nonconformity ON nonconformity_history (nonconformity_id, created_at);

-- ---------------------------------------------------------------------------
-- Guards.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION prod_forbid_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '%: % is append-only', TG_OP, TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

-- History tables are append-only.
CREATE TRIGGER trg_instance_remark_history_immutable
    BEFORE UPDATE OR DELETE ON instance_remark_history
    FOR EACH ROW EXECUTE FUNCTION prod_forbid_mutation();

CREATE TRIGGER trg_nonconformity_history_immutable
    BEFORE UPDATE OR DELETE ON nonconformity_history
    FOR EACH ROW EXECUTE FUNCTION prod_forbid_mutation();

-- Production order must reference an APPROVED or ACTIVE BOM revision
-- at the moment the revision is assigned/changed.
CREATE OR REPLACE FUNCTION prod_guard_order_bom_revision() RETURNS trigger AS $$
DECLARE
    rev_status VARCHAR(16);
BEGIN
    IF TG_OP = 'UPDATE' AND NEW.bom_revision_id = OLD.bom_revision_id THEN
        RETURN NEW;
    END IF;
    SELECT status INTO rev_status FROM product_revisions WHERE id = NEW.bom_revision_id;
    IF rev_status NOT IN ('APPROVED', 'ACTIVE') THEN
        RAISE EXCEPTION 'production order must reference an APPROVED or ACTIVE BOM revision';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_production_orders_bom_revision_guard
    BEFORE INSERT OR UPDATE ON production_orders
    FOR EACH ROW EXECUTE FUNCTION prod_guard_order_bom_revision();

-- Test results are immutable; corrections are new rows via supersedes_test_id.
CREATE OR REPLACE FUNCTION prod_guard_test_mutation() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF NEW.instance_id <> OLD.instance_id
            OR NEW.order_id IS DISTINCT FROM OLD.order_id
            OR NEW.protocol_document_version_id IS DISTINCT FROM OLD.protocol_document_version_id
            OR NEW.performed_by <> OLD.performed_by
            OR NEW.performed_at <> OLD.performed_at
            OR NEW.result <> OLD.result
            OR NEW.notes IS DISTINCT FROM OLD.notes
            OR NEW.supersedes_test_id IS DISTINCT FROM OLD.supersedes_test_id THEN
            RAISE EXCEPTION 'UPDATE: instance test is immutable; create a new test with supersedes_test_id';
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_instance_tests_immutable
    BEFORE UPDATE OR DELETE ON instance_tests
    FOR EACH ROW EXECUTE FUNCTION prod_guard_test_mutation();
