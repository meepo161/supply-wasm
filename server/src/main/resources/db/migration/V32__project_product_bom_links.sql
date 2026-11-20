-- V32: Явная связь проекта с моделями изделий и применёнными ревизиями BOM.
--
-- Основания: D-32, D-39, D-48 и D-52. Снабжение и WMS уже связаны с проектом
-- через потребности, резервы и выдачи. Эти таблицы добавляют недостающую
-- связь проекта с повторно используемыми моделями изделий и версиями BOM до
-- появления производственного заказа. Они не создают копий BOM, номенклатуры
-- или складского учёта и не меняют существующие первичные документы.

CREATE TABLE project_products
(
    id               BIGSERIAL PRIMARY KEY,
    project_id       BIGINT      NOT NULL REFERENCES projects (id),
    product_model_id BIGINT      NOT NULL REFERENCES product_models (id),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by       BIGINT      NOT NULL REFERENCES users (id),
    CONSTRAINT uq_project_products_model UNIQUE (project_id, product_model_id)
);

CREATE INDEX idx_project_products_project ON project_products (project_id);
CREATE INDEX idx_project_products_model ON project_products (product_model_id);

-- В одном проекте для одной модели могут применяться несколько ревизий.
-- Конкретная ревизия фиксируется ссылкой, а не копированием строк BOM.
CREATE TABLE project_bom_revisions
(
    id                 BIGSERIAL PRIMARY KEY,
    project_product_id BIGINT      NOT NULL REFERENCES project_products (id),
    bom_revision_id    BIGINT      NOT NULL REFERENCES product_revisions (id),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by         BIGINT      NOT NULL REFERENCES users (id),
    CONSTRAINT uq_project_bom_revisions UNIQUE (project_product_id, bom_revision_id)
);

CREATE INDEX idx_project_bom_revisions_project_product ON project_bom_revisions (project_product_id);
CREATE INDEX idx_project_bom_revisions_revision ON project_bom_revisions (bom_revision_id);

-- Связывать можно только ревизию той же модели и только утверждённую либо
-- действующую ревизию. Поздняя замена статуса не стирает историческую ссылку.
CREATE OR REPLACE FUNCTION project_guard_bom_revision_link() RETURNS trigger AS $$
DECLARE
    project_model_id BIGINT;
    revision_model_id BIGINT;
    revision_status VARCHAR(16);
BEGIN
    SELECT product_model_id INTO project_model_id
    FROM project_products
    WHERE id = NEW.project_product_id;

    SELECT product_model_id, status INTO revision_model_id, revision_status
    FROM product_revisions
    WHERE id = NEW.bom_revision_id;

    IF project_model_id IS NULL OR revision_model_id IS NULL THEN
        RAISE EXCEPTION 'project product or BOM revision does not exist';
    END IF;
    IF project_model_id <> revision_model_id THEN
        RAISE EXCEPTION 'BOM revision must belong to the linked product model';
    END IF;
    IF revision_status NOT IN ('APPROVED', 'ACTIVE') THEN
        RAISE EXCEPTION 'project BOM revision must be APPROVED or ACTIVE';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_project_bom_revisions_guard
    BEFORE INSERT OR UPDATE ON project_bom_revisions
    FOR EACH ROW EXECUTE FUNCTION project_guard_bom_revision_link();
