-- V10: Изделия (модели), независимые ревизии BOM и центральная Потребность.
-- Основания: D-11, D-15, D-16 (потребность для проекта или задела; задел —
-- общий запас, отдельной сущности задела пока нет), D-31–D-34, D-39–D-40,
-- приложения J–L ТЗ. Различаются модель изделия, ревизия состава и физический
-- экземпляр (D-32, K.1): здесь создаются только модель и ревизия; карточка
-- экземпляра и производственный заказ в объём миграции не входят.
-- Инварианты схемы:
--  * ревизия BOM версионируется независимо от модели (D-39, L.4); утверждённая
--    ревизия и её строки неизменяемы (триггеры), изменение создаёт новую
--    ревизию (L.5);
--  * потребность допускает частичное покрытие несколькими поставками, а закупка
--    может покрывать несколько потребностей (приложение J, п. 3): связь хранится
--    в demand_procurements без самой сущности закупки;
--  * истории потребностей и их позиций append-only (триггеры), без перезаписи;
--  * поставщики, производство, бухгалтерия и автоматическое списание материалов
--    при инженерном изменении не создаются (D-34, D-18).

-- Модель изделия: типовое изделие, а не ревизия и не экземпляр (D-32, K.1).
-- development_status — рабочий набор значений; точный перечень и его
-- носители утверждаются отдельно (открытый вопрос по типам изделий).
CREATE TABLE product_models
(
    id                 BIGSERIAL PRIMARY KEY,
    code               VARCHAR(64)  NOT NULL UNIQUE,
    name               VARCHAR(500) NOT NULL,
    description        TEXT,
    development_status VARCHAR(32)  NOT NULL DEFAULT 'DEVELOPMENT'
        CHECK (development_status IN ('DEVELOPMENT', 'PILOT_PRODUCTION', 'SERIAL_PRODUCTION', 'DISCONTINUED')),
    designer_id        BIGINT REFERENCES users (id),
    technologist_id    BIGINT REFERENCES users (id),
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by         BIGINT       NOT NULL REFERENCES users (id),
    version            BIGINT       NOT NULL DEFAULT 0
);

CREATE INDEX idx_product_models_status ON product_models (development_status);
CREATE INDEX idx_product_models_designer ON product_models (designer_id) WHERE designer_id IS NOT NULL;
CREATE INDEX idx_product_models_technologist ON product_models (technologist_id) WHERE technologist_id IS NOT NULL;

-- Ревизия состава изделия: независимая версия BOM модели (D-39, L.4).
-- Номер ревизии уникален в рамках модели. Утверждение фиксирует согласующего
-- и время (BOM_APPROVE — серверное полномочие, D-36/L.1); утверждённая
-- ревизия неизменяема по содержанию (триггер trg_product_revisions_approved_guard),
-- допускаются только переходы жизненного цикла APPROVED -> ACTIVE -> REPLACED.
-- DRAFT/CANCELLED ревизия может удаляться; утверждённая — нет.
CREATE TABLE product_revisions
(
    id               BIGSERIAL PRIMARY KEY,
    product_model_id BIGINT      NOT NULL REFERENCES product_models (id),
    revision_number  VARCHAR(64) NOT NULL,
    status           VARCHAR(16) NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT', 'APPROVED', 'ACTIVE', 'REPLACED', 'CANCELLED')),
    change_reason    TEXT,
    approved_by      BIGINT REFERENCES users (id),
    approved_at      TIMESTAMPTZ,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by       BIGINT       NOT NULL REFERENCES users (id),
    version          BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_product_revisions_model_number UNIQUE (product_model_id, revision_number),
    CONSTRAINT chk_product_revisions_approval CHECK (
        status NOT IN ('APPROVED', 'ACTIVE', 'REPLACED')
            OR (approved_at IS NOT NULL AND approved_by IS NOT NULL)
    )
);

CREATE INDEX idx_product_revisions_model ON product_revisions (product_model_id, status);
CREATE INDEX idx_product_revisions_status ON product_revisions (status);

-- Строки BOM ревизии: иерархия родитель/дочерняя номенклатура.
-- parent_line_id связывает строку с вышестоящей строкой той же ревизии;
-- строка без parent_line_id — верхний уровень. substitute_of_line_id помечает
-- строку как допустимую замену другой строки той же ревизии. Норма расхода
-- (consumption_rate) и потери (loss_pct, процент) не изменяют quantity
-- автоматически — расчёт выполняет сервер. Строки утверждённой ревизии
-- неизменяемы (триггер trg_bom_lines_revision_guard).
CREATE TABLE bom_lines
(
    id                    BIGSERIAL PRIMARY KEY,
    revision_id           BIGINT        NOT NULL REFERENCES product_revisions (id),
    parent_line_id        BIGINT REFERENCES bom_lines (id),
    parent_nomenclature_id BIGINT       NOT NULL REFERENCES nomenclature (id),
    child_nomenclature_id BIGINT        NOT NULL REFERENCES nomenclature (id),
    quantity              NUMERIC(19, 4) NOT NULL CHECK (quantity > 0),
    unit                  VARCHAR(32)   NOT NULL,
    position              VARCHAR(64),
    level                 INTEGER       NOT NULL DEFAULT 1 CHECK (level >= 1),
    mandatory             BOOLEAN       NOT NULL DEFAULT TRUE,
    substitute_of_line_id BIGINT REFERENCES bom_lines (id),
    consumption_rate      NUMERIC(19, 4) CHECK (consumption_rate IS NULL OR consumption_rate >= 0),
    loss_pct              NUMERIC(9, 4) NOT NULL DEFAULT 0
        CHECK (loss_pct >= 0 AND loss_pct <= 100),
    created_at            TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    created_by            BIGINT        NOT NULL REFERENCES users (id),
    CONSTRAINT chk_bom_lines_not_self CHECK (parent_nomenclature_id <> child_nomenclature_id),
    CONSTRAINT chk_bom_lines_hierarchy CHECK (
        parent_line_id IS NULL OR (parent_line_id <> id AND level > 1)
    )
);

-- Позиция уникальна внутри родительского узла ревизии (COALESCE — для корней).
CREATE UNIQUE INDEX idx_bom_lines_position ON bom_lines
    (revision_id, COALESCE(parent_line_id, 0), position) WHERE position IS NOT NULL;
CREATE INDEX idx_bom_lines_revision ON bom_lines (revision_id);
CREATE INDEX idx_bom_lines_parent ON bom_lines (parent_line_id) WHERE parent_line_id IS NOT NULL;
CREATE INDEX idx_bom_lines_child_nomenclature ON bom_lines (child_nomenclature_id);
CREATE INDEX idx_bom_lines_parent_nomenclature ON bom_lines (parent_nomenclature_id);
CREATE INDEX idx_bom_lines_substitute ON bom_lines (substitute_of_line_id) WHERE substitute_of_line_id IS NOT NULL;

-- Центральная Потребность (приложение J, п. 3; D-11, D-15).
-- Стабильный бизнес-номер отделён от внутреннего id. Основания первой очереди:
-- PROJECT (project_id обязателен), STOCKPILE (задел — общий запас предприятия,
-- сущности задела пока нет; направление/обоснование — в stockpile_note),
-- MANUAL (ручное создание инициатором).
CREATE TABLE demands
(
    id            BIGSERIAL PRIMARY KEY,
    number        VARCHAR(64)  NOT NULL UNIQUE,
    source_type   VARCHAR(16)  NOT NULL CHECK (source_type IN ('PROJECT', 'STOCKPILE', 'MANUAL')),
    project_id    BIGINT REFERENCES projects (id),
    stockpile_note TEXT,
    initiator_id  BIGINT       NOT NULL REFERENCES users (id),
    required_date DATE,
    priority      VARCHAR(16)  NOT NULL DEFAULT 'NORMAL',
    status        VARCHAR(32)  NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'IN_PROCUREMENT',
                          'PARTIALLY_COVERED', 'COVERED', 'CANCELLED')),
    approved_by   BIGINT REFERENCES users (id),
    approved_at   TIMESTAMPTZ,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by    BIGINT       NOT NULL REFERENCES users (id),
    version       BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT chk_demands_source CHECK (
        (source_type = 'PROJECT' AND project_id IS NOT NULL) OR
        (source_type IN ('STOCKPILE', 'MANUAL') AND project_id IS NULL)
    ),
    CONSTRAINT chk_demands_approval CHECK (
        status NOT IN ('APPROVED', 'IN_PROCUREMENT', 'PARTIALLY_COVERED', 'COVERED')
            OR (approved_at IS NOT NULL AND approved_by IS NOT NULL)
    )
);

CREATE INDEX idx_demands_number ON demands (number);
CREATE INDEX idx_demands_status ON demands (status);
CREATE INDEX idx_demands_source ON demands (source_type);
CREATE INDEX idx_demands_project ON demands (project_id) WHERE project_id IS NOT NULL;
CREATE INDEX idx_demands_initiator ON demands (initiator_id);
CREATE INDEX idx_demands_required_date ON demands (required_date) WHERE required_date IS NOT NULL;

-- Связь потребности с закупками (приложение J, п. 3): одна потребность
-- покрывается несколькими поставками, одна закупка покрывает несколько
-- потребностей. Сущности закупки в схеме нет — хранится внешняя ссылка
-- (номер/идентификатор закупки), целостность обеспечит сервер после её
-- появления. Поставщики и суммы не создаются.
CREATE TABLE demand_procurements
(
    id              BIGSERIAL PRIMARY KEY,
    demand_id       BIGINT       NOT NULL REFERENCES demands (id),
    procurement_ref VARCHAR(255) NOT NULL,
    note            TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by      BIGINT       NOT NULL REFERENCES users (id),
    CONSTRAINT uq_demand_procurements UNIQUE (demand_id, procurement_ref)
);

CREATE INDEX idx_demand_procurements_ref ON demand_procurements (procurement_ref);

-- Позиции потребности. covered_quantity — обеспеченное количество (резервы,
-- поставки); deficit_quantity — вычисляемый дефицит, отрицательное значение
-- запрещено. source_bom_revision_id — ревизия BOM, из которой позиция
-- получена расчётом, при наличии; ручные позиции не имеют источника.
-- Изменения количества и статуса не перезаписываются — фиксируются
-- в demand_line_history.
CREATE TABLE demand_lines
(
    id                    BIGSERIAL PRIMARY KEY,
    demand_id             BIGINT         NOT NULL REFERENCES demands (id),
    line_no               INTEGER        NOT NULL CHECK (line_no > 0),
    nomenclature_id       BIGINT         NOT NULL REFERENCES nomenclature (id),
    quantity              NUMERIC(19, 4) NOT NULL CHECK (quantity > 0),
    unit                  VARCHAR(32)    NOT NULL,
    covered_quantity      NUMERIC(19, 4) NOT NULL DEFAULT 0 CHECK (covered_quantity >= 0),
    deficit_quantity      NUMERIC(19, 4) GENERATED ALWAYS AS (quantity - covered_quantity) STORED,
    source_bom_revision_id BIGINT REFERENCES product_revisions (id),
    status                VARCHAR(32)    NOT NULL DEFAULT 'OPEN'
        CHECK (status IN ('OPEN', 'PARTIALLY_COVERED', 'COVERED', 'CANCELLED')),
    created_at            TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    created_by            BIGINT         NOT NULL REFERENCES users (id),
    version               BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT uq_demand_lines_demand_line UNIQUE (demand_id, line_no),
    CONSTRAINT chk_demand_lines_covered CHECK (covered_quantity <= quantity)
);

CREATE INDEX idx_demand_lines_demand ON demand_lines (demand_id);
CREATE INDEX idx_demand_lines_nomenclature ON demand_lines (nomenclature_id);
CREATE INDEX idx_demand_lines_status ON demand_lines (status);
CREATE INDEX idx_demand_lines_bom_revision ON demand_lines (source_bom_revision_id)
    WHERE source_bom_revision_id IS NOT NULL;

-- История потребности: append-only (триггер), без перезаписи.
CREATE TABLE demand_history
(
    id         BIGSERIAL PRIMARY KEY,
    demand_id  BIGINT      NOT NULL REFERENCES demands (id),
    action     VARCHAR(32) NOT NULL CHECK (action IN (
        'CREATED', 'EDITED', 'STATUS_CHANGED', 'APPROVED', 'CANCELLED'
    )),
    from_status VARCHAR(32),
    to_status   VARCHAR(32),
    reason      TEXT,
    changed_by  BIGINT      NOT NULL REFERENCES users (id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_demand_history_demand ON demand_history (demand_id, created_at);

-- История позиций потребности: изменения количества и статуса без перезаписи.
CREATE TABLE demand_line_history
(
    id              BIGSERIAL PRIMARY KEY,
    demand_line_id  BIGINT         NOT NULL REFERENCES demand_lines (id),
    action          VARCHAR(32)    NOT NULL CHECK (action IN (
        'CREATED', 'QUANTITY_CHANGED', 'COVERAGE_CHANGED', 'STATUS_CHANGED', 'CANCELLED'
    )),
    quantity_before NUMERIC(19, 4),
    quantity_after  NUMERIC(19, 4),
    from_status     VARCHAR(32),
    to_status       VARCHAR(32),
    reason          TEXT,
    changed_by      BIGINT         NOT NULL REFERENCES users (id),
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_demand_line_history_line ON demand_line_history (demand_line_id, created_at);

-- Append-only истории используют общую функцию wms_forbid_mutation() из V8.
CREATE TRIGGER trg_demand_history_immutable
    BEFORE UPDATE OR DELETE ON demand_history
    FOR EACH ROW EXECUTE FUNCTION wms_forbid_mutation();

CREATE TRIGGER trg_demand_line_history_immutable
    BEFORE UPDATE OR DELETE ON demand_line_history
    FOR EACH ROW EXECUTE FUNCTION wms_forbid_mutation();

-- Неизменяемость утверждённой ревизии: после выхода из DRAFT запрещены
-- изменение содержания (кроме перехода status в рамках жизненного цикла)
-- и удаление не-DRAFT ревизии.
CREATE OR REPLACE FUNCTION bom_guard_approved_revision() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.status <> 'DRAFT' THEN
            RAISE EXCEPTION 'DELETE: only DRAFT product revision can be deleted';
        END IF;
        RETURN OLD;
    END IF;
    IF OLD.status <> 'DRAFT' THEN
        IF NEW.product_model_id <> OLD.product_model_id
            OR NEW.revision_number <> OLD.revision_number
            OR NEW.change_reason IS DISTINCT FROM OLD.change_reason
            OR NEW.approved_by IS DISTINCT FROM OLD.approved_by
            OR NEW.approved_at IS DISTINCT FROM OLD.approved_at
            OR NEW.created_by <> OLD.created_by
            OR NEW.created_at <> OLD.created_at THEN
            RAISE EXCEPTION 'UPDATE: approved product revision content is immutable; create a new revision';
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_product_revisions_approved_guard
    BEFORE UPDATE OR DELETE ON product_revisions
    FOR EACH ROW EXECUTE FUNCTION bom_guard_approved_revision();

-- Строки BOM изменяются только пока ревизия в DRAFT; ссылки иерархии и замены
-- должны указывать на строки той же ревизии.
CREATE OR REPLACE FUNCTION bom_guard_line_mutation() RETURNS trigger AS $$
DECLARE
    parent_status VARCHAR(16);
    ref_revision  BIGINT;
BEGIN
    SELECT status INTO parent_status
    FROM product_revisions
    WHERE id = COALESCE(NEW.revision_id, OLD.revision_id);
    IF parent_status <> 'DRAFT' THEN
        RAISE EXCEPTION '%: bom lines of a non-DRAFT revision cannot be changed or deleted', TG_OP;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    IF NEW.parent_line_id IS NOT NULL THEN
        SELECT revision_id INTO ref_revision FROM bom_lines WHERE id = NEW.parent_line_id;
        IF ref_revision IS NULL OR ref_revision <> NEW.revision_id THEN
            RAISE EXCEPTION 'parent_line_id must reference a line of the same revision';
        END IF;
    END IF;
    IF NEW.substitute_of_line_id IS NOT NULL THEN
        SELECT revision_id INTO ref_revision FROM bom_lines WHERE id = NEW.substitute_of_line_id;
        IF ref_revision IS NULL OR ref_revision <> NEW.revision_id THEN
            RAISE EXCEPTION 'substitute_of_line_id must reference a line of the same revision';
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_bom_lines_revision_guard
    BEFORE INSERT OR UPDATE OR DELETE ON bom_lines
    FOR EACH ROW EXECUTE FUNCTION bom_guard_line_mutation();
