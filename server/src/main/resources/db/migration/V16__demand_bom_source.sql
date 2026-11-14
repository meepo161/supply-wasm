-- V16: Дополнительные атрибуты центральной Потребности.
-- Основания: D-44, разделы 9.1–9.4 и J.3 ТЗ.
-- * source_bom_revision_id + bom_release_quantity + bom_revision_version
--   фиксируют источник APPROVED/ACTIVE ревизии BOM и выпуск, чтобы расчёт
--   потребности был воспроизводим (содержимое утверждённой ревизии неизменно,
--   но версия меняется при жизненном цикле).
-- * idempotency_key защищает критичную команду создания потребности от повторов.
ALTER TABLE demands
    ADD COLUMN IF NOT EXISTS source_bom_revision_id BIGINT REFERENCES product_revisions(id),
    ADD COLUMN IF NOT EXISTS bom_release_quantity NUMERIC(19,4),
    ADD COLUMN IF NOT EXISTS bom_revision_version BIGINT,
    ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(64);

CREATE UNIQUE INDEX IF NOT EXISTS idx_demands_idempotency_key
    ON demands (idempotency_key) WHERE idempotency_key IS NOT NULL;
