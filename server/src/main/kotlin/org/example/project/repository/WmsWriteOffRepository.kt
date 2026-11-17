package org.example.project.repository

import org.example.project.generated.jooq.tables.ApprovalRoutes.APPROVAL_ROUTES
import org.example.project.generated.jooq.tables.Nomenclature.NOMENCLATURE
import org.example.project.generated.jooq.tables.StockBalance.STOCK_BALANCE
import org.example.project.generated.jooq.tables.StockMovement.STOCK_MOVEMENT
import org.example.project.generated.jooq.tables.StorageLocations.STORAGE_LOCATIONS
import org.example.project.generated.jooq.tables.Warehouses.WAREHOUSES
import org.example.project.generated.jooq.tables.WriteOffLines.WRITE_OFF_LINES
import org.example.project.generated.jooq.tables.WriteOffOrders.WRITE_OFF_ORDERS
import org.example.project.generated.jooq.tables.WmsSettings.WMS_SETTINGS
import org.example.project.generated.jooq.tables.WmsWriteOffIdempotencyKeys.WMS_WRITE_OFF_IDEMPOTENCY_KEYS
import org.example.project.generated.jooq.tables.WmsWriteOffLimits.WMS_WRITE_OFF_LIMITS
import org.example.project.generated.jooq.tables.records.NomenclatureRecord
import org.example.project.generated.jooq.tables.records.StockBalanceRecord
import org.example.project.generated.jooq.tables.records.StorageLocationsRecord
import org.example.project.generated.jooq.tables.records.WarehousesRecord
import org.example.project.generated.jooq.tables.records.WriteOffLinesRecord
import org.example.project.generated.jooq.tables.records.WriteOffOrdersRecord
import org.example.project.generated.jooq.tables.records.WmsWriteOffIdempotencyKeysRecord
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.math.BigDecimal
import java.time.OffsetDateTime

class WmsWriteOffRepository(private val dsl: DSLContext) {

    // Ключ учётной позиции остатка; порядок сравнения задаёт детерминированный
    // порядок блокировок строк против дедлоков.
    data class BalanceKey(
        val warehouseId: Long,
        val locationId: Long,
        val nomenclatureId: Long,
        val lotId: Long?,
        val serialId: Long?
    ) : Comparable<BalanceKey> {
        override fun compareTo(other: BalanceKey): Int = compareValuesBy(
            this, other,
            { it.warehouseId },
            { it.locationId },
            { it.nomenclatureId },
            { it.lotId ?: 0L },
            { it.serialId ?: 0L }
        )
    }

    fun findWarehouseById(id: Long): WarehousesRecord? =
        dsl.selectFrom(WAREHOUSES).where(WAREHOUSES.ID.eq(id)).fetchOne()

    fun findLocationById(id: Long): StorageLocationsRecord? =
        dsl.selectFrom(STORAGE_LOCATIONS).where(STORAGE_LOCATIONS.ID.eq(id)).fetchOne()

    fun findNomenclatureById(id: Long): NomenclatureRecord? =
        dsl.selectFrom(NOMENCLATURE).where(NOMENCLATURE.ID.eq(id)).fetchOne()

    fun findWriteOffById(id: Long): WriteOffOrdersRecord? =
        dsl.selectFrom(WRITE_OFF_ORDERS).where(WRITE_OFF_ORDERS.ID.eq(id)).fetchOne()

    fun lockWriteOffById(id: Long): WriteOffOrdersRecord? =
        dsl.selectFrom(WRITE_OFF_ORDERS).where(WRITE_OFF_ORDERS.ID.eq(id)).forUpdate().fetchOne()

    fun listWriteOffs(warehouseId: Long?, status: String?): List<WriteOffOrdersRecord> {
        val conditions = mutableListOf<Condition>()
        warehouseId?.let { conditions += WRITE_OFF_ORDERS.WAREHOUSE_ID.eq(it) }
        status?.let { conditions += WRITE_OFF_ORDERS.STATUS.eq(it) }
        return dsl.selectFrom(WRITE_OFF_ORDERS)
            .where(conditions)
            .orderBy(WRITE_OFF_ORDERS.ID.desc())
            .fetch()
    }

    fun insertWriteOff(
        number: String,
        warehouseId: Long,
        reasonType: String,
        reasonNote: String,
        note: String?,
        totalQuantity: BigDecimal,
        createdBy: Long
    ): WriteOffOrdersRecord {
        val record = dsl.newRecord(WRITE_OFF_ORDERS)
        record.number = number
        record.warehouseId = warehouseId
        record.reasonType = reasonType
        record.reasonNote = reasonNote
        record.totalQuantity = totalQuantity
        record.note = note
        record.createdBy = createdBy
        record.store()
        return findWriteOffById(record.id)!!
    }

    fun insertLine(
        writeOffId: Long,
        lineNo: Int,
        nomenclatureId: Long,
        locationId: Long,
        lotId: Long?,
        serialId: Long?,
        quantity: BigDecimal,
        note: String?
    ): WriteOffLinesRecord {
        val record = dsl.newRecord(WRITE_OFF_LINES)
        record.writeOffId = writeOffId
        record.lineNo = lineNo
        record.nomenclatureId = nomenclatureId
        record.locationId = locationId
        record.lotId = lotId
        record.serialId = serialId
        record.quantity = quantity
        record.note = note
        record.store()
        return record
    }

    fun listLines(writeOffId: Long): List<WriteOffLinesRecord> =
        dsl.selectFrom(WRITE_OFF_LINES)
            .where(WRITE_OFF_LINES.WRITE_OFF_ID.eq(writeOffId))
            .orderBy(WRITE_OFF_LINES.LINE_NO.asc())
            .fetch()

    fun findLineById(lineId: Long): WriteOffLinesRecord? =
        dsl.selectFrom(WRITE_OFF_LINES).where(WRITE_OFF_LINES.ID.eq(lineId)).fetchOne()

    fun countLines(writeOffId: Long): Int =
        dsl.fetchCount(WRITE_OFF_LINES, WRITE_OFF_LINES.WRITE_OFF_ID.eq(writeOffId))

    // Переходы статусов — условные UPDATE по id + version + исходному статусу,
    // чтобы конкурентное изменение обнаруживалось, а не перетиралось.

    fun markPendingApproval(
        id: Long,
        expectedVersion: Long,
        overLimit: Boolean,
        limitQuantity: BigDecimal?
    ): WriteOffOrdersRecord? =
        dsl.update(WRITE_OFF_ORDERS)
            .set(WRITE_OFF_ORDERS.STATUS, "PENDING_APPROVAL")
            .set(WRITE_OFF_ORDERS.OVER_LIMIT, overLimit)
            .set(WRITE_OFF_ORDERS.LIMIT_QUANTITY, limitQuantity)
            .set(WRITE_OFF_ORDERS.VERSION, WRITE_OFF_ORDERS.VERSION.plus(1))
            .where(WRITE_OFF_ORDERS.ID.eq(id))
            .and(WRITE_OFF_ORDERS.VERSION.eq(expectedVersion))
            .and(WRITE_OFF_ORDERS.STATUS.eq("DRAFT"))
            .returning()
            .fetchOne()

    fun markApproved(id: Long, expectedVersion: Long, approvedBy: Long): WriteOffOrdersRecord? =
        dsl.update(WRITE_OFF_ORDERS)
            .set(WRITE_OFF_ORDERS.STATUS, "APPROVED")
            .set(WRITE_OFF_ORDERS.APPROVED_AT, OffsetDateTime.now())
            .set(WRITE_OFF_ORDERS.APPROVED_BY, approvedBy)
            .set(WRITE_OFF_ORDERS.VERSION, WRITE_OFF_ORDERS.VERSION.plus(1))
            .where(WRITE_OFF_ORDERS.ID.eq(id))
            .and(WRITE_OFF_ORDERS.VERSION.eq(expectedVersion))
            .and(WRITE_OFF_ORDERS.STATUS.eq("PENDING_APPROVAL"))
            .returning()
            .fetchOne()

    fun markPosted(id: Long, expectedVersion: Long, postedBy: Long): WriteOffOrdersRecord? =
        dsl.update(WRITE_OFF_ORDERS)
            .set(WRITE_OFF_ORDERS.STATUS, "POSTED")
            .set(WRITE_OFF_ORDERS.POSTED_AT, OffsetDateTime.now())
            .set(WRITE_OFF_ORDERS.POSTED_BY, postedBy)
            .set(WRITE_OFF_ORDERS.VERSION, WRITE_OFF_ORDERS.VERSION.plus(1))
            .where(WRITE_OFF_ORDERS.ID.eq(id))
            .and(WRITE_OFF_ORDERS.VERSION.eq(expectedVersion))
            .and(WRITE_OFF_ORDERS.STATUS.`in`("DRAFT", "APPROVED"))
            .returning()
            .fetchOne()

    // Отмена допустима только до проведения; APPROVED отменяется с причиной.
    fun markCancelled(id: Long, expectedVersion: Long, reason: String, cancelledBy: Long): WriteOffOrdersRecord? =
        dsl.update(WRITE_OFF_ORDERS)
            .set(WRITE_OFF_ORDERS.STATUS, "CANCELLED")
            .set(WRITE_OFF_ORDERS.CANCELLED_AT, OffsetDateTime.now())
            .set(WRITE_OFF_ORDERS.CANCELLED_BY, cancelledBy)
            .set(WRITE_OFF_ORDERS.CANCEL_REASON, reason)
            .set(WRITE_OFF_ORDERS.VERSION, WRITE_OFF_ORDERS.VERSION.plus(1))
            .where(WRITE_OFF_ORDERS.ID.eq(id))
            .and(WRITE_OFF_ORDERS.VERSION.eq(expectedVersion))
            .and(WRITE_OFF_ORDERS.STATUS.`in`("DRAFT", "PENDING_APPROVAL", "APPROVED"))
            .returning()
            .fetchOne()

    // Лимит списания без согласования: складской переопределяет значение
    // wms_settings.write_off_default_limit.
    fun findWarehouseLimit(warehouseId: Long): BigDecimal =
        dsl.select(WMS_WRITE_OFF_LIMITS.LIMIT_QUANTITY)
            .from(WMS_WRITE_OFF_LIMITS)
            .where(WMS_WRITE_OFF_LIMITS.WAREHOUSE_ID.eq(warehouseId))
            .fetchOne(WMS_WRITE_OFF_LIMITS.LIMIT_QUANTITY)
            ?: defaultLimit()

    fun defaultLimit(): BigDecimal =
        dsl.select(WMS_SETTINGS.VALUE)
            .from(WMS_SETTINGS)
            .where(WMS_SETTINGS.KEY.eq("write_off_default_limit"))
            .fetchOne(WMS_SETTINGS.VALUE)
            ?.toBigDecimalOrNull()
            ?: BigDecimal.ZERO

    fun upsertWarehouseLimit(warehouseId: Long, limit: BigDecimal, updatedBy: Long) {
        dsl.insertInto(WMS_WRITE_OFF_LIMITS)
            .set(WMS_WRITE_OFF_LIMITS.WAREHOUSE_ID, warehouseId)
            .set(WMS_WRITE_OFF_LIMITS.LIMIT_QUANTITY, limit)
            .set(WMS_WRITE_OFF_LIMITS.UPDATED_BY, updatedBy)
            .onConflict(WMS_WRITE_OFF_LIMITS.WAREHOUSE_ID)
            .doUpdate()
            .set(WMS_WRITE_OFF_LIMITS.LIMIT_QUANTITY, limit)
            .set(WMS_WRITE_OFF_LIMITS.UPDATED_AT, OffsetDateTime.now())
            .set(WMS_WRITE_OFF_LIMITS.UPDATED_BY, updatedBy)
            .execute()
    }

    fun listWarehouseLimits(): List<Pair<Long, BigDecimal>> =
        dsl.select(WMS_WRITE_OFF_LIMITS.WAREHOUSE_ID, WMS_WRITE_OFF_LIMITS.LIMIT_QUANTITY)
            .from(WMS_WRITE_OFF_LIMITS)
            .orderBy(WMS_WRITE_OFF_LIMITS.WAREHOUSE_ID.asc())
            .fetch()
            .map { it.value1() to it.value2() }

    // Остатки: блокировка строки учётной позиции и списание свободного остатка.
    fun lockBalance(key: BalanceKey): StockBalanceRecord? =
        dsl.selectFrom(STOCK_BALANCE)
            .where(STOCK_BALANCE.WAREHOUSE_ID.eq(key.warehouseId))
            .and(STOCK_BALANCE.LOCATION_ID.eq(key.locationId))
            .and(STOCK_BALANCE.NOMENCLATURE_ID.eq(key.nomenclatureId))
            .and(if (key.lotId != null) STOCK_BALANCE.LOT_ID.eq(key.lotId) else STOCK_BALANCE.LOT_ID.isNull)
            .and(if (key.serialId != null) STOCK_BALANCE.SERIAL_ID.eq(key.serialId) else STOCK_BALANCE.SERIAL_ID.isNull)
            .forUpdate()
            .fetchOne()

    // Списание только свободного остатка: reserved_qty не трогается, поэтому
    // списать зарезервированное количество нельзя.
    fun decreaseFreePhysical(balanceId: Long, quantity: BigDecimal): Int =
        dsl.update(STOCK_BALANCE)
            .set(STOCK_BALANCE.PHYSICAL_QTY, STOCK_BALANCE.PHYSICAL_QTY.minus(quantity))
            .set(STOCK_BALANCE.UPDATED_AT, OffsetDateTime.now())
            .set(STOCK_BALANCE.VERSION, STOCK_BALANCE.VERSION.plus(1))
            .where(STOCK_BALANCE.ID.eq(balanceId))
            .and(STOCK_BALANCE.PHYSICAL_QTY.minus(STOCK_BALANCE.RESERVED_QTY).ge(quantity))
            .execute()

    fun insertMovement(
        movementType: String,
        nomenclatureId: Long,
        lotId: Long?,
        serialId: Long?,
        fromLocationId: Long,
        quantity: BigDecimal,
        documentId: Long,
        note: String?,
        createdBy: Long
    ) {
        dsl.insertInto(STOCK_MOVEMENT)
            .set(STOCK_MOVEMENT.MOVEMENT_TYPE, movementType)
            .set(STOCK_MOVEMENT.NOMENCLATURE_ID, nomenclatureId)
            .set(STOCK_MOVEMENT.LOT_ID, lotId)
            .set(STOCK_MOVEMENT.SERIAL_ID, serialId)
            .set(STOCK_MOVEMENT.FROM_LOCATION_ID, fromLocationId)
            .set(STOCK_MOVEMENT.QUANTITY, quantity)
            .set(STOCK_MOVEMENT.DOCUMENT_TYPE, "WRITE_OFF")
            .set(STOCK_MOVEMENT.DOCUMENT_ID, documentId)
            .set(STOCK_MOVEMENT.NOTE, note)
            .set(STOCK_MOVEMENT.CREATED_BY, createdBy)
            .execute()
    }

    // Активный маршрут согласования цели WRITE_OFF (защита от повторной отправки).
    fun hasActiveApprovalRoute(writeOffId: Long): Boolean =
        dsl.fetchExists(
            dsl.selectOne()
                .from(APPROVAL_ROUTES)
                .where(APPROVAL_ROUTES.TARGET_TYPE.eq("WRITE_OFF"))
                .and(APPROVAL_ROUTES.TARGET_ID.eq(writeOffId))
                .and(APPROVAL_ROUTES.STATUS.eq("ACTIVE"))
        )

    fun generateNumber(): String {
        val year = OffsetDateTime.now().year
        dsl.fetch("SELECT pg_advisory_xact_lock(?)", year * 10000L + 13L)
        val prefix = "WOF-$year-"
        val maxNumber = dsl.select(WRITE_OFF_ORDERS.NUMBER)
            .from(WRITE_OFF_ORDERS)
            .where(WRITE_OFF_ORDERS.NUMBER.like("$prefix%"))
            .orderBy(WRITE_OFF_ORDERS.NUMBER.desc())
            .limit(1)
            .fetchOne(WRITE_OFF_ORDERS.NUMBER)
        val nextIndex = maxNumber?.removePrefix(prefix)?.toIntOrNull()?.plus(1) ?: 1
        return String.format("$prefix%05d", nextIndex)
    }

    // Идемпотентность

    fun findIdempotency(userId: Long, operation: String, key: String): WmsWriteOffIdempotencyKeysRecord? =
        dsl.selectFrom(WMS_WRITE_OFF_IDEMPOTENCY_KEYS)
            .where(WMS_WRITE_OFF_IDEMPOTENCY_KEYS.USER_ID.eq(userId))
            .and(WMS_WRITE_OFF_IDEMPOTENCY_KEYS.OPERATION.eq(operation))
            .and(WMS_WRITE_OFF_IDEMPOTENCY_KEYS.IDEMPOTENCY_KEY.eq(key))
            .fetchOne()

    fun insertIdempotency(
        userId: Long,
        operation: String,
        key: String,
        requestHash: String,
        writeOffId: Long,
        responseStatus: Int,
        responseBody: String
    ) {
        dsl.insertInto(WMS_WRITE_OFF_IDEMPOTENCY_KEYS)
            .set(WMS_WRITE_OFF_IDEMPOTENCY_KEYS.USER_ID, userId)
            .set(WMS_WRITE_OFF_IDEMPOTENCY_KEYS.OPERATION, operation)
            .set(WMS_WRITE_OFF_IDEMPOTENCY_KEYS.IDEMPOTENCY_KEY, key)
            .set(WMS_WRITE_OFF_IDEMPOTENCY_KEYS.REQUEST_HASH, requestHash)
            .set(WMS_WRITE_OFF_IDEMPOTENCY_KEYS.WRITE_OFF_ID, writeOffId)
            .set(WMS_WRITE_OFF_IDEMPOTENCY_KEYS.RESPONSE_STATUS, responseStatus)
            .set(WMS_WRITE_OFF_IDEMPOTENCY_KEYS.RESPONSE_BODY, responseBody)
            .execute()
    }

    // Сумма свободного остатка по позиции на складе — для подсказки в UI
    // и для предварительной проверки доступности.
    fun availableQuantity(warehouseId: Long, nomenclatureId: Long): BigDecimal =
        dsl.select(
            DSL.coalesce(
                DSL.sum(STOCK_BALANCE.PHYSICAL_QTY.minus(STOCK_BALANCE.RESERVED_QTY)),
                BigDecimal.ZERO
            )
        )
            .from(STOCK_BALANCE)
            .where(STOCK_BALANCE.WAREHOUSE_ID.eq(warehouseId))
            .and(STOCK_BALANCE.NOMENCLATURE_ID.eq(nomenclatureId))
            .fetchOne(0, BigDecimal::class.java) ?: BigDecimal.ZERO
}
