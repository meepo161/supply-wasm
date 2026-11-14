package org.example.project.repository

import org.example.project.generated.jooq.tables.IssueLines.ISSUE_LINES
import org.example.project.generated.jooq.tables.IssueOrders.ISSUE_ORDERS
import org.example.project.generated.jooq.tables.IssuePicks.ISSUE_PICKS
import org.example.project.generated.jooq.tables.Lots.LOTS
import org.example.project.generated.jooq.tables.Nomenclature.NOMENCLATURE
import org.example.project.generated.jooq.tables.Projects.PROJECTS
import org.example.project.generated.jooq.tables.ReservationHistory.RESERVATION_HISTORY
import org.example.project.generated.jooq.tables.Reservations.RESERVATIONS
import org.example.project.generated.jooq.tables.ReturnLines.RETURN_LINES
import org.example.project.generated.jooq.tables.ReturnOrders.RETURN_ORDERS
import org.example.project.generated.jooq.tables.SerialItems.SERIAL_ITEMS
import org.example.project.generated.jooq.tables.StockBalance.STOCK_BALANCE
import org.example.project.generated.jooq.tables.StockMovement.STOCK_MOVEMENT
import org.example.project.generated.jooq.tables.StorageLocations.STORAGE_LOCATIONS
import org.example.project.generated.jooq.tables.Tasks.TASKS
import org.example.project.generated.jooq.tables.Users.USERS
import org.example.project.generated.jooq.tables.Warehouses.WAREHOUSES
import org.example.project.generated.jooq.tables.WmsIssueIdempotencyKeys.WMS_ISSUE_IDEMPOTENCY_KEYS
import org.example.project.generated.jooq.tables.records.IssueLinesRecord
import org.example.project.generated.jooq.tables.records.IssueOrdersRecord
import org.example.project.generated.jooq.tables.records.IssuePicksRecord
import org.example.project.generated.jooq.tables.records.LotsRecord
import org.example.project.generated.jooq.tables.records.NomenclatureRecord
import org.example.project.generated.jooq.tables.records.ReservationsRecord
import org.example.project.generated.jooq.tables.records.ReturnLinesRecord
import org.example.project.generated.jooq.tables.records.ReturnOrdersRecord
import org.example.project.generated.jooq.tables.records.SerialItemsRecord
import org.example.project.generated.jooq.tables.records.StockBalanceRecord
import org.example.project.generated.jooq.tables.records.StorageLocationsRecord
import org.example.project.generated.jooq.tables.records.WarehousesRecord
import org.example.project.generated.jooq.tables.records.WmsIssueIdempotencyKeysRecord
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.math.BigDecimal
import java.time.OffsetDateTime

class WmsIssueRepository(private val dsl: DSLContext) {

    // Ключ учётной позиции; порядок сравнения задаёт детерминированный
    // порядок блокировок против дедлоков.
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
        return dsl.selectFrom(WAREHOUSES).where(WAREHOUSES.ID.eq(id)).fetchOne()
    }

    fun findLocationById(id: Long): StorageLocationsRecord? {
        return dsl.selectFrom(STORAGE_LOCATIONS).where(STORAGE_LOCATIONS.ID.eq(id)).fetchOne()
    }

    fun findNomenclatureById(id: Long): NomenclatureRecord? {
        return dsl.selectFrom(NOMENCLATURE).where(NOMENCLATURE.ID.eq(id)).fetchOne()
    }

    fun findLotById(id: Long): LotsRecord? {
        return dsl.selectFrom(LOTS).where(LOTS.ID.eq(id)).fetchOne()
    }

    fun findSerialById(id: Long): SerialItemsRecord? {
        return dsl.selectFrom(SERIAL_ITEMS).where(SERIAL_ITEMS.ID.eq(id)).fetchOne()
    }

    fun findReservationById(id: Long): ReservationsRecord? {
        return dsl.selectFrom(RESERVATIONS).where(RESERVATIONS.ID.eq(id)).fetchOne()
    }

    fun lockReservationById(id: Long): ReservationsRecord? {
        return dsl.selectFrom(RESERVATIONS).where(RESERVATIONS.ID.eq(id)).forUpdate().fetchOne()
    }

    fun projectExists(id: Long): Boolean {
        return dsl.fetchExists(dsl.selectOne().from(PROJECTS).where(PROJECTS.ID.eq(id)))
    }

    fun taskExists(id: Long): Boolean {
        return dsl.fetchExists(dsl.selectOne().from(TASKS).where(TASKS.ID.eq(id)))
    }

    fun userExists(id: Long): Boolean {
        return dsl.fetchExists(dsl.selectOne().from(USERS).where(USERS.ID.eq(id)))
    }

    // Документы выдачи

    fun findIssueById(id: Long): IssueOrdersRecord? {
        return dsl.selectFrom(ISSUE_ORDERS).where(ISSUE_ORDERS.ID.eq(id)).fetchOne()
    }

    fun lockIssueById(id: Long): IssueOrdersRecord? {
        return dsl.selectFrom(ISSUE_ORDERS).where(ISSUE_ORDERS.ID.eq(id)).forUpdate().fetchOne()
    }

    fun lockIssueLineById(id: Long): IssueLinesRecord? {
        return dsl.selectFrom(ISSUE_LINES).where(ISSUE_LINES.ID.eq(id)).forUpdate().fetchOne()
    }

    fun listIssues(warehouseId: Long?, status: String?): List<IssueOrdersRecord> {
        val conditions = mutableListOf<Condition>()
        warehouseId?.let { conditions += ISSUE_ORDERS.WAREHOUSE_ID.eq(it) }
        status?.let { conditions += ISSUE_ORDERS.STATUS.eq(it) }
        return dsl.selectFrom(ISSUE_ORDERS)
            .where(conditions)
            .orderBy(ISSUE_ORDERS.ID.desc())
            .fetch()
    }

    fun insertIssue(
        number: String,
        warehouseId: Long,
        reasonType: String,
        projectId: Long?,
        taskId: Long?,
        reasonNote: String?,
        recipientId: Long,
        note: String?,
        createdBy: Long
    ): IssueOrdersRecord {
        val record = dsl.newRecord(ISSUE_ORDERS)
        record.number = number
        record.warehouseId = warehouseId
        record.reasonType = reasonType
        record.projectId = projectId
        record.taskId = taskId
        record.reasonNote = reasonNote
        record.recipientId = recipientId
        record.note = note
        record.createdBy = createdBy
        record.store()
        return findIssueById(record.id)!!
    }

    fun insertIssueLine(
        issueOrderId: Long,
        lineNo: Int,
        nomenclatureId: Long,
        requestedQuantity: BigDecimal,
        reservationId: Long?,
        note: String?
    ): IssueLinesRecord {
        val record = dsl.newRecord(ISSUE_LINES)
        record.issueOrderId = issueOrderId
        record.lineNo = lineNo
        record.nomenclatureId = nomenclatureId
        record.requestedQuantity = requestedQuantity
        record.reservationId = reservationId
        record.note = note
        record.store()
        return record
    }

    fun listIssueLines(issueOrderId: Long): List<IssueLinesRecord> {
        return dsl.selectFrom(ISSUE_LINES)
            .where(ISSUE_LINES.ISSUE_ORDER_ID.eq(issueOrderId))
            .orderBy(ISSUE_LINES.LINE_NO.asc())
            .fetch()
    }

    fun insertPick(
        issueLineId: Long,
        locationId: Long,
        lotId: Long?,
        serialId: Long?,
        quantity: BigDecimal,
        pickedBy: Long
    ) {
        dsl.insertInto(ISSUE_PICKS)
            .set(ISSUE_PICKS.ISSUE_LINE_ID, issueLineId)
            .set(ISSUE_PICKS.LOCATION_ID, locationId)
            .set(ISSUE_PICKS.LOT_ID, lotId)
            .set(ISSUE_PICKS.SERIAL_ID, serialId)
            .set(ISSUE_PICKS.QUANTITY, quantity)
            .set(ISSUE_PICKS.PICKED_BY, pickedBy)
            .execute()
    }

    fun listPicks(issueLineIds: List<Long>): List<IssuePicksRecord> {
        if (issueLineIds.isEmpty()) return emptyList()
        return dsl.selectFrom(ISSUE_PICKS)
            .where(ISSUE_PICKS.ISSUE_LINE_ID.`in`(issueLineIds))
            .orderBy(ISSUE_PICKS.ID.asc())
            .fetch()
    }

    fun sumPicksForLine(issueLineId: Long): BigDecimal {
        return dsl.select(DSL.coalesce(DSL.sum(ISSUE_PICKS.QUANTITY), BigDecimal.ZERO))
            .from(ISSUE_PICKS)
            .where(ISSUE_PICKS.ISSUE_LINE_ID.eq(issueLineId))
            .fetchOne(0, BigDecimal::class.java) ?: BigDecimal.ZERO
    }

    // Переходы статусов — условные UPDATE по id + version + исходному статусу.
    fun markIssued(id: Long, expectedVersion: Long, issuedBy: Long): IssueOrdersRecord? {
        return dsl.update(ISSUE_ORDERS)
            .set(ISSUE_ORDERS.STATUS, "ISSUED")
            .set(ISSUE_ORDERS.ISSUED_AT, OffsetDateTime.now())
            .set(ISSUE_ORDERS.ISSUED_BY, issuedBy)
            .set(ISSUE_ORDERS.VERSION, ISSUE_ORDERS.VERSION.plus(1))
            .where(ISSUE_ORDERS.ID.eq(id))
            .and(ISSUE_ORDERS.VERSION.eq(expectedVersion))
            .and(ISSUE_ORDERS.STATUS.eq("DRAFT"))
            .returning()
            .fetchOne()
    }

    fun markConfirmed(id: Long, expectedVersion: Long, newStatus: String, confirmedBy: Long): IssueOrdersRecord? {
        return dsl.update(ISSUE_ORDERS)
            .set(ISSUE_ORDERS.STATUS, newStatus)
            .set(ISSUE_ORDERS.CONFIRMED_AT, OffsetDateTime.now())
            .set(ISSUE_ORDERS.CONFIRMED_BY, confirmedBy)
            .set(ISSUE_ORDERS.VERSION, ISSUE_ORDERS.VERSION.plus(1))
            .where(ISSUE_ORDERS.ID.eq(id))
            .and(ISSUE_ORDERS.VERSION.eq(expectedVersion))
            .and(ISSUE_ORDERS.STATUS.eq("ISSUED"))
            .returning()
            .fetchOne()
    }

    fun markRefused(id: Long, expectedVersion: Long, reason: String): IssueOrdersRecord? {
        return dsl.update(ISSUE_ORDERS)
            .set(ISSUE_ORDERS.STATUS, "REFUSED")
            .set(ISSUE_ORDERS.REFUSAL_REASON, reason)
            .set(ISSUE_ORDERS.VERSION, ISSUE_ORDERS.VERSION.plus(1))
            .where(ISSUE_ORDERS.ID.eq(id))
            .and(ISSUE_ORDERS.VERSION.eq(expectedVersion))
            .and(ISSUE_ORDERS.STATUS.`in`("DRAFT", "ISSUED"))
            .returning()
            .fetchOne()
    }

    fun markCancelled(id: Long, expectedVersion: Long): IssueOrdersRecord? {
        return dsl.update(ISSUE_ORDERS)
            .set(ISSUE_ORDERS.STATUS, "CANCELLED")
            .set(ISSUE_ORDERS.VERSION, ISSUE_ORDERS.VERSION.plus(1))
            .where(ISSUE_ORDERS.ID.eq(id))
            .and(ISSUE_ORDERS.VERSION.eq(expectedVersion))
            .and(ISSUE_ORDERS.STATUS.eq("DRAFT"))
            .returning()
            .fetchOne()
    }

    // Фактическое количество строки при подтверждении (родитель в ISSUED).
    fun setLineIssuedQuantity(lineId: Long, issuedQuantity: BigDecimal) {
        dsl.update(ISSUE_LINES)
            .set(ISSUE_LINES.ISSUED_QUANTITY, issuedQuantity)
            .where(ISSUE_LINES.ID.eq(lineId))
            .execute()
    }

    fun generateIssueNumber(): String {
        return generateNumber("ISS", 11L, ISSUE_ORDERS.NUMBER, ISSUE_ORDERS)
    }

    // Резерв: issued_quantity растёт не выше quantity, ACTIVE -> FULFILLED.
    // Условный UPDATE не даёт использовать резерв дважды.
    fun addIssuedToReservation(reservationId: Long, quantity: BigDecimal): ReservationsRecord? {
        val newIssued = RESERVATIONS.ISSUED_QUANTITY.plus(quantity)
        return dsl.update(RESERVATIONS)
            .set(RESERVATIONS.ISSUED_QUANTITY, newIssued)
            .set(
                RESERVATIONS.STATUS,
                DSL.`when`(newIssued.ge(RESERVATIONS.QUANTITY), "FULFILLED").otherwise(RESERVATIONS.STATUS)
            )
            .set(RESERVATIONS.VERSION, RESERVATIONS.VERSION.plus(1))
            .where(RESERVATIONS.ID.eq(reservationId))
            .and(RESERVATIONS.STATUS.eq("ACTIVE"))
            .and(newIssued.le(RESERVATIONS.QUANTITY))
            .returning()
            .fetchOne()
    }

    fun insertReservationHistory(
        reservationId: Long,
        action: String,
        fromStatus: String?,
        toStatus: String?,
        quantityBefore: BigDecimal?,
        quantityAfter: BigDecimal?,
        reason: String?,
        changedBy: Long
    ) {
        dsl.insertInto(RESERVATION_HISTORY)
            .set(RESERVATION_HISTORY.RESERVATION_ID, reservationId)
            .set(RESERVATION_HISTORY.ACTION, action)
            .set(RESERVATION_HISTORY.FROM_STATUS, fromStatus)
            .set(RESERVATION_HISTORY.TO_STATUS, toStatus)
            .set(RESERVATION_HISTORY.QUANTITY_BEFORE, quantityBefore)
            .set(RESERVATION_HISTORY.QUANTITY_AFTER, quantityAfter)
            .set(RESERVATION_HISTORY.REASON, reason)
            .set(RESERVATION_HISTORY.CHANGED_BY, changedBy)
            .execute()
    }

    // Возвраты

    fun findReturnById(id: Long): ReturnOrdersRecord? {
        return dsl.selectFrom(RETURN_ORDERS).where(RETURN_ORDERS.ID.eq(id)).fetchOne()
    }

    fun lockReturnById(id: Long): ReturnOrdersRecord? {
        return dsl.selectFrom(RETURN_ORDERS).where(RETURN_ORDERS.ID.eq(id)).forUpdate().fetchOne()
    }

    fun findReturnLineById(id: Long): ReturnLinesRecord? {
        return dsl.selectFrom(RETURN_LINES).where(RETURN_LINES.ID.eq(id)).fetchOne()
    }

    fun listReturns(warehouseId: Long?, status: String?): List<ReturnOrdersRecord> {
        val conditions = mutableListOf<Condition>()
        warehouseId?.let { conditions += RETURN_ORDERS.WAREHOUSE_ID.eq(it) }
        status?.let { conditions += RETURN_ORDERS.STATUS.eq(it) }
        return dsl.selectFrom(RETURN_ORDERS)
            .where(conditions)
            .orderBy(RETURN_ORDERS.ID.desc())
            .fetch()
    }

    fun insertReturn(
        number: String,
        issueOrderId: Long,
        warehouseId: Long,
        quarantineLocationId: Long,
        returnedBy: Long,
        reason: String,
        createdBy: Long
    ): ReturnOrdersRecord {
        val record = dsl.newRecord(RETURN_ORDERS)
        record.number = number
        record.issueOrderId = issueOrderId
        record.warehouseId = warehouseId
        record.quarantineLocationId = quarantineLocationId
        record.returnedBy = returnedBy
        record.reason = reason
        record.createdBy = createdBy
        record.store()
        return findReturnById(record.id)!!
    }

    fun insertReturnLine(
        returnOrderId: Long,
        issueLineId: Long,
        quantity: BigDecimal,
        lotId: Long?,
        serialId: Long?
    ) {
        dsl.insertInto(RETURN_LINES)
            .set(RETURN_LINES.RETURN_ORDER_ID, returnOrderId)
            .set(RETURN_LINES.ISSUE_LINE_ID, issueLineId)
            .set(RETURN_LINES.QUANTITY, quantity)
            .set(RETURN_LINES.LOT_ID, lotId)
            .set(RETURN_LINES.SERIAL_ID, serialId)
            .execute()
    }

    fun listReturnLines(returnOrderId: Long): List<ReturnLinesRecord> {
        return dsl.selectFrom(RETURN_LINES)
            .where(RETURN_LINES.RETURN_ORDER_ID.eq(returnOrderId))
            .orderBy(RETURN_LINES.ID.asc())
            .fetch()
    }

    // Сумма уже оформленных возвратов по строке выдачи (все статусы —
    // возвраты не отменяются, иначе два черновика превысили бы выдачу).
    fun sumReturnedForIssueLine(issueLineId: Long, excludeReturnOrderId: Long? = null): BigDecimal {
        return dsl.select(DSL.coalesce(DSL.sum(RETURN_LINES.QUANTITY), BigDecimal.ZERO))
            .from(RETURN_LINES)
            .where(RETURN_LINES.ISSUE_LINE_ID.eq(issueLineId))
            .and(
                if (excludeReturnOrderId != null) {
                    RETURN_LINES.RETURN_ORDER_ID.ne(excludeReturnOrderId)
                } else {
                    DSL.noCondition()
                }
            )
            .fetchOne(0, BigDecimal::class.java) ?: BigDecimal.ZERO
    }

    fun findIssueLineById(id: Long): IssueLinesRecord? {
        return dsl.selectFrom(ISSUE_LINES).where(ISSUE_LINES.ID.eq(id)).fetchOne()
    }

    fun countPendingReturnLines(returnOrderId: Long): Int {
        return dsl.fetchCount(
            dsl.selectOne().from(RETURN_LINES)
                .where(RETURN_LINES.RETURN_ORDER_ID.eq(returnOrderId))
                .and(RETURN_LINES.INSPECTION.eq("PENDING"))
        )
    }

    fun markReturnReceived(id: Long, expectedVersion: Long, receivedBy: Long): ReturnOrdersRecord? {
        return dsl.update(RETURN_ORDERS)
            .set(RETURN_ORDERS.STATUS, "RECEIVED")
            .set(RETURN_ORDERS.RECEIVED_AT, OffsetDateTime.now())
            .set(RETURN_ORDERS.RECEIVED_BY, receivedBy)
            .set(RETURN_ORDERS.VERSION, RETURN_ORDERS.VERSION.plus(1))
            .where(RETURN_ORDERS.ID.eq(id))
            .and(RETURN_ORDERS.VERSION.eq(expectedVersion))
            .and(RETURN_ORDERS.STATUS.eq("DRAFT"))
            .returning()
            .fetchOne()
    }

    // Решение о пригодности: только PENDING -> GOOD/DEFECT при родителе RECEIVED
    // (следит и триггер trg_return_lines_mutation_guard).
    fun inspectReturnLine(
        lineId: Long,
        decision: String,
        targetLocationId: Long?,
        inspectionNote: String?,
        inspectedBy: Long
    ): ReturnLinesRecord? {
        return dsl.update(RETURN_LINES)
            .set(RETURN_LINES.INSPECTION, decision)
            .set(RETURN_LINES.INSPECTED_AT, OffsetDateTime.now())
            .set(RETURN_LINES.INSPECTED_BY, inspectedBy)
            .set(RETURN_LINES.TARGET_LOCATION_ID, targetLocationId)
            .set(RETURN_LINES.INSPECTION_NOTE, inspectionNote)
            .where(RETURN_LINES.ID.eq(lineId))
            .and(RETURN_LINES.INSPECTION.eq("PENDING"))
            .returning()
            .fetchOne()
    }

    // Закрытие возврата, когда все строки проверены.
    fun markReturnInspected(id: Long, expectedVersion: Long): ReturnOrdersRecord? {
        return dsl.update(RETURN_ORDERS)
            .set(RETURN_ORDERS.STATUS, "INSPECTED")
            .set(RETURN_ORDERS.VERSION, RETURN_ORDERS.VERSION.plus(1))
            .where(RETURN_ORDERS.ID.eq(id))
            .and(RETURN_ORDERS.VERSION.eq(expectedVersion))
            .and(RETURN_ORDERS.STATUS.eq("RECEIVED"))
            .returning()
            .fetchOne()
    }

    fun generateReturnNumber(): String {
        return generateNumber("RET", 12L, RETURN_ORDERS.NUMBER, RETURN_ORDERS)
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

    // Списание только свободного остатка (строки без резерва).
    fun decreaseFreePhysical(balanceId: Long, quantity: BigDecimal): Int {
        return dsl.update(STOCK_BALANCE)
            .set(STOCK_BALANCE.PHYSICAL_QTY, STOCK_BALANCE.PHYSICAL_QTY.minus(quantity))
            .set(STOCK_BALANCE.UPDATED_AT, OffsetDateTime.now())
            .set(STOCK_BALANCE.VERSION, STOCK_BALANCE.VERSION.plus(1))
            .where(STOCK_BALANCE.ID.eq(balanceId))
            .and(STOCK_BALANCE.PHYSICAL_QTY.minus(STOCK_BALANCE.RESERVED_QTY).ge(quantity))
            .execute()
    }

    // Выдача из резерва: physical и reserved уменьшаются согласованно,
    // reserved не может уйти ниже нуля.
    fun decreaseReservedPhysical(balanceId: Long, quantity: BigDecimal): Int {
        return dsl.update(STOCK_BALANCE)
            .set(STOCK_BALANCE.PHYSICAL_QTY, STOCK_BALANCE.PHYSICAL_QTY.minus(quantity))
            .set(STOCK_BALANCE.RESERVED_QTY, STOCK_BALANCE.RESERVED_QTY.minus(quantity))
            .set(STOCK_BALANCE.UPDATED_AT, OffsetDateTime.now())
            .set(STOCK_BALANCE.VERSION, STOCK_BALANCE.VERSION.plus(1))
            .where(STOCK_BALANCE.ID.eq(balanceId))
            .and(STOCK_BALANCE.RESERVED_QTY.ge(quantity))
            .and(STOCK_BALANCE.PHYSICAL_QTY.ge(quantity))
            .execute()
    }

    // Списание физического остатка без свободного ограничения — для карантинной
    // строки возврата при инспекции (карантин недоступен обычным операциям,
    // reserved там всегда 0).
    fun decreasePhysical(balanceId: Long, quantity: BigDecimal): Int {
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
        movementType: String,
        nomenclatureId: Long,
        lotId: Long?,
        serialId: Long?,
        fromLocationId: Long?,
        toLocationId: Long?,
        quantity: BigDecimal,
        documentType: String,
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
            .set(STOCK_MOVEMENT.TO_LOCATION_ID, toLocationId)
            .set(STOCK_MOVEMENT.QUANTITY, quantity)
            .set(STOCK_MOVEMENT.DOCUMENT_TYPE, documentType)
            .set(STOCK_MOVEMENT.DOCUMENT_ID, documentId)
            .set(STOCK_MOVEMENT.NOTE, note)
            .set(STOCK_MOVEMENT.CREATED_BY, createdBy)
            .execute()
    }

    // Идемпотентность

    fun findIdempotency(userId: Long, operation: String, key: String): WmsIssueIdempotencyKeysRecord? {
        return dsl.selectFrom(WMS_ISSUE_IDEMPOTENCY_KEYS)
            .where(WMS_ISSUE_IDEMPOTENCY_KEYS.USER_ID.eq(userId))
            .and(WMS_ISSUE_IDEMPOTENCY_KEYS.OPERATION.eq(operation))
            .and(WMS_ISSUE_IDEMPOTENCY_KEYS.IDEMPOTENCY_KEY.eq(key))
            .fetchOne()
    }

    fun insertIdempotency(
        userId: Long,
        operation: String,
        key: String,
        requestHash: String,
        entityType: String,
        entityId: Long,
        responseStatus: Int,
        responseBody: String
    ) {
        dsl.insertInto(WMS_ISSUE_IDEMPOTENCY_KEYS)
            .set(WMS_ISSUE_IDEMPOTENCY_KEYS.USER_ID, userId)
            .set(WMS_ISSUE_IDEMPOTENCY_KEYS.OPERATION, operation)
            .set(WMS_ISSUE_IDEMPOTENCY_KEYS.IDEMPOTENCY_KEY, key)
            .set(WMS_ISSUE_IDEMPOTENCY_KEYS.REQUEST_HASH, requestHash)
            .set(WMS_ISSUE_IDEMPOTENCY_KEYS.ENTITY_TYPE, entityType)
            .set(WMS_ISSUE_IDEMPOTENCY_KEYS.ENTITY_ID, entityId)
            .set(WMS_ISSUE_IDEMPOTENCY_KEYS.RESPONSE_STATUS, responseStatus)
            .set(WMS_ISSUE_IDEMPOTENCY_KEYS.RESPONSE_BODY, responseBody)
            .execute()
    }

    private fun <R : org.jooq.Record> generateNumber(
        prefixCode: String,
        lockOffset: Long,
        numberField: org.jooq.TableField<R, String>,
        table: org.jooq.Table<R>
    ): String {
        val year = OffsetDateTime.now().year
        val lockKey = year * 10000L + lockOffset
        dsl.fetch("SELECT pg_advisory_xact_lock(?)", lockKey)
        val prefix = "$prefixCode-$year-"
        val maxNumber = dsl.select(numberField)
            .from(table)
            .where(numberField.like("$prefix%"))
            .orderBy(numberField.desc())
            .limit(1)
            .fetchOne(numberField)
        val nextIndex = if (maxNumber == null) {
            1
        } else {
            maxNumber.removePrefix(prefix).toIntOrNull()?.plus(1) ?: 1
        }
        return String.format("$prefix%05d", nextIndex)
    }
}
