-- PRJ-12: Зависимости задач (с проверкой циклов на сервере)
CREATE TABLE task_dependencies
(
    id                  BIGSERIAL PRIMARY KEY,
    predecessor_task_id BIGINT      NOT NULL REFERENCES tasks (id) ON DELETE CASCADE,
    successor_task_id   BIGINT      NOT NULL REFERENCES tasks (id) ON DELETE CASCADE,
    dependency_type     VARCHAR(32) NOT NULL DEFAULT 'FINISH_TO_START',
    created_by          BIGINT      NOT NULL REFERENCES users (id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_task_dependency UNIQUE (predecessor_task_id, successor_task_id),
    CONSTRAINT chk_task_dep_no_self CHECK (predecessor_task_id <> successor_task_id),
    CONSTRAINT chk_task_dep_type CHECK (dependency_type IN ('FINISH_TO_START', 'START_TO_START', 'FINISH_TO_FINISH'))
);

CREATE INDEX idx_task_dependencies_pred ON task_dependencies (predecessor_task_id);
CREATE INDEX idx_task_dependencies_succ ON task_dependencies (successor_task_id);

-- SUP-21: Минимальная партия и кратность закупки
ALTER TABLE nomenclature
    ADD COLUMN IF NOT EXISTS min_order_qty  NUMERIC(19, 4) NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS order_multiple NUMERIC(19, 4) NOT NULL DEFAULT 1;

-- CRM-01, CRM-02: Справочник контрагентов (заказчики, партнёры) и контактные лица
CREATE TABLE counterparties
(
    id         BIGSERIAL PRIMARY KEY,
    name       VARCHAR(255) NOT NULL,
    kind       VARCHAR(32)  NOT NULL DEFAULT 'CUSTOMER',
    inn        VARCHAR(20),
    kpp        VARCHAR(20),
    ogrn       VARCHAR(20),
    email      VARCHAR(255),
    phone      VARCHAR(64),
    address    TEXT,
    notes      TEXT,
    archived   BOOLEAN      NOT NULL DEFAULT FALSE,
    created_by BIGINT       NOT NULL REFERENCES users (id),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    version    BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT chk_counterparty_kind CHECK (kind IN ('CUSTOMER', 'PARTNER', 'SUPPLIER', 'OTHER'))
);

CREATE TABLE counterparty_contacts
(
    id              BIGSERIAL PRIMARY KEY,
    counterparty_id BIGINT       NOT NULL REFERENCES counterparties (id) ON DELETE CASCADE,
    full_name       VARCHAR(255) NOT NULL,
    position        VARCHAR(128),
    email           VARCHAR(255),
    phone           VARCHAR(64),
    is_primary      BOOLEAN      NOT NULL DEFAULT FALSE,
    notes           TEXT
);

CREATE INDEX idx_counterparty_contacts_cp ON counterparty_contacts (counterparty_id);
