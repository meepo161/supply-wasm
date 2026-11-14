package org.example.project.repository

import org.example.project.generated.jooq.tables.Lots.LOTS
import org.example.project.generated.jooq.tables.Nomenclature.NOMENCLATURE
import org.example.project.generated.jooq.tables.SerialItems.SERIAL_ITEMS
import org.example.project.generated.jooq.tables.StockBalance.STOCK_BALANCE
import org.example.project.generated.jooq.tables.StockMovement.STOCK_MOVEMENT
import org.example.project.generated.jooq.tables.StorageLocations.STORAGE_LOCATIONS
import org.example.project.generated.jooq.tables.TransferEvents.TRANSFER_EVENTS
import org.example.project.generated.jooq.tables.Transfers.TRANSFERS
import org.example.project.generated.jooq.tables.Warehouses.WAREHOUSES
import org.example.project.generated.jooq.tables.WmsIdempotencyKeys.WMS_IDEMPOTENCY_KEYS
import org.example.project.generated.jooq.tables.records.LotsRecord
import org.example.project.generated.jooq.tables.records.NomenclatureRecord
import org.example.project.generated.jooq.tables.records.SerialItemsRecord
import org.example.project.generated.jooq.tables.records.StockBalanceRecord
import org.example.project.generated.jooq.tables.records.StorageLocationsRecord
import org.example.project.generated.jooq.tables.records.TransferEventsRecord
import org.example.project.generated.jooq.tables.records.TransfersRecord
import org.example.project.generated.jooq.tables.records.WarehousesRecord
import org.example.project.generated.jooq.tables.records.WmsIdempotencyKeysRecord
import org.jooq.Condition
import org.jooq.DSLContext
import java.math.BigDecimal
import java.time.OffsetDateTime

class WmsTransferRepository(private val dsl: DSLContext) {

    // Ключ учётной позиции остатка; порядок сравнения используется для
    // детерминированной блокировки строк против дедлоков.
    data class BalanceKey(
        val warehouseId: Long,
        val locationId: Long,
        val nomenclatureId: Long,
        val lotId: Long?,
        val serialId: Long?
    ) : Comparable<BalanceKey> {
        override fun compareTo(other: BalanceKey): Int {
            return compareValuesBy(
                this, other,
                { it.warehouseId },
                { it.locationId },
                { it.nomenclatureId },
                { it.lotId ?: 0L },
                { it.serialId ?: 0L }
            )
        }
    }

    // Справочные lookup-ы

    fun findWarehouseById(id: Long): WarehousesRecord? {
        return dsl.selectFrom(WAREHOUSES)
            .where(WAREHOUSES.ID.eq(id))
            .fetchOne()
    }

    fun findLocationById(id: Long): StorageLocationsRecord? {
        return dsl.selectFrom(STORAGE_LOCATIONS)
            .where(STORAGE_LOCATIONS.ID.eq(id))
            .fetchOne()
    }

    fun findNomenclatureById(id: Long): NomenclatureRecord? {
        return dsl.selectFrom(NOMENCLATURE)
            .where(NOMENCLATURE.ID.eq(id))
            .fetchOne()
    }

    fun findLotById(id: Long): LotsRecord? {
        return dsl.selectFrom(LOTS)
            .where(LOTS.ID.eq(id))
            .fetchOne()
    }

    fun findSerialById(id: Long): SerialItemsRecord? {
        return dsl.selectFrom(SERIAL_ITEMS)
            .where(SERIAL_ITEMS.ID.eq(id))
            .fetchOne()
    }

    // Транзитное место хранения склада: одна зона purpose = 'TEMPORARY'
    // с кодом IN-TRANSIT, создаётся при первой отгрузке.
    fun findTransitLocation(warehouseId: Long): StorageLocationsRecord? {
        return dsl.selectFrom(STORAGE_LOCATIONS)
            .where(STORAGE_LOCATIONS.WAREHOUSE_ID.eq(warehouseId))
            .and(STORAGE_LOCATIONS.CODE.eq(TRANSIT_LOCATION_CODE))
            .and(STORAGE_LOCATIONS.PURPOSE.eq("TEMPORARY"))
            .fetchOne()
    }

    fun createTransitLocation(warehouseId: Long, createdBy: Long): StorageLocationsRecord {
        val record = dsl.newRecord(STORAGE_LOCATIONS)
        record.warehouseId = warehouseId
        record.code = TRANSIT_LOCATION_CODE
        record.name = "В пути (межскладские перемещения)"
        record.kind = "ZONE"
        record.purpose = "TEMPORARY"
        record.createdBy = createdBy
        record.store()
        return findLocationById(record.id)!!
    }

    // Документы перемещения

    fun findTransferById(id: Long): TransfersRecord? {
        return dsl.selectFrom(TRANSFERS)
            .where(TRANSFERS.ID.eq(id))
            .fetchOne()
    }

    fun lockTransferById(id: Long): TransfersRecord? {
        return dsl.selectFrom(TRANSFERS)
            .where(TRANSFERS.ID.eq(id))
            .forUpdate()
            .fetchOne()
    }

    fun listTransfers(warehouseId: Long?, nomenclatureId: Long?, status: String?): List<TransfersRecord> {
        val conditions = mutableListOf<Condition>()
        warehouseId?.let {
            conditions += TRANSFERS.FROM_WAREHOUSE_ID.eq(it).or(TRANSFERS.TO_WAREHOUSE_ID.eq(it))
        }
        nomenclatureId?.let { conditions += TRANSFERS.NOMENCLATURE_ID.eq(it) }
        status?.let { conditions += TRANSFERS.STATUS.eq(it) }
        return dsl.selectFrom(TRANSFERS)
            .where(conditions)
            .orderBy(TRANSFERS.ID.desc())
            .fetch()
    }

    fun insertTransfer(
        number: String,
        kind: String,
        fromWarehouseId: Long,
        fromLocationId: Long,
        toWarehouseId: Long,
        toLocationId: Long,
        transitLocationId: Long?,
        nomenclatureId: Long,
        lotId: Long?,
        serialId: Long?,
        quantity: BigDecimal,
        status: String,
        note: String?,
        shippedAt: OffsetDateTime?,
        shippedBy: Long?,
        receivedAt: OffsetDateTime?,
        receivedBy: Long?,
        receivedQuantity: BigDecimal?,
        createdBy: Long
    ): TransfersRecord {
        val record = dsl.newRecord(TRANSFERS)
        record.number = number
        record.transferKind = kind
        record.fromWarehouseId = fromWarehouseId
        record.fromLocationId = fromLocationId
        record.toWarehouseId = toWarehouseId
        record.toLocationId = toLocationId
        record.transitLocationId = transitLocationId
        record.nomenclatureId = nomenclatureId
        record.lotId = lotId
        record.serialId = serialId
        record.quantity = quantity
        record.status = status
        record.note = note
        record.shippedAt = shippedAt
        record.shippedBy = shippedBy
        record.receivedAt = receivedAt
        record.receivedBy = receivedBy
        record.receivedQuantity = receivedQuantity
        record.createdBy = createdBy
        record.store()
        return findTransferById(record.id)!!
    }

    // Все переходы статусов — условные UPDATE по id + version + исходному
    // статусу: повторный переход и конкурентное изменение не проходят.
    fun markShipped(id: Long, expectedVersion: Long, transitLocationId: Long, shippedBy: Long): TransfersRecord? {
        return dsl.update(TRANSFERS)
            .set(TRANSFERS.STATUS, "SHIPPED")
            .set(TRANSFERS.TRANSIT_LOCATION_ID, transitLocationId)
            .set(TRANSFERS.SHIPPED_AT, OffsetDateTime.now())
            .set(TRANSFERS.SHIPPED_BY, shippedBy)
            .set(TRANSFERS.VERSION, TRANSFERS.VERSION.plus(1))
            .where(TRANSFERS.ID.eq(id))
            .and(TRANSFERS.VERSION.eq(expectedVersion))
            .and(TRANSFERS.STATUS.eq("CREATED"))
            .returning()
            .fetchOne()
    }

    fun markInTransit(id: Long, expectedVersion: Long): TransfersRecord? {
        return dsl.update(TRANSFERS)
            .set(TRANSFERS.STATUS, "IN_TRANSIT")
            .set(TRANSFERS.VERSION, TRANSFERS.VERSION.plus(1))
            .where(TRANSFERS.ID.eq(id))
            .and(TRANSFERS.VERSION.eq(expectedVersion))
            .and(TRANSFERS.STATUS.eq("SHIPPED"))
            .returning()
            .fetchOne()
    }

    fun markReceived(
        id: Long,
        expectedVersion: Long,
        newStatus: String,
        receivedQuantity: BigDecimal,
        discrepancyQuantity: BigDecimal?,
        receivedBy: Long
    ): TransfersRecord? {
        return dsl.update(TRANSFERS)
            .set(TRANSFERS.STATUS, newStatus)
            .set(TRANSFERS.RECEIVED_QUANTITY, receivedQuantity)
            .set(TRANSFERS.DISCREPANCY_QUANTITY, discrepancyQuantity)
            .set(TRANSFERS.RECEIVED_AT, OffsetDateTime.now())
            .set(TRANSFERS.RECEIVED_BY, receivedBy)
            .set(TRANSFERS.VERSION, TRANSFERS.VERSION.plus(1))
            .where(TRANSFERS.ID.eq(id))
            .and(TRANSFERS.VERSION.eq(expectedVersion))
            .and(TRANSFERS.STATUS.eq("IN_TRANSIT"))
            .returning()
            .fetchOne()
    }

    fun markCancelled(id: Long, expectedVersion: Long, reason: String, cancelledBy: Long): TransfersRecord? {
        return dsl.update(TRANSFERS)
            .set(TRANSFERS.STATUS, "CANCELLED")
            .set(TRANSFERS.CANCELLED_AT, OffsetDateTime.now())
            .set(TRANSFERS.CANCELLED_BY, cancelledBy)
            .set(TRANSFERS.CANCEL_REASON, reason)
            .set(TRANSFERS.VERSION, TRANSFERS.VERSION.plus(1))
            .where(TRANSFERS.ID.eq(id))
            .and(TRANSFERS.VERSION.eq(expectedVersion))
            .and(TRANSFERS.STATUS.eq("CREATED"))
            .returning()
            .fetchOne()
    }

    fun generateTransferNumber(): String {
        val year = OffsetDateTime.now().year
        val lockKey = year * 10000L + 10L
        dsl.fetch("SELECT pg_advisory_xact_lock(?)", lockKey)
        val prefix = "TRF-$year-"
        val maxNumber = dsl.select(TRANSFERS.NUMBER)
            .from(TRANSFERS)
            .where(TRANSFERS.NUMBER.like("$prefix%"))
            .orderBy(TRANSFERS.NUMBER.desc())
            .limit(1)
            .fetchOne(TRANSFERS.NUMBER)
        val nextIndex = if (maxNumber == null) {
            1
        } else {
            maxNumber.removePrefix(prefix).toIntOrNull()?.plus(1) ?: 1
        }
        return String.format("$prefix%05d", nextIndex)
    }

    // Журнал событий перемещения (append-only)

    fun insertEvent(
        transferId: Long,
        action: String,
        fromStatus: String?,
        toStatus: String?,
        quantity: BigDecimal?,
        discrepancyQuantity: BigDecimal?,
        note: String?,
        changedBy: Long
    ) {
        dsl.insertInto(TRANSFER_EVENTS)
            .set(TRANSFER_EVENTS.TRANSFER_ID, transferId)
            .set(TRANSFER_EVENTS.ACTION, action)
            .set(TRANSFER_EVENTS.FROM_STATUS, fromStatus)
            .set(TRANSFER_EVENTS.TO_STATUS, toStatus)
            .set(TRANSFER_EVENTS.QUANTITY, quantity)
            .set(TRANSFER_EVENTS.DISCREPANCY_QUANTITY, discrepancyQuantity)
            .set(TRANSFER_EVENTS.NOTE, note)
            .set(TRANSFER_EVENTS.CHANGED_BY, changedBy)
            .execute()
    }

    fun listEvents(transferId: Long): List<TransferEventsRecord> {
        return dsl.selectFrom(TRANSFER_EVENTS)
            .where(TRANSFER_EVENTS.TRANSFER_ID.eq(transferId))
            .orderBy(TRANSFER_EVENTS.ID.asc())
            .fetch()
    }

    // Остатки

    fun lockBalance(key: BalanceKey): StockBalanceRecord? {
        return dsl.selectFrom(STOCK_BALANCE)
            .where(STOCK_BALANCE.WAREHOUSE_ID.eq(key.warehouseId))
            .and(STOCK_BALANCE.LOCATION_ID.eq(key.locationId))
            .and(STOCK_BALANCE.NOMENCLATURE_ID.eq(key.nomenclatureId))
            .and(if (key.lotId != null) STOCK_BALANCE.LOT_ID.eq(key.lotId) else STOCK_BALANCE.LOT_ID.isNull)
            .and(if (key.serialId != null) STOCK_BALANCE.SERIAL_ID.eq(key.serialId) else STOCK_BALANCE.SERIAL_ID.isNull)
            .forUpdate()
            .fetchOne()
    }

    // Списание только свободного остатка: physical - reserved >= qty.
    fun decreaseFreePhysical(balanceId: Long, quantity: BigDecimal): Int {
        return dsl.update(STOCK_BALANCE)
            .set(STOCK_BALANCE.PHYSICAL_QTY, STOCK_BALANCE.PHYSICAL_QTY.minus(quantity))
            .set(STOCK_BALANCE.UPDATED_AT, OffsetDateTime.now())
            .set(STOCK_BALANCE.VERSION, STOCK_BALANCE.VERSION.plus(1))
            .where(STOCK_BALANCE.ID.eq(balanceId))
            .and(STOCK_BALANCE.PHYSICAL_QTY.minus(STOCK_BALANCE.RESERVED_QTY).ge(quantity))
            .execute()
    }

    // Атомарный upsert по ключу учётной позиции (idx_stock_balance_key).
    fun increasePhysical(key: BalanceKey, quantity: BigDecimal): StockBalanceRecord {
        return dsl.fetchOne(
            """
            INSERT INTO stock_balance
                (warehouse_id, location_id, nomenclature_id, lot_id, serial_id, physical_qty, reserved_qty)
            VALUES (?, ?, ?, ?, ?, ?, 0)
            ON CONFLICT (warehouse_id, location_id, nomenclature_id, COALESCE(lot_id, 0), COALESCE(serial_id, 0))
            DO UPDATE SET physical_qty = stock_balance.physical_qty + EXCLUDED.physical_qty,
                          updated_at = NOW(),
                          version = stock_balance.version + 1
            RETURNING *
            """.trimIndent(),
            key.warehouseId, key.locationId, key.nomenclatureId, key.lotId, key.serialId, quantity
        )!!.into(STOCK_BALANCE)
    }

    fun insertMovement(
        nomenclatureId: Long,
        lotId: Long?,
        serialId: Long?,
        fromLocationId: Long,
        toLocationId: Long,
        quantity: BigDecimal,
        documentId: Long,
        note: String?,
        createdBy: Long
    ) {
        dsl.insertInto(STOCK_MOVEMENT)
            .set(STOCK_MOVEMENT.MOVEMENT_TYPE, "TRANSFER")
            .set(STOCK_MOVEMENT.NOMENCLATURE_ID, nomenclatureId)
            .set(STOCK_MOVEMENT.LOT_ID, lotId)
            .set(STOCK_MOVEMENT.SERIAL_ID, serialId)
            .set(STOCK_MOVEMENT.FROM_LOCATION_ID, fromLocationId)
            .set(STOCK_MOVEMENT.TO_LOCATION_ID, toLocationId)
            .set(STOCK_MOVEMENT.QUANTITY, quantity)
            .set(STOCK_MOVEMENT.DOCUMENT_TYPE, "TRANSFER")
            .set(STOCK_MOVEMENT.DOCUMENT_ID, documentId)
            .set(STOCK_MOVEMENT.NOTE, note)
            .set(STOCK_MOVEMENT.CREATED_BY, createdBy)
            .execute()
    }

    // Идемпотентность

    fun findIdempotency(userId: Long, operation: String, key: String): WmsIdempotencyKeysRecord? {
        return dsl.selectFrom(WMS_IDEMPOTENCY_KEYS)
            .where(WMS_IDEMPOTENCY_KEYS.USER_ID.eq(userId))
            .and(WMS_IDEMPOTENCY_KEYS.OPERATION.eq(operation))
            .and(WMS_IDEMPOTENCY_KEYS.IDEMPOTENCY_KEY.eq(key))
            .fetchOne()
    }

    fun insertIdempotency(
        userId: Long,
        operation: String,
        key: String,
        requestHash: String,
        transferId: Long,
        responseStatus: Int,
        responseBody: String
    ) {
        dsl.insertInto(WMS_IDEMPOTENCY_KEYS)
            .set(WMS_IDEMPOTENCY_KEYS.USER_ID, userId)
            .set(WMS_IDEMPOTENCY_KEYS.OPERATION, operation)
            .set(WMS_IDEMPOTENCY_KEYS.IDEMPOTENCY_KEY, key)
            .set(WMS_IDEMPOTENCY_KEYS.REQUEST_HASH, requestHash)
            .set(WMS_IDEMPOTENCY_KEYS.TRANSFER_ID, transferId)
            .set(WMS_IDEMPOTENCY_KEYS.RESPONSE_STATUS, responseStatus)
            .set(WMS_IDEMPOTENCY_KEYS.RESPONSE_BODY, responseBody)
            .execute()
    }

    companion object {
        const val TRANSIT_LOCATION_CODE = "IN-TRANSIT"
    }
}
