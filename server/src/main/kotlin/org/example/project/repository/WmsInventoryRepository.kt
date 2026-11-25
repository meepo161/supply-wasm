package org.example.project.repository

import org.example.project.generated.jooq.tables.InventoryCounts.INVENTORY_COUNTS
import org.example.project.generated.jooq.tables.InventoryDiscrepancies.INVENTORY_DISCREPANCIES
import org.example.project.generated.jooq.tables.InventoryOrders.INVENTORY_ORDERS
import org.example.project.generated.jooq.tables.InventorySnapshot.INVENTORY_SNAPSHOT
import org.example.project.generated.jooq.tables.StockBalance.STOCK_BALANCE
import org.example.project.generated.jooq.tables.StockMovement.STOCK_MOVEMENT
import org.example.project.generated.jooq.tables.StorageLocations.STORAGE_LOCATIONS
import org.example.project.generated.jooq.tables.Nomenclature.NOMENCLATURE
import org.example.project.generated.jooq.tables.Warehouses.WAREHOUSES
import org.example.project.generated.jooq.tables.WmsInventoryIdempotencyKeys.WMS_INVENTORY_IDEMPOTENCY_KEYS
import org.example.project.generated.jooq.tables.records.InventoryCountsRecord
import org.example.project.generated.jooq.tables.records.InventoryDiscrepanciesRecord
import org.example.project.generated.jooq.tables.records.InventoryOrdersRecord
import org.example.project.generated.jooq.tables.records.InventorySnapshotRecord
import org.example.project.generated.jooq.tables.records.NomenclatureRecord
import org.example.project.generated.jooq.tables.records.StockBalanceRecord
import org.example.project.generated.jooq.tables.records.StorageLocationsRecord
import org.example.project.generated.jooq.tables.records.WarehousesRecord
import org.example.project.generated.jooq.tables.records.WmsInventoryIdempotencyKeysRecord
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.math.BigDecimal
import java.time.OffsetDateTime

class WmsInventoryRepository(private val dsl: DSLContext) {

    fun findWarehouseById(id: Long): WarehousesRecord? =
        dsl.selectFrom(WAREHOUSES).where(WAREHOUSES.ID.eq(id)).fetchOne()

    fun findLocationById(id: Long): StorageLocationsRecord? =
        dsl.selectFrom(STORAGE_LOCATIONS).where(STORAGE_LOCATIONS.ID.eq(id)).fetchOne()

    fun findNomenclatureById(id: Long): NomenclatureRecord? =
        dsl.selectFrom(NOMENCLATURE).where(NOMENCLATURE.ID.eq(id)).fetchOne()

    fun findInventoryById(id: Long): InventoryOrdersRecord? =
        dsl.selectFrom(INVENTORY_ORDERS).where(INVENTORY_ORDERS.ID.eq(id)).fetchOne()

    fun lockInventoryById(id: Long): InventoryOrdersRecord? =
        dsl.selectFrom(INVENTORY_ORDERS).where(INVENTORY_ORDERS.ID.eq(id)).forUpdate().fetchOne()

    fun listInventories(warehouseId: Long?, status: String?): List<InventoryOrdersRecord> {
        val conditions = mutableListOf<Condition>()
        warehouseId?.let { conditions += INVENTORY_ORDERS.WAREHOUSE_ID.eq(it) }
        status?.let { conditions += INVENTORY_ORDERS.STATUS.eq(it) }
        return dsl.selectFrom(INVENTORY_ORDERS)
            .where(conditions)
            .orderBy(INVENTORY_ORDERS.ID.desc())
            .fetch()
    }

    fun insertInventory(
        number: String,
        warehouseId: Long,
        scopeType: String,
        scopeLocationId: Long?,
        scopeNomenclatureId: Long?,
        note: String?,
        createdBy: Long
    ): InventoryOrdersRecord {
        val record = dsl.newRecord(INVENTORY_ORDERS)
        record.number = number
        record.warehouseId = warehouseId
        record.scopeType = scopeType
        record.scopeLocationId = scopeLocationId
        record.scopeNomenclatureId = scopeNomenclatureId
        record.note = note
        record.createdBy = createdBy
        record.store()
        return findInventoryById(record.id)!!
    }

    // Остатки области инвентаризации. Отбор по складу обязателен, зона/ячейка
    // сужают по location_id, номенклатура — по nomenclature_id.
    fun listBalancesInScope(
        warehouseId: Long,
        scopeType: String,
        scopeLocationId: Long?,
        scopeNomenclatureId: Long?
    ): List<StockBalanceRecord> {
        var condition = STOCK_BALANCE.WAREHOUSE_ID.eq(warehouseId)
        if (scopeType == "ZONE" || scopeType == "CELL") {
            condition = condition.and(STOCK_BALANCE.LOCATION_ID.eq(scopeLocationId))
        }
        if (scopeType == "NOMENCLATURE") {
            condition = condition.and(STOCK_BALANCE.NOMENCLATURE_ID.eq(scopeNomenclatureId))
        }
        return dsl.selectFrom(STOCK_BALANCE)
            .where(condition)
            .orderBy(STOCK_BALANCE.LOCATION_ID.asc(), STOCK_BALANCE.NOMENCLATURE_ID.asc())
            .fetch()
    }

    fun insertSnapshot(
        inventoryId: Long,
        locationId: Long,
        nomenclatureId: Long,
        lotId: Long?,
        serialId: Long?,
        bookQuantity: BigDecimal
    ): InventorySnapshotRecord {
        val record = dsl.newRecord(INVENTORY_SNAPSHOT)
        record.inventoryId = inventoryId
        record.locationId = locationId
        record.nomenclatureId = nomenclatureId
        record.lotId = lotId
        record.serialId = serialId
        record.bookQuantity = bookQuantity
        record.store()
        return record
    }

    fun listSnapshot(inventoryId: Long): List<InventorySnapshotRecord> =
        dsl.selectFrom(INVENTORY_SNAPSHOT)
            .where(INVENTORY_SNAPSHOT.INVENTORY_ID.eq(inventoryId))
            .orderBy(INVENTORY_SNAPSHOT.ID.asc())
            .fetch()

    fun findSnapshotById(id: Long): InventorySnapshotRecord? =
        dsl.selectFrom(INVENTORY_SNAPSHOT).where(INVENTORY_SNAPSHOT.ID.eq(id)).fetchOne()

    fun insertCount(
        inventoryId: Long,
        snapshotId: Long,
        countedQuantity: BigDecimal,
        recount: Boolean,
        note: String?,
        countedBy: Long
    ): InventoryCountsRecord {
        val record = dsl.newRecord(INVENTORY_COUNTS)
        record.inventoryId = inventoryId
        record.snapshotId = snapshotId
        record.countedQuantity = countedQuantity
        record.recount = recount
        record.note = note
        record.countedBy = countedBy
        record.store()
        return record
    }

    // Последний подсчёт по каждой строке среза: история пересчётов сохраняется,
    // в ведомость попадает последнее значение.
    fun latestCounts(inventoryId: Long): Map<Long, InventoryCountsRecord> {
        val rows = dsl.selectFrom(INVENTORY_COUNTS)
            .where(INVENTORY_COUNTS.INVENTORY_ID.eq(inventoryId))
            .orderBy(INVENTORY_COUNTS.ID.asc())
            .fetch()
        return rows.associateBy { it.snapshotId }
    }

    fun insertDiscrepancy(
        inventoryId: Long,
        snapshotId: Long,
        locationId: Long,
        nomenclatureId: Long,
        lotId: Long?,
        serialId: Long?,
        bookQuantity: BigDecimal,
        countedQuantity: BigDecimal,
        discrepancy: BigDecimal,
        kind: String?,
        note: String?
    ): InventoryDiscrepanciesRecord {
        val record = dsl.newRecord(INVENTORY_DISCREPANCIES)
        record.inventoryId = inventoryId
        record.snapshotId = snapshotId
        record.locationId = locationId
        record.nomenclatureId = nomenclatureId
        record.lotId = lotId
        record.serialId = serialId
        record.bookQuantity = bookQuantity
        record.countedQuantity = countedQuantity
        record.discrepancy = discrepancy
        record.kind = kind
        record.note = note
        record.store()
        return record
    }

    fun listDiscrepancies(inventoryId: Long): List<InventoryDiscrepanciesRecord> =
        dsl.selectFrom(INVENTORY_DISCREPANCIES)
            .where(INVENTORY_DISCREPANCIES.INVENTORY_ID.eq(inventoryId))
            .orderBy(INVENTORY_DISCREPANCIES.ID.asc())
            .fetch()

    // Последняя строка ведомости по каждой позиции среза: пересчёт добавляет
    // новую запись, а не переписывает прежнюю.
    fun latestDiscrepancies(inventoryId: Long): List<InventoryDiscrepanciesRecord> =
        listDiscrepancies(inventoryId).groupBy { it.snapshotId }.values.map { rows -> rows.maxBy { it.id } }

    fun countDiscrepancies(inventoryId: Long): Int =
        dsl.fetchCount(INVENTORY_DISCREPANCIES, INVENTORY_DISCREPANCIES.INVENTORY_ID.eq(inventoryId))

    // Переходы статусов — условные UPDATE по id + version + ожидаемому статусу.
    fun markBooked(id: Long, expectedVersion: Long, bookedBy: Long): InventoryOrdersRecord? =
        dsl.update(INVENTORY_ORDERS)
            .set(INVENTORY_ORDERS.STATUS, "BOOKED")
            .set(INVENTORY_ORDERS.BOOKED_AT, OffsetDateTime.now())
            .set(INVENTORY_ORDERS.BOOKED_BY, bookedBy)
            .set(INVENTORY_ORDERS.VERSION, INVENTORY_ORDERS.VERSION.plus(1))
            .where(INVENTORY_ORDERS.ID.eq(id))
            .and(INVENTORY_ORDERS.VERSION.eq(expectedVersion))
            .and(INVENTORY_ORDERS.STATUS.eq("DRAFT"))
            .returning()
            .fetchOne()

    fun markCounted(id: Long, expectedVersion: Long, countedBy: Long, status: String): InventoryOrdersRecord? =
        dsl.update(INVENTORY_ORDERS)
            .set(INVENTORY_ORDERS.STATUS, status)
            .set(INVENTORY_ORDERS.COUNTED_AT, OffsetDateTime.now())
            .set(INVENTORY_ORDERS.COUNTED_BY, countedBy)
            .set(INVENTORY_ORDERS.VERSION, INVENTORY_ORDERS.VERSION.plus(1))
            .where(INVENTORY_ORDERS.ID.eq(id))
            .and(INVENTORY_ORDERS.VERSION.eq(expectedVersion))
            .and(INVENTORY_ORDERS.STATUS.`in`("BOOKED", "COUNTING", "DISCREPANCY"))
            .returning()
            .fetchOne()

    fun markApproved(id: Long, expectedVersion: Long, approvedBy: Long): InventoryOrdersRecord? =
        dsl.update(INVENTORY_ORDERS)
            .set(INVENTORY_ORDERS.STATUS, "APPROVED")
            .set(INVENTORY_ORDERS.APPROVED_AT, OffsetDateTime.now())
            .set(INVENTORY_ORDERS.APPROVED_BY, approvedBy)
            .set(INVENTORY_ORDERS.VERSION, INVENTORY_ORDERS.VERSION.plus(1))
            .where(INVENTORY_ORDERS.ID.eq(id))
            .and(INVENTORY_ORDERS.VERSION.eq(expectedVersion))
            .and(INVENTORY_ORDERS.STATUS.`in`("DISCREPANCY", "COUNTING"))
            .returning()
            .fetchOne()

    fun markPosted(id: Long, expectedVersion: Long, postedBy: Long): InventoryOrdersRecord? =
        dsl.update(INVENTORY_ORDERS)
            .set(INVENTORY_ORDERS.STATUS, "POSTED")
            .set(INVENTORY_ORDERS.POSTED_AT, OffsetDateTime.now())
            .set(INVENTORY_ORDERS.POSTED_BY, postedBy)
            .set(INVENTORY_ORDERS.VERSION, INVENTORY_ORDERS.VERSION.plus(1))
            .where(INVENTORY_ORDERS.ID.eq(id))
            .and(INVENTORY_ORDERS.VERSION.eq(expectedVersion))
            .and(INVENTORY_ORDERS.STATUS.`in`("DISCREPANCY", "APPROVED"))
            .returning()
            .fetchOne()

    fun markPaused(id: Long, expectedVersion: Long, pausedBy: Long, fromStatus: String): InventoryOrdersRecord? =
        dsl.update(INVENTORY_ORDERS)
            .set(INVENTORY_ORDERS.STATUS, "PAUSED")
            .set(INVENTORY_ORDERS.PAUSED_AT, OffsetDateTime.now())
            .set(INVENTORY_ORDERS.PAUSED_BY, pausedBy)
            .set(INVENTORY_ORDERS.PAUSED_FROM_STATUS, fromStatus)
            .set(INVENTORY_ORDERS.VERSION, INVENTORY_ORDERS.VERSION.plus(1))
            .where(INVENTORY_ORDERS.ID.eq(id))
            .and(INVENTORY_ORDERS.VERSION.eq(expectedVersion))
            .and(INVENTORY_ORDERS.STATUS.eq(fromStatus))
            .returning()
            .fetchOne()

    fun markResumed(id: Long, expectedVersion: Long, targetStatus: String): InventoryOrdersRecord? =
        dsl.update(INVENTORY_ORDERS)
            .set(INVENTORY_ORDERS.STATUS, targetStatus)
            .set(INVENTORY_ORDERS.PAUSED_AT, null as OffsetDateTime?)
            .set(INVENTORY_ORDERS.PAUSED_BY, null as Long?)
            .set(INVENTORY_ORDERS.PAUSED_FROM_STATUS, null as String?)
            .set(INVENTORY_ORDERS.VERSION, INVENTORY_ORDERS.VERSION.plus(1))
            .where(INVENTORY_ORDERS.ID.eq(id))
            .and(INVENTORY_ORDERS.VERSION.eq(expectedVersion))
            .and(INVENTORY_ORDERS.STATUS.eq("PAUSED"))
            .returning()
            .fetchOne()

    fun markCancelled(id: Long, expectedVersion: Long, reason: String, cancelledBy: Long): InventoryOrdersRecord? =
        dsl.update(INVENTORY_ORDERS)
            .set(INVENTORY_ORDERS.STATUS, "CANCELLED")
            .set(INVENTORY_ORDERS.CANCELLED_AT, OffsetDateTime.now())
            .set(INVENTORY_ORDERS.CANCELLED_BY, cancelledBy)
            .set(INVENTORY_ORDERS.CANCEL_REASON, reason)
            .set(INVENTORY_ORDERS.VERSION, INVENTORY_ORDERS.VERSION.plus(1))
            .where(INVENTORY_ORDERS.ID.eq(id))
            .and(INVENTORY_ORDERS.VERSION.eq(expectedVersion))
            .and(INVENTORY_ORDERS.STATUS.`in`("DRAFT", "BOOKED", "COUNTING", "DISCREPANCY", "PAUSED", "APPROVED"))
            .returning()
            .fetchOne()

    // Корректирующее движение и правка остатка при проведении инвентаризации.
    fun lockBalance(key: BalanceKey): StockBalanceRecord? =
        dsl.selectFrom(STOCK_BALANCE)
            .where(STOCK_BALANCE.WAREHOUSE_ID.eq(key.warehouseId))
            .and(STOCK_BALANCE.LOCATION_ID.eq(key.locationId))
            .and(STOCK_BALANCE.NOMENCLATURE_ID.eq(key.nomenclatureId))
            .and(if (key.lotId != null) STOCK_BALANCE.LOT_ID.eq(key.lotId) else STOCK_BALANCE.LOT_ID.isNull)
            .and(if (key.serialId != null) STOCK_BALANCE.SERIAL_ID.eq(key.serialId) else STOCK_BALANCE.SERIAL_ID.isNull)
            .forUpdate()
            .fetchOne()

    data class BalanceKey(
        val warehouseId: Long,
        val locationId: Long,
        val nomenclatureId: Long,
        val lotId: Long?,
        val serialId: Long?
    ) : Comparable<BalanceKey> {
        override fun compareTo(other: BalanceKey): Int = compareValuesBy(
            this, other,
            { it.locationId },
            { it.nomenclatureId },
            { it.lotId ?: 0L },
            { it.serialId ?: 0L }
        )
    }

    // Излишек: увеличивает physical_qty (upsert по ключу учётной позиции).
    fun increasePhysical(key: BalanceKey, warehouseId: Long, quantity: BigDecimal) {
        dsl.fetchOne(
            """
            INSERT INTO stock_balance
                (warehouse_id, location_id, nomenclature_id, lot_id, serial_id, physical_qty, reserved_qty)
            VALUES (?, ?, ?, ?, ?, ?, 0)
            ON CONFLICT (warehouse_id, location_id, nomenclature_id, COALESCE(lot_id, 0), COALESCE(serial_id, 0))
            DO UPDATE SET physical_qty = stock_balance.physical_qty + EXCLUDED.physical_qty,
                          updated_at = NOW(),
                          version = stock_balance.version + 1
            RETURNING id
            """.trimIndent(),
            warehouseId, key.locationId, key.nomenclatureId, key.lotId, key.serialId, quantity
        )
    }

    // Недостача: уменьшает physical_qty, но не ниже зарезервированного
    // количества (резерв не обнуляется инвентаризацией).
    fun decreasePhysical(balanceId: Long, quantity: BigDecimal): Int =
        dsl.update(STOCK_BALANCE)
            .set(STOCK_BALANCE.PHYSICAL_QTY, STOCK_BALANCE.PHYSICAL_QTY.minus(quantity))
            .set(STOCK_BALANCE.UPDATED_AT, OffsetDateTime.now())
            .set(STOCK_BALANCE.VERSION, STOCK_BALANCE.VERSION.plus(1))
            .where(STOCK_BALANCE.ID.eq(balanceId))
            .and(STOCK_BALANCE.PHYSICAL_QTY.minus(quantity).ge(STOCK_BALANCE.RESERVED_QTY))
            .execute()

    fun insertMovement(
        movementType: String,
        nomenclatureId: Long,
        lotId: Long?,
        serialId: Long?,
        locationId: Long,
        quantity: BigDecimal,
        documentId: Long,
        note: String?,
        createdBy: Long
    ) {
        val insert = dsl.insertInto(STOCK_MOVEMENT)
            .set(STOCK_MOVEMENT.MOVEMENT_TYPE, movementType)
            .set(STOCK_MOVEMENT.NOMENCLATURE_ID, nomenclatureId)
            .set(STOCK_MOVEMENT.LOT_ID, lotId)
            .set(STOCK_MOVEMENT.SERIAL_ID, serialId)
            .set(STOCK_MOVEMENT.QUANTITY, quantity)
            .set(STOCK_MOVEMENT.DOCUMENT_TYPE, "INVENTORY")
            .set(STOCK_MOVEMENT.DOCUMENT_ID, documentId)
            .set(STOCK_MOVEMENT.NOTE, note)
            .set(STOCK_MOVEMENT.CREATED_BY, createdBy)
        if (movementType == "ADJUSTMENT_IN") {
            insert.set(STOCK_MOVEMENT.TO_LOCATION_ID, locationId)
        } else {
            insert.set(STOCK_MOVEMENT.FROM_LOCATION_ID, locationId)
        }
        insert.execute()
    }

    fun generateNumber(): String {
        val year = OffsetDateTime.now().year
        dsl.fetch("SELECT pg_advisory_xact_lock(?)", year * 10000L + 14L)
        val prefix = "INV-$year-"
        val maxNumber = dsl.select(INVENTORY_ORDERS.NUMBER)
            .from(INVENTORY_ORDERS)
            .where(INVENTORY_ORDERS.NUMBER.like("$prefix%"))
            .orderBy(INVENTORY_ORDERS.NUMBER.desc())
            .limit(1)
            .fetchOne(INVENTORY_ORDERS.NUMBER)
        val nextIndex = maxNumber?.removePrefix(prefix)?.toIntOrNull()?.plus(1) ?: 1
        return String.format("$prefix%05d", nextIndex)
    }

    fun activeInventoryExists(warehouseId: Long): Boolean =
        dsl.fetchExists(
            dsl.selectOne().from(INVENTORY_ORDERS)
                .where(INVENTORY_ORDERS.WAREHOUSE_ID.eq(warehouseId))
                .and(INVENTORY_ORDERS.STATUS.`in`("BOOKED", "COUNTING", "DISCREPANCY", "APPROVED"))
        )

    fun findIdempotency(userId: Long, operation: String, key: String): WmsInventoryIdempotencyKeysRecord? =
        dsl.selectFrom(WMS_INVENTORY_IDEMPOTENCY_KEYS)
            .where(WMS_INVENTORY_IDEMPOTENCY_KEYS.USER_ID.eq(userId))
            .and(WMS_INVENTORY_IDEMPOTENCY_KEYS.OPERATION.eq(operation))
            .and(WMS_INVENTORY_IDEMPOTENCY_KEYS.IDEMPOTENCY_KEY.eq(key))
            .fetchOne()

    fun insertIdempotency(
        userId: Long,
        operation: String,
        key: String,
        requestHash: String,
        inventoryId: Long,
        responseStatus: Int,
        responseBody: String
    ) {
        dsl.insertInto(WMS_INVENTORY_IDEMPOTENCY_KEYS)
            .set(WMS_INVENTORY_IDEMPOTENCY_KEYS.USER_ID, userId)
            .set(WMS_INVENTORY_IDEMPOTENCY_KEYS.OPERATION, operation)
            .set(WMS_INVENTORY_IDEMPOTENCY_KEYS.IDEMPOTENCY_KEY, key)
            .set(WMS_INVENTORY_IDEMPOTENCY_KEYS.REQUEST_HASH, requestHash)
            .set(WMS_INVENTORY_IDEMPOTENCY_KEYS.INVENTORY_ID, inventoryId)
            .set(WMS_INVENTORY_IDEMPOTENCY_KEYS.RESPONSE_STATUS, responseStatus)
            .set(WMS_INVENTORY_IDEMPOTENCY_KEYS.RESPONSE_BODY, responseBody)
            .execute()
    }

    fun sumBookQuantity(inventoryId: Long): BigDecimal =
        dsl.select(DSL.coalesce(DSL.sum(INVENTORY_SNAPSHOT.BOOK_QUANTITY), BigDecimal.ZERO))
            .from(INVENTORY_SNAPSHOT)
            .where(INVENTORY_SNAPSHOT.INVENTORY_ID.eq(inventoryId))
            .fetchOne(0, BigDecimal::class.java) ?: BigDecimal.ZERO
}
