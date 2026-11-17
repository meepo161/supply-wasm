-- V27: Замыкание приёмки с закупкой — основания, расхождения и поздние связи.
-- Основания: ТЗ §8.4–8.6, §9.3, §22 (связи «заказ 1:N поставки, поставка 1:N
-- приёмки»), §25 инвариант 15, §28.2; SUP-15/SUP-16 и WMS-PROC-001.
--
-- Что добавляется и зачем:
--  * основание приёмки становится обязательным и проверяемым, а не свободной
--    строкой: заказ поставщику, счёт на оплату, накладная/УПД, возврат,
--    перемещение, начальный остаток, прочий документ (ТЗ §8.4 + уточнение
--    пользователя о приёмке по счёту);
--  * одна приёмка может ссылаться на заказ и поставку (поставка 1:N приёмки),
--    одна поставка может приниматься несколькими приёмками (частично), а один
--    документ-основание нельзя провести дважды — уникальный естественный ключ;
--  * расхождения «ожидалось / принято» фиксируются, а не игнорируются:
--    недопоставка остаётся видимой, излишек не попадает в свободный остаток без
--    решения (ТЗ §8.4, приложение J, инвариант «излишек — отдельное решение»);
--  * позднее добавленные заказ/накладная связываются с уже проведённой
--    приёмкой без повторного создания остатка: документ и движения не
--    дублируются, меняется только учётная связь.
--
-- Чего НЕТ: оплата по счёту, претензии, контроль качества (WMS-37) — отдельные
-- блоки; здесь только основание, количество, расхождение и связь с закупкой.

-- ---------------------------------------------------------------------------
-- 1. Основание приёмки: расширенный словарь + обязательность документа
-- ---------------------------------------------------------------------------
ALTER TABLE receipts DROP CONSTRAINT IF EXISTS receipts_source_type_check;

-- Старое имя ограничения могло быть сгенерировано автоматически.
DO $$
DECLARE
    constraint_name text;
BEGIN
    FOR constraint_name IN
        SELECT conname
        FROM pg_constraint
        WHERE conrelid = 'receipts'::regclass
          AND contype = 'c'
          AND pg_get_constraintdef(oid) LIKE '%source_type%'
    LOOP
        EXECUTE format('ALTER TABLE receipts DROP CONSTRAINT %I', constraint_name);
    END LOOP;
END;
$$;

ALTER TABLE receipts
    ADD CONSTRAINT chk_receipts_source_type CHECK (source_type IN (
        'PURCHASE_ORDER', 'INVOICE', 'DELIVERY_NOTE', 'RETURN', 'TRANSFER',
        'INITIAL_STOCK', 'OTHER'
    ));

-- Ссылка на конкретный документ-основание: для заказа и поставки это внешний
-- ключ, для остальных — реквизит документа (номер/дата), поэтому хранится
-- строкой. Уникальность естественного ключа защищает от повторной приёмки
-- по одному и тому же документу.
ALTER TABLE receipts ADD COLUMN source_document_no   VARCHAR(255);
ALTER TABLE receipts ADD COLUMN source_document_date DATE;
ALTER TABLE receipts ADD COLUMN purchase_order_id    BIGINT REFERENCES purchase_orders (id);
ALTER TABLE receipts ADD COLUMN purchase_delivery_id BIGINT REFERENCES purchase_deliveries (id);

CREATE INDEX idx_receipts_purchase_order ON receipts (purchase_order_id)
    WHERE purchase_order_id IS NOT NULL;
CREATE INDEX idx_receipts_purchase_delivery ON receipts (purchase_delivery_id)
    WHERE purchase_delivery_id IS NOT NULL;

-- Один документ-основание проводится один раз. Для приёмок без документа
-- (начальный остаток, прочее) ограничение не действует: NULL не конфликтует.
CREATE UNIQUE INDEX uq_receipts_source_document
    ON receipts (source_type, source_document_no)
    WHERE source_document_no IS NOT NULL AND status <> 'CANCELLED';

-- Приёмка по заказу/поставке невозможна без их указания, а указание ссылки
-- не того типа — ошибка данных, а не «просто ссылка».
ALTER TABLE receipts ADD CONSTRAINT chk_receipts_purchase_links CHECK (
    (purchase_order_id IS NULL AND purchase_delivery_id IS NULL) OR
    (source_type IN ('PURCHASE_ORDER', 'DELIVERY_NOTE', 'INVOICE'))
);

-- ---------------------------------------------------------------------------
-- 2. Расхождения по строкам приёмки
-- ---------------------------------------------------------------------------
-- discrepancy_quantity — положительная разница «принято − ожидалось» (излишек)
-- или «ожидалось − принято» (недопоставка); вид задаётся discrepancy_kind,
-- чтобы одна цифра не трактовалась двояко.
ALTER TABLE receipt_lines ADD COLUMN discrepancy_quantity NUMERIC(19, 4)
    CHECK (discrepancy_quantity IS NULL OR discrepancy_quantity > 0);
ALTER TABLE receipt_lines ADD COLUMN discrepancy_kind VARCHAR(16)
    CHECK (discrepancy_kind IS NULL OR discrepancy_kind IN ('SHORTAGE', 'SURPLUS'));
ALTER TABLE receipt_lines ADD COLUMN discrepancy_reason TEXT;
ALTER TABLE receipt_lines ADD CONSTRAINT chk_receipt_lines_discrepancy CHECK (
    (discrepancy_quantity IS NULL AND discrepancy_kind IS NULL) OR
    (discrepancy_quantity IS NOT NULL AND discrepancy_kind IS NOT NULL)
);

-- Позиция заказа и позиция поставки — чтобы расхождение было видно в разрезе
-- заказа, а не только по номенклатуре.
ALTER TABLE receipt_lines ADD COLUMN purchase_order_line_id BIGINT
    REFERENCES purchase_order_lines (id);
ALTER TABLE receipt_lines ADD COLUMN purchase_delivery_line_id BIGINT
    REFERENCES purchase_delivery_lines (id);

CREATE INDEX idx_receipt_lines_order_line ON receipt_lines (purchase_order_line_id)
    WHERE purchase_order_line_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- 3. Журнал поздних связей
-- ---------------------------------------------------------------------------
-- Приёмка могла быть создана без заказа/поставки, а заказ и накладная
-- появились позже. Связь добавляется отдельным действием, которое нельзя
-- применить дважды и которое не создаёт остаток повторно. Журнал фиксирует,
-- кто и когда связал документы.
CREATE TABLE receipt_links
(
    id                   BIGSERIAL PRIMARY KEY,
    receipt_id           BIGINT      NOT NULL REFERENCES receipts (id),
    purchase_order_id    BIGINT REFERENCES purchase_orders (id),
    purchase_delivery_id BIGINT REFERENCES purchase_deliveries (id),
    -- Номер документа, пришедшего позже (накладная, УПД, счёт). Связь может
    -- состоять только из него: заказ к моменту приёмки ещё не оформлен.
    source_document_no   VARCHAR(255),
    reason               TEXT        NOT NULL CHECK (length(trim(reason)) > 0),
    linked_by            BIGINT      NOT NULL REFERENCES users (id),
    linked_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_receipt_links_target CHECK (
        purchase_order_id IS NOT NULL
            OR purchase_delivery_id IS NOT NULL
            OR source_document_no IS NOT NULL
    )
);

CREATE INDEX idx_receipt_links_receipt ON receipt_links (receipt_id);

CREATE TRIGGER trg_receipt_links_immutable
    BEFORE UPDATE OR DELETE ON receipt_links
    FOR EACH ROW EXECUTE FUNCTION wms_forbid_mutation();

-- ---------------------------------------------------------------------------
-- 4. Проведённая приёмка не переписывается
-- ---------------------------------------------------------------------------
-- Учётная связь и основание меняются только до проведения; после POSTED
-- переписывать документ нельзя — иначе остаток перестанет соответствовать
-- документам (ТЗ §8.4, инварианты 2 и 18).
CREATE OR REPLACE FUNCTION wms_guard_receipt_mutation() RETURNS trigger AS $$
BEGIN
    IF OLD.status = 'POSTED' AND (
        NEW.status <> OLD.status
            OR NEW.warehouse_id <> OLD.warehouse_id
            OR NEW.version < OLD.version
            -- Связь можно заполнить позже (заказ/накладная пришли после приёмки),
            -- но нельзя переписать или обнулить уже зафиксированную.
            OR (OLD.purchase_order_id IS NOT NULL
                AND NEW.purchase_order_id IS DISTINCT FROM OLD.purchase_order_id)
            OR (OLD.purchase_delivery_id IS NOT NULL
                AND NEW.purchase_delivery_id IS DISTINCT FROM OLD.purchase_delivery_id)
            OR (OLD.source_document_no IS NOT NULL
                AND NEW.source_document_no IS DISTINCT FROM OLD.source_document_no)
        ) THEN
        RAISE EXCEPTION 'receipts: posted receipt cannot be rewritten';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- V8 запрещал любое обновление проведённой приёмки, из-за чего поздняя связь
-- была невозможна. Заменяем его точечным стражем: перезапись запрещена,
-- однократное заполнение ссылки и номера документа — разрешено.
DROP TRIGGER IF EXISTS trg_receipts_posted_immutable ON receipts;

CREATE TRIGGER trg_receipts_guard
    BEFORE UPDATE ON receipts
    FOR EACH ROW EXECUTE FUNCTION wms_guard_receipt_mutation();

-- ---------------------------------------------------------------------------
-- 5. Вид «ожидаемые поставки по заказу»
-- ---------------------------------------------------------------------------
-- Ожидаемая поставка = отправленный заказ минус то, что уже принято по
-- связанным приёмкам. Это представление только читает данные: остаток
-- по-прежнему меняется лишь проведением документа.
CREATE VIEW purchase_order_receipt_status AS
SELECT o.id                                              AS order_id,
       o.number                                          AS order_number,
       o.status                                          AS order_status,
       l.id                                              AS order_line_id,
       l.line_no                                         AS line_no,
       l.nomenclature_id                                 AS nomenclature_id,
       l.quantity                                        AS ordered_quantity,
       l.delivered_quantity                              AS delivered_quantity,
       -- Осталось получить от поставщика: заказано минус уже доставленное.
       (l.quantity - l.delivered_quantity)                AS expected_remaining,
       COALESCE(rec.accepted_quantity, 0)                 AS accepted_quantity,
       (l.quantity - COALESCE(rec.accepted_quantity, 0))  AS not_accepted_quantity
FROM purchase_orders o
         JOIN purchase_order_lines l ON l.order_id = o.id
         LEFT JOIN (
    SELECT rl.purchase_order_line_id,
           SUM(rl.received_quantity) AS accepted_quantity
    FROM receipt_lines rl
             JOIN receipts r ON r.id = rl.receipt_id
    WHERE r.status = 'POSTED'
      AND rl.purchase_order_line_id IS NOT NULL
    GROUP BY rl.purchase_order_line_id
) rec ON rec.purchase_order_line_id = l.id;
