-- V44: Карточка номенклатуры (NSI-001, D-63).
-- Основания: ТЗ §8.2 (атрибуты номенклатуры), §10.2 (изделие), §10.5 (аналоги).
-- Добавляет недостающие атрибуты §8.2, справочник категорий, характеристики,
-- допустимые склады, связь с моделью изделия, признак согласования аналога и
-- привязку документов DMS к номенклатуре. Остатки, движения и проведённые
-- документы не меняются.

-- Иерархический справочник категорий (D-63). Цикл в иерархии запрещает сервер:
-- проверка требует обхода дерева и в CHECK не выражается.
CREATE TABLE nomenclature_categories
(
    id         BIGSERIAL PRIMARY KEY,
    parent_id  BIGINT REFERENCES nomenclature_categories (id),
    code       VARCHAR(64)  NOT NULL UNIQUE,
    name       VARCHAR(255) NOT NULL,
    archived   BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by BIGINT       NOT NULL REFERENCES users (id),
    version    BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT chk_nomenclature_categories_parent CHECK (parent_id IS NULL OR parent_id <> id)
);

CREATE INDEX idx_nomenclature_categories_parent ON nomenclature_categories (parent_id);

-- Атрибуты §8.2. Габариты в миллиметрах, масса в килограммах: единицы
-- фиксированы, чтобы значения разных позиций были сравнимы.
-- manufacturer_model — модель производителя (не путать с моделью изделия).
-- product_model_id — связь с моделью изделия, одна позиция ↔ одна модель (D-63).
ALTER TABLE nomenclature
    ADD COLUMN category_id        BIGINT REFERENCES nomenclature_categories (id),
    ADD COLUMN article            VARCHAR(128),
    ADD COLUMN manufacturer       VARCHAR(255),
    ADD COLUMN manufacturer_model VARCHAR(255),
    ADD COLUMN weight_kg          NUMERIC(19, 4) CHECK (weight_kg IS NULL OR weight_kg > 0),
    ADD COLUMN length_mm          NUMERIC(19, 2) CHECK (length_mm IS NULL OR length_mm > 0),
    ADD COLUMN width_mm           NUMERIC(19, 2) CHECK (width_mm IS NULL OR width_mm > 0),
    ADD COLUMN height_mm          NUMERIC(19, 2) CHECK (height_mm IS NULL OR height_mm > 0),
    ADD COLUMN storage_conditions TEXT,
    ADD COLUMN product_model_id   BIGINT REFERENCES product_models (id);

CREATE UNIQUE INDEX uq_nomenclature_product_model ON nomenclature (product_model_id)
    WHERE product_model_id IS NOT NULL;
CREATE INDEX idx_nomenclature_category ON nomenclature (category_id) WHERE category_id IS NOT NULL;
CREATE INDEX idx_nomenclature_article ON nomenclature (article) WHERE article IS NOT NULL;

-- Характеристики: имя уникально в пределах позиции, порядок задаёт пользователь.
CREATE TABLE nomenclature_attributes
(
    id              BIGSERIAL PRIMARY KEY,
    nomenclature_id BIGINT        NOT NULL REFERENCES nomenclature (id),
    name            VARCHAR(255)  NOT NULL CHECK (btrim(name) <> ''),
    value           VARCHAR(1000) NOT NULL CHECK (btrim(value) <> ''),
    unit            VARCHAR(32),
    sort_order      INTEGER       NOT NULL DEFAULT 0,
    CONSTRAINT uq_nomenclature_attributes_name UNIQUE (nomenclature_id, name)
);

CREATE INDEX idx_nomenclature_attributes_nomenclature ON nomenclature_attributes (nomenclature_id, sort_order);

-- Допустимые склады: справочная информация, приёмку не блокирует (D-63).
-- Пустой список означает «ограничений не задано».
CREATE TABLE nomenclature_allowed_warehouses
(
    nomenclature_id BIGINT NOT NULL REFERENCES nomenclature (id),
    warehouse_id    BIGINT NOT NULL REFERENCES warehouses (id),
    PRIMARY KEY (nomenclature_id, warehouse_id)
);

CREATE INDEX idx_nomenclature_allowed_warehouses_warehouse ON nomenclature_allowed_warehouses (warehouse_id);

-- §10.5: аналог хранит признак «требует согласования» (D-63).
ALTER TABLE nomenclature_analogs
    ADD COLUMN requires_approval BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX idx_nomenclature_analogs_analog ON nomenclature_analogs (analog_nomenclature_id);

-- Документы DMS привязываются к номенклатуре так же, как к проекту и задаче.
ALTER TABLE document_links
    ADD COLUMN nomenclature_id BIGINT REFERENCES nomenclature (id);

ALTER TABLE document_links DROP CONSTRAINT chk_document_links_one_target;
ALTER TABLE document_links
    ADD CONSTRAINT chk_document_links_one_target CHECK (
        ((project_id IS NOT NULL)::int + (task_id IS NOT NULL)::int +
         (product_model_id IS NOT NULL)::int + (bom_revision_id IS NOT NULL)::int +
         (demand_id IS NOT NULL)::int + (nomenclature_id IS NOT NULL)::int) = 1
    );

DROP INDEX uq_document_links_document_target;
CREATE UNIQUE INDEX uq_document_links_document_target ON document_links (
    document_id,
    COALESCE(project_id, 0),
    COALESCE(task_id, 0),
    COALESCE(product_model_id, 0),
    COALESCE(bom_revision_id, 0),
    COALESCE(demand_id, 0),
    COALESCE(nomenclature_id, 0)
);

CREATE INDEX idx_document_links_nomenclature ON document_links (nomenclature_id) WHERE nomenclature_id IS NOT NULL;
