package org.example.project.repository

import org.example.project.generated.jooq.tables.Lots.LOTS
import org.example.project.generated.jooq.tables.Nomenclature.NOMENCLATURE
import org.example.project.generated.jooq.tables.NomenclatureBarcodes.NOMENCLATURE_BARCODES
import org.example.project.generated.jooq.tables.Projects.PROJECTS
import org.example.project.generated.jooq.tables.PurchaseDeliveries.PURCHASE_DELIVERIES
import org.example.project.generated.jooq.tables.PurchaseOrderLines.PURCHASE_ORDER_LINES
import org.example.project.generated.jooq.tables.PurchaseOrders.PURCHASE_ORDERS
import org.example.project.generated.jooq.tables.ReceiptLinks.RECEIPT_LINKS
import org.example.project.generated.jooq.tables.ReceiptLines.RECEIPT_LINES
import org.example.project.generated.jooq.tables.Receipts.RECEIPTS
import org.example.project.generated.jooq.tables.ReservationHistory.RESERVATION_HISTORY
import org.example.project.generated.jooq.tables.Reservations.RESERVATIONS
import org.example.project.generated.jooq.tables.SerialItems.SERIAL_ITEMS
import org.example.project.generated.jooq.tables.StockBalance.STOCK_BALANCE
import org.example.project.generated.jooq.tables.StockMovement.STOCK_MOVEMENT
import org.example.project.generated.jooq.tables.StorageLocations.STORAGE_LOCATIONS
import org.example.project.generated.jooq.tables.Tasks.TASKS
import org.example.project.generated.jooq.tables.Users.USERS
import org.example.project.generated.jooq.tables.Warehouses.WAREHOUSES
import org.example.project.generated.jooq.tables.records.LotsRecord
import org.example.project.generated.jooq.tables.records.NomenclatureBarcodesRecord
import org.example.project.generated.jooq.tables.records.NomenclatureRecord
import org.example.project.generated.jooq.tables.records.PurchaseDeliveriesRecord
import org.example.project.generated.jooq.tables.records.PurchaseOrderLinesRecord
import org.example.project.generated.jooq.tables.records.PurchaseOrdersRecord
import org.example.project.generated.jooq.tables.records.ReceiptLinesRecord
import org.example.project.generated.jooq.tables.records.ReceiptLinksRecord
import org.example.project.generated.jooq.tables.records.ReceiptsRecord
import org.example.project.generated.jooq.tables.records.ReservationHistoryRecord
import org.example.project.generated.jooq.tables.records.ReservationsRecord
import org.example.project.generated.jooq.tables.records.SerialItemsRecord
import org.example.project.generated.jooq.tables.records.StockBalanceRecord
import org.example.project.generated.jooq.tables.records.StorageLocationsRecord
import org.example.project.generated.jooq.tables.records.WarehousesRecord
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Record
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime

class WmsRepository(private val dsl: DSLContext) {

    data class StockRow(
        val balance: StockBalanceRecord,
        val locationCode: String,
        val locationPurpose: String,
        val lotNumber: String?,
        val serialNumber: String?
    )

    // Склады

    fun findWarehouseById(id: Long): WarehousesRecord? {
        return dsl.selectFrom(WAREHOUSES)
            .where(WAREHOUSES.ID.eq(id))
            .fetchOne()
    }

    fun existsWarehouseByCode(code: String): Boolean {
        return dsl.fetchExists(
            dsl.selectOne().from(WAREHOUSES).where(WAREHOUSES.CODE.eq(code))
        )
    }

    fun listWarehouses(): List<WarehousesRecord> {
        return dsl.selectFrom(WAREHOUSES)
            .orderBy(WAREHOUSES.CODE.asc())
            .fetch()
    }

    fun createWarehouse(code: String, name: String, address: String?, createdBy: Long): WarehousesRecord {
        val record = dsl.newRecord(WAREHOUSES)
        record.code = code
        record.name = name
        record.address = address
        record.createdBy = createdBy
        record.store()
        return findWarehouseById(record.id)!!
    }

    // Места хранения

    fun findLocationById(id: Long): StorageLocationsRecord? {
        return dsl.selectFrom(STORAGE_LOCATIONS)
            .where(STORAGE_LOCATIONS.ID.eq(id))
            .fetchOne()
    }

    fun existsLocationByCode(warehouseId: Long, code: String): Boolean {
        return dsl.fetchExists(
            dsl.selectOne().from(STORAGE_LOCATIONS)
                .where(STORAGE_LOCATIONS.WAREHOUSE_ID.eq(warehouseId))
                .and(STORAGE_LOCATIONS.CODE.eq(code))
        )
    }

    fun listLocationsByWarehouse(warehouseId: Long): List<StorageLocationsRecord> {
        return dsl.selectFrom(STORAGE_LOCATIONS)
            .where(STORAGE_LOCATIONS.WAREHOUSE_ID.eq(warehouseId))
            .orderBy(STORAGE_LOCATIONS.CODE.asc())
            .fetch()
    }

    fun createLocation(
        warehouseId: Long,
        parentId: Long?,
        code: String,
        name: String,
        kind: String,
        purpose: String,
        capacity: BigDecimal?,
        createdBy: Long
    ): StorageLocationsRecord {
        val record = dsl.newRecord(STORAGE_LOCATIONS)
        record.warehouseId = warehouseId
        record.parentId = parentId
        record.code = code
        record.name = name
        record.kind = kind
        record.purpose = purpose
        record.capacity = capacity
        record.createdBy = createdBy
        record.store()
        return findLocationById(record.id)!!
    }

    // Номенклатура и штрихкоды

    fun findNomenclatureById(id: Long): NomenclatureRecord? {
        return dsl.selectFrom(NOMENCLATURE)
            .where(NOMENCLATURE.ID.eq(id))
            .fetchOne()
    }

    fun existsNomenclatureByCode(code: String): Boolean {
        return dsl.fetchExists(
            dsl.selectOne().from(NOMENCLATURE).where(NOMENCLATURE.CODE.eq(code))
        )
    }

    fun listNomenclature(): List<NomenclatureRecord> {
        return dsl.selectFrom(NOMENCLATURE)
            .orderBy(NOMENCLATURE.CODE.asc())
            .fetch()
    }

    fun createNomenclature(
        code: String,
        name: String,
        description: String?,
        type: String,
        unit: String,
        lotTracked: Boolean,
        serialTracked: Boolean,
        createdBy: Long
    ): NomenclatureRecord {
        val record = dsl.newRecord(NOMENCLATURE)
        record.code = code
        record.name = name
        record.description = description
        record.type = type
        record.unit = unit
        record.lotTracked = lotTracked
        record.serialTracked = serialTracked
        record.createdBy = createdBy
        record.store()
        return findNomenclatureById(record.id)!!
    }

    fun archiveNomenclature(id: Long): NomenclatureRecord? {
        return dsl.update(NOMENCLATURE)
            .set(NOMENCLATURE.ARCHIVED, true)
            .set(NOMENCLATURE.VERSION, NOMENCLATURE.VERSION.plus(1))
            .where(NOMENCLATURE.ID.eq(id))
            .and(NOMENCLATURE.ARCHIVED.eq(false))
            .returning()
            .fetchOne()
    }

    fun insertBarcode(
        nomenclatureId: Long,
        barcode: String,
        packageType: String,
        quantityInPackage: BigDecimal,
        createdBy: Long
    ) {
        dsl.insertInto(NOMENCLATURE_BARCODES)
            .set(NOMENCLATURE_BARCODES.NOMENCLATURE_ID, nomenclatureId)
            .set(NOMENCLATURE_BARCODES.BARCODE, barcode)
            .set(NOMENCLATURE_BARCODES.PACKAGE_TYPE, packageType)
            .set(NOMENCLATURE_BARCODES.QUANTITY_IN_PACKAGE, quantityInPackage)
            .set(NOMENCLATURE_BARCODES.CREATED_BY, createdBy)
            .execute()
    }

    fun listBarcodesFor(nomenclatureIds: List<Long>): List<NomenclatureBarcodesRecord> {
        if (nomenclatureIds.isEmpty()) return emptyList()
        return dsl.selectFrom(NOMENCLATURE_BARCODES)
            .where(NOMENCLATURE_BARCODES.NOMENCLATURE_ID.`in`(nomenclatureIds))
            .orderBy(NOMENCLATURE_BARCODES.ID.asc())
            .fetch()
    }

    // Партии и серии

    fun findLot(nomenclatureId: Long, lotNumber: String): LotsRecord? {
        return dsl.selectFrom(LOTS)
            .where(LOTS.NOMENCLATURE_ID.eq(nomenclatureId))
            .and(LOTS.LOT_NUMBER.eq(lotNumber))
            .fetchOne()
    }

    fun findLotById(id: Long): LotsRecord? {
        return dsl.selectFrom(LOTS)
            .where(LOTS.ID.eq(id))
            .fetchOne()
    }

    fun createLot(nomenclatureId: Long, lotNumber: String, createdBy: Long): LotsRecord {
        val record = dsl.newRecord(LOTS)
        record.nomenclatureId = nomenclatureId
        record.lotNumber = lotNumber
        record.createdBy = createdBy
        record.store()
        return findLotById(record.id)!!
    }

    fun findSerialById(id: Long): SerialItemsRecord? {
        return dsl.selectFrom(SERIAL_ITEMS)
            .where(SERIAL_ITEMS.ID.eq(id))
            .fetchOne()
    }

    // Остатки

    fun selectStock(warehouseId: Long?, nomenclatureId: Long?): List<StockRow> {
        val conditions = mutableListOf<Condition>()
        warehouseId?.let { conditions += STOCK_BALANCE.WAREHOUSE_ID.eq(it) }
        nomenclatureId?.let { conditions += STOCK_BALANCE.NOMENCLATURE_ID.eq(it) }
        return dsl.select(
            STOCK_BALANCE.asterisk(),
            STORAGE_LOCATIONS.CODE,
            STORAGE_LOCATIONS.PURPOSE,
            LOTS.LOT_NUMBER,
            SERIAL_ITEMS.SERIAL_NUMBER
        )
            .from(STOCK_BALANCE)
            .join(STORAGE_LOCATIONS).on(STORAGE_LOCATIONS.ID.eq(STOCK_BALANCE.LOCATION_ID))
            .leftJoin(LOTS).on(LOTS.ID.eq(STOCK_BALANCE.LOT_ID))
            .leftJoin(SERIAL_ITEMS).on(SERIAL_ITEMS.ID.eq(STOCK_BALANCE.SERIAL_ID))
            .where(conditions)
            .orderBy(STOCK_BALANCE.ID.asc())
            .fetch { record: Record ->
                StockRow(
                    balance = record.into(STOCK_BALANCE),
                    locationCode = record.get(STORAGE_LOCATIONS.CODE),
                    locationPurpose = record.get(STORAGE_LOCATIONS.PURPOSE),
                    lotNumber = record.get(LOTS.LOT_NUMBER),
                    serialNumber = record.get(SERIAL_ITEMS.SERIAL_NUMBER)
                )
            }
    }

    fun lockBalance(
        warehouseId: Long,
        locationId: Long,
        nomenclatureId: Long,
        lotId: Long?,
        serialId: Long?
    ): StockBalanceRecord? {
        return dsl.selectFrom(STOCK_BALANCE)
            .where(STOCK_BALANCE.WAREHOUSE_ID.eq(warehouseId))
            .and(STOCK_BALANCE.LOCATION_ID.eq(locationId))
            .and(STOCK_BALANCE.NOMENCLATURE_ID.eq(nomenclatureId))
            .and(if (lotId != null) STOCK_BALANCE.LOT_ID.eq(lotId) else STOCK_BALANCE.LOT_ID.isNull)
            .and(if (serialId != null) STOCK_BALANCE.SERIAL_ID.eq(serialId) else STOCK_BALANCE.SERIAL_ID.isNull)
            .forUpdate()
            .fetchOne()
    }

    // Атомарный upsert физического остатка по ключу учётной позиции.
    // Условный ON CONFLICT по выражениям соответствует idx_stock_balance_key.
    fun increasePhysical(
        warehouseId: Long,
        locationId: Long,
        nomenclatureId: Long,
        lotId: Long?,
        serialId: Long?,
        quantity: BigDecimal
    ): StockBalanceRecord {
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
            warehouseId, locationId, nomenclatureId, lotId, serialId, quantity
        )!!.into(STOCK_BALANCE)
    }

    // Условное увеличение резерва: не даёт доступному уйти ниже нуля
    // даже при гонке вне блокировки строки.
    fun addReserved(balanceId: Long, quantity: BigDecimal): Int {
        return dsl.update(STOCK_BALANCE)
            .set(STOCK_BALANCE.RESERVED_QTY, STOCK_BALANCE.RESERVED_QTY.plus(quantity))
            .set(STOCK_BALANCE.UPDATED_AT, OffsetDateTime.now())
            .set(STOCK_BALANCE.VERSION, STOCK_BALANCE.VERSION.plus(1))
            .where(STOCK_BALANCE.ID.eq(balanceId))
            .and(STOCK_BALANCE.PHYSICAL_QTY.minus(STOCK_BALANCE.RESERVED_QTY).ge(quantity))
            .execute()
    }

    fun subtractReserved(balanceId: Long, quantity: BigDecimal): Int {
        return dsl.update(STOCK_BALANCE)
            .set(STOCK_BALANCE.RESERVED_QTY, STOCK_BALANCE.RESERVED_QTY.minus(quantity))
            .set(STOCK_BALANCE.UPDATED_AT, OffsetDateTime.now())
            .set(STOCK_BALANCE.VERSION, STOCK_BALANCE.VERSION.plus(1))
            .where(STOCK_BALANCE.ID.eq(balanceId))
            .and(STOCK_BALANCE.RESERVED_QTY.ge(quantity))
            .execute()
    }

    // Журнал движений (append-only)

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

    // Приёмка

    fun findReceiptById(id: Long): ReceiptsRecord? {
        return dsl.selectFrom(RECEIPTS)
            .where(RECEIPTS.ID.eq(id))
            .fetchOne()
    }

    fun lockReceiptById(id: Long): ReceiptsRecord? {
        return dsl.selectFrom(RECEIPTS)
            .where(RECEIPTS.ID.eq(id))
            .forUpdate()
            .fetchOne()
    }

    fun createReceipt(
        number: String,
        sourceType: String,
        sourceRef: String?,
        sourceDocumentNo: String?,
        sourceDocumentDate: LocalDate?,
        purchaseOrderId: Long?,
        purchaseDeliveryId: Long?,
        warehouseId: Long,
        expectedAt: OffsetDateTime?,
        note: String?,
        createdBy: Long
    ): ReceiptsRecord {
        val record = dsl.newRecord(RECEIPTS)
        record.number = number
        record.sourceType = sourceType
        record.sourceRef = sourceRef
        record.sourceDocumentNo = sourceDocumentNo
        record.sourceDocumentDate = sourceDocumentDate
        record.purchaseOrderId = purchaseOrderId
        record.purchaseDeliveryId = purchaseDeliveryId
        record.warehouseId = warehouseId
        record.expectedAt = expectedAt
        record.note = note
        record.createdBy = createdBy
        record.store()
        return findReceiptById(record.id)!!
    }

    /** Поиск приёмки по тому же документу-основанию: повторная приёмка запрещена. */
    fun findReceiptBySourceDocument(
        sourceType: String,
        sourceDocumentNo: String
    ): ReceiptsRecord? =
        dsl.selectFrom(RECEIPTS)
            .where(RECEIPTS.SOURCE_TYPE.eq(sourceType))
            .and(RECEIPTS.SOURCE_DOCUMENT_NO.eq(sourceDocumentNo))
            .and(RECEIPTS.STATUS.ne("CANCELLED"))
            .fetchOne()

    fun findReceiptsByPurchaseOrder(orderId: Long): List<ReceiptsRecord> =
        dsl.selectFrom(RECEIPTS)
            .where(RECEIPTS.PURCHASE_ORDER_ID.eq(orderId))
            .orderBy(RECEIPTS.ID.asc())
            .fetch()

    fun updateReceiptLinks(
        id: Long,
        expectedVersion: Long,
        purchaseOrderId: Long?,
        purchaseDeliveryId: Long?,
        sourceDocumentNo: String?,
        updatedBy: Long
    ): ReceiptsRecord? {
        val set = mutableMapOf<org.jooq.Field<*>, Any?>()
        if (purchaseOrderId != null) set[RECEIPTS.PURCHASE_ORDER_ID] = purchaseOrderId
        if (purchaseDeliveryId != null) set[RECEIPTS.PURCHASE_DELIVERY_ID] = purchaseDeliveryId
        if (sourceDocumentNo != null) set[RECEIPTS.SOURCE_DOCUMENT_NO] = sourceDocumentNo
        set[RECEIPTS.VERSION] = RECEIPTS.VERSION.plus(1)
        val changed = dsl.update(RECEIPTS)
            .set(set)
            .where(RECEIPTS.ID.eq(id))
            .and(RECEIPTS.VERSION.eq(expectedVersion))
            .execute()
        if (changed == 0) return null
        return findReceiptById(id)
    }

    fun insertReceiptLink(
        receiptId: Long,
        purchaseOrderId: Long?,
        purchaseDeliveryId: Long?,
        sourceDocumentNo: String?,
        reason: String,
        linkedBy: Long
    ) {
        dsl.insertInto(RECEIPT_LINKS)
            .set(RECEIPT_LINKS.RECEIPT_ID, receiptId)
            .set(RECEIPT_LINKS.PURCHASE_ORDER_ID, purchaseOrderId)
            .set(RECEIPT_LINKS.PURCHASE_DELIVERY_ID, purchaseDeliveryId)
            .set(RECEIPT_LINKS.SOURCE_DOCUMENT_NO, sourceDocumentNo)
            .set(RECEIPT_LINKS.REASON, reason)
            .set(RECEIPT_LINKS.LINKED_BY, linkedBy)
            .execute()
    }

    fun listReceiptLinks(receiptId: Long): List<ReceiptLinksRecord> =
        dsl.selectFrom(RECEIPT_LINKS)
            .where(RECEIPT_LINKS.RECEIPT_ID.eq(receiptId))
            .orderBy(RECEIPT_LINKS.ID.asc())
            .fetch()

    /** Заказ, к которому относится приёмка, — для проверок прав и сумм. */
    fun findPurchaseOrderById(id: Long): PurchaseOrdersRecord? =
        dsl.selectFrom(PURCHASE_ORDERS).where(PURCHASE_ORDERS.ID.eq(id)).fetchOne()

    fun findPurchaseOrderLineById(id: Long): PurchaseOrderLinesRecord? =
        dsl.selectFrom(PURCHASE_ORDER_LINES).where(PURCHASE_ORDER_LINES.ID.eq(id)).fetchOne()

    fun listPurchaseOrderLines(orderId: Long): List<PurchaseOrderLinesRecord> =
        dsl.selectFrom(PURCHASE_ORDER_LINES)
            .where(PURCHASE_ORDER_LINES.ORDER_ID.eq(orderId))
            .orderBy(PURCHASE_ORDER_LINES.LINE_NO.asc())
            .fetch()

    fun findPurchaseDeliveryById(id: Long): PurchaseDeliveriesRecord? =
        dsl.selectFrom(PURCHASE_DELIVERIES).where(PURCHASE_DELIVERIES.ID.eq(id)).fetchOne()

    /** Ожидаемые к приёмке позиции по заказу (view `purchase_order_receipt_status`). */
    fun expectedReceiptLines(orderId: Long?): List<org.jooq.Record> {
        val sql = StringBuilder(
            """
            SELECT order_id, order_number, order_status, order_line_id, line_no,
                   nomenclature_id, ordered_quantity, delivered_quantity,
                   accepted_quantity, expected_remaining, not_accepted_quantity
            FROM purchase_order_receipt_status
            """.trimIndent()
        )
        if (orderId != null) sql.append(" WHERE order_id = ?")
        sql.append(" ORDER BY order_id, line_no")
        return if (orderId != null) {
            dsl.fetch(sql.toString(), orderId).intoList()
        } else {
            dsl.fetch(sql.toString()).intoList()
        }
    }

    /**
     * Принятое по позиции заказа: сумма проведённых приёмок.
     * `excludeReceiptId` исключает текущий документ — так пересчитывается
     * остаток при проведении, не считая приёмку дважды.
     */
    fun acceptedQuantityForOrderLine(orderLineId: Long, excludeReceiptId: Long? = null): BigDecimal =
        dsl.fetchOne(
            """
            SELECT COALESCE(SUM(rl.received_quantity), 0) AS total
            FROM receipt_lines rl
                     JOIN receipts r ON r.id = rl.receipt_id
            WHERE rl.purchase_order_line_id = ?
              AND r.status = 'POSTED'
              AND (? IS NULL OR r.id <> ?)
            """.trimIndent(),
            orderLineId, excludeReceiptId, excludeReceiptId
        )?.get("total", BigDecimal::class.java) ?: BigDecimal.ZERO

    /** Принято по позициям поставки: позиция заказа → количество. */
    fun acceptedQuantitiesForDelivery(deliveryId: Long): Map<Long, BigDecimal> =
        dsl.fetch(
            """
            SELECT rl.purchase_order_line_id AS order_line_id,
                   COALESCE(SUM(rl.received_quantity), 0) AS accepted
            FROM receipt_lines rl
                     JOIN receipts r ON r.id = rl.receipt_id
            WHERE r.status = 'POSTED'
              AND r.purchase_delivery_id = ?
              AND rl.purchase_order_line_id IS NOT NULL
            GROUP BY rl.purchase_order_line_id
            """.trimIndent(),
            deliveryId
        ).associate { row ->
            row.get("order_line_id", Long::class.java) to row.get("accepted", BigDecimal::class.java)
        }

    fun listReceipts(purchaseOrderId: Long?, status: String?): List<ReceiptsRecord> {
        val conditions = mutableListOf<Condition>()
        purchaseOrderId?.let { conditions += RECEIPTS.PURCHASE_ORDER_ID.eq(it) }
        status?.let { conditions += RECEIPTS.STATUS.eq(it) }
        return dsl.selectFrom(RECEIPTS)
            .where(conditions)
            .orderBy(RECEIPTS.ID.desc())
            .fetch()
    }

    fun insertReceiptLine(
        receiptId: Long,
        lineNo: Int,
        nomenclatureId: Long,
        expectedQuantity: BigDecimal,
        receivedQuantity: BigDecimal,
        lotId: Long?,
        locationId: Long?,
        note: String?,
        purchaseOrderLineId: Long? = null,
        discrepancyKind: String? = null,
        discrepancyQuantity: BigDecimal? = null,
        discrepancyReason: String? = null
    ) {
        dsl.insertInto(RECEIPT_LINES)
            .set(RECEIPT_LINES.RECEIPT_ID, receiptId)
            .set(RECEIPT_LINES.LINE_NO, lineNo)
            .set(RECEIPT_LINES.NOMENCLATURE_ID, nomenclatureId)
            .set(RECEIPT_LINES.EXPECTED_QUANTITY, expectedQuantity)
            .set(RECEIPT_LINES.RECEIVED_QUANTITY, receivedQuantity)
            .set(RECEIPT_LINES.LOT_ID, lotId)
            .set(RECEIPT_LINES.LOCATION_ID, locationId)
            .set(RECEIPT_LINES.NOTE, note)
            .set(RECEIPT_LINES.PURCHASE_ORDER_LINE_ID, purchaseOrderLineId)
            .set(RECEIPT_LINES.DISCREPANCY_KIND, discrepancyKind)
            .set(RECEIPT_LINES.DISCREPANCY_QUANTITY, discrepancyQuantity)
            .set(RECEIPT_LINES.DISCREPANCY_REASON, discrepancyReason)
            .execute()
    }

    fun listReceiptLines(receiptId: Long): List<ReceiptLinesRecord> {
        return dsl.selectFrom(RECEIPT_LINES)
            .where(RECEIPT_LINES.RECEIPT_ID.eq(receiptId))
            .orderBy(RECEIPT_LINES.LINE_NO.asc())
            .fetch()
    }

    // Перевод в POSTED только из DRAFT/IN_PROGRESS и только при совпадении
    // версии: повторное проведение и конкурентное изменение не проходят.
    fun postReceipt(id: Long, expectedVersion: Long, postedBy: Long): ReceiptsRecord? {
        return dsl.update(RECEIPTS)
            .set(RECEIPTS.STATUS, "POSTED")
            .set(RECEIPTS.POSTED_AT, OffsetDateTime.now())
            .set(RECEIPTS.POSTED_BY, postedBy)
            .set(RECEIPTS.VERSION, RECEIPTS.VERSION.plus(1))
            .where(RECEIPTS.ID.eq(id))
            .and(RECEIPTS.VERSION.eq(expectedVersion))
            .and(RECEIPTS.STATUS.`in`("DRAFT", "IN_PROGRESS"))
            .returning()
            .fetchOne()
    }

    fun generateReceiptNumber(): String {
        return generateNumber("RCP", 8L, RECEIPTS.NUMBER, RECEIPTS)
    }

    // Резервы

    fun findReservationById(id: Long): ReservationsRecord? {
        return dsl.selectFrom(RESERVATIONS)
            .where(RESERVATIONS.ID.eq(id))
            .fetchOne()
    }

    fun lockReservationById(id: Long): ReservationsRecord? {
        return dsl.selectFrom(RESERVATIONS)
            .where(RESERVATIONS.ID.eq(id))
            .forUpdate()
            .fetchOne()
    }

    fun listReservations(warehouseId: Long?, nomenclatureId: Long?, status: String?): List<ReservationsRecord> {
        val conditions = mutableListOf<Condition>()
        warehouseId?.let { conditions += RESERVATIONS.WAREHOUSE_ID.eq(it) }
        nomenclatureId?.let { conditions += RESERVATIONS.NOMENCLATURE_ID.eq(it) }
        status?.let { conditions += RESERVATIONS.STATUS.eq(it) }
        return dsl.selectFrom(RESERVATIONS)
            .where(conditions)
            .orderBy(RESERVATIONS.ID.desc())
            .fetch()
    }

    fun createReservation(
        number: String,
        reasonType: String,
        projectId: Long?,
        taskId: Long?,
        reasonNote: String?,
        nomenclatureId: Long,
        warehouseId: Long,
        locationId: Long?,
        lotId: Long?,
        serialId: Long?,
        quantity: BigDecimal,
        ownerId: Long,
        expiresAt: OffsetDateTime?,
        createdBy: Long
    ): ReservationsRecord {
        val record = dsl.newRecord(RESERVATIONS)
        record.number = number
        record.reasonType = reasonType
        record.projectId = projectId
        record.taskId = taskId
        record.reasonNote = reasonNote
        record.nomenclatureId = nomenclatureId
        record.warehouseId = warehouseId
        record.locationId = locationId
        record.lotId = lotId
        record.serialId = serialId
        record.quantity = quantity
        record.ownerId = ownerId
        record.expiresAt = expiresAt
        record.createdBy = createdBy
        record.store()
        return findReservationById(record.id)!!
    }

    fun updateReservationStatus(id: Long, expectedVersion: Long, fromStatus: String, newStatus: String): ReservationsRecord? {
        return dsl.update(RESERVATIONS)
            .set(RESERVATIONS.STATUS, newStatus)
            .set(RESERVATIONS.VERSION, RESERVATIONS.VERSION.plus(1))
            .where(RESERVATIONS.ID.eq(id))
            .and(RESERVATIONS.VERSION.eq(expectedVersion))
            .and(RESERVATIONS.STATUS.eq(fromStatus))
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

    fun listReservationHistory(reservationId: Long): List<ReservationHistoryRecord> {
        return dsl.selectFrom(RESERVATION_HISTORY)
            .where(RESERVATION_HISTORY.RESERVATION_ID.eq(reservationId))
            .orderBy(RESERVATION_HISTORY.ID.asc())
            .fetch()
    }

    fun generateReservationNumber(): String {
        return generateNumber("RSV", 9L, RESERVATIONS.NUMBER, RESERVATIONS)
    }

    // Ссылки на существующие сущности

    fun projectExists(id: Long): Boolean {
        return dsl.fetchExists(dsl.selectOne().from(PROJECTS).where(PROJECTS.ID.eq(id)))
    }

    fun taskExists(id: Long): Boolean {
        return dsl.fetchExists(dsl.selectOne().from(TASKS).where(TASKS.ID.eq(id)))
    }

    fun userExists(id: Long): Boolean {
        return dsl.fetchExists(dsl.selectOne().from(USERS).where(USERS.ID.eq(id)))
    }

    // Нумерация по образцу ProjectRepository.generateNumber: advisory lock
    // сериализует выдачу номеров внутри транзакции.
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
