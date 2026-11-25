package org.example.project.repository

import org.example.project.generated.jooq.tables.BomLines.BOM_LINES
import org.example.project.generated.jooq.tables.DemandHistory.DEMAND_HISTORY
import org.example.project.generated.jooq.tables.DemandLineHistory.DEMAND_LINE_HISTORY
import org.example.project.generated.jooq.tables.DemandLines.DEMAND_LINES
import org.example.project.generated.jooq.tables.Demands.DEMANDS
import org.example.project.generated.jooq.tables.Lots.LOTS
import org.example.project.generated.jooq.tables.Nomenclature.NOMENCLATURE
import org.example.project.generated.jooq.tables.NomenclatureAnalogs.NOMENCLATURE_ANALOGS
import org.example.project.generated.jooq.tables.NomenclatureBarcodes.NOMENCLATURE_BARCODES
import org.example.project.generated.jooq.tables.NomenclatureUnitConversions.NOMENCLATURE_UNIT_CONVERSIONS
import org.example.project.generated.jooq.tables.ProductRevisions.PRODUCT_REVISIONS
import org.example.project.generated.jooq.tables.Projects.PROJECTS
import org.example.project.generated.jooq.tables.PurchaseDeliveries.PURCHASE_DELIVERIES
import org.example.project.generated.jooq.tables.PurchaseOrderDemands.PURCHASE_ORDER_DEMANDS
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
import org.example.project.generated.jooq.tables.SupplierOfferLines.SUPPLIER_OFFER_LINES
import org.example.project.generated.jooq.tables.SupplierOffers.SUPPLIER_OFFERS
import org.example.project.generated.jooq.tables.Suppliers.SUPPLIERS
import org.example.project.generated.jooq.tables.Tasks.TASKS
import org.example.project.generated.jooq.tables.Users.USERS
import org.example.project.generated.jooq.tables.Warehouses.WAREHOUSES
import org.example.project.generated.jooq.tables.records.DemandLinesRecord
import org.example.project.generated.jooq.tables.records.DemandsRecord
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

    data class MovementRow(
        val movement: org.example.project.generated.jooq.tables.records.StockMovementRecord,
        val lotNumber: String?,
        val serialNumber: String?,
        val fromLocationCode: String?,
        val toLocationCode: String?,
        val createdByName: String
    )

    fun listLots(nomenclatureId: Long): List<LotsRecord> {
        return dsl.selectFrom(LOTS)
            .where(LOTS.NOMENCLATURE_ID.eq(nomenclatureId))
            .orderBy(LOTS.CREATED_AT.desc(), LOTS.ID.desc())
            .fetch()
    }

    fun listSerials(nomenclatureId: Long): List<SerialItemsRecord> {
        return dsl.selectFrom(SERIAL_ITEMS)
            .where(SERIAL_ITEMS.NOMENCLATURE_ID.eq(nomenclatureId))
            .orderBy(SERIAL_ITEMS.CREATED_AT.desc(), SERIAL_ITEMS.ID.desc())
            .fetch()
    }

    fun listMovements(nomenclatureId: Long): List<MovementRow> {
        val fromLocations = STORAGE_LOCATIONS.`as`("from_locations")
        val toLocations = STORAGE_LOCATIONS.`as`("to_locations")
        return dsl.select(
            STOCK_MOVEMENT.asterisk(),
            LOTS.LOT_NUMBER,
            SERIAL_ITEMS.SERIAL_NUMBER,
            fromLocations.CODE,
            toLocations.CODE,
            USERS.FULL_NAME
        )
            .from(STOCK_MOVEMENT)
            .leftJoin(LOTS).on(LOTS.ID.eq(STOCK_MOVEMENT.LOT_ID))
            .leftJoin(SERIAL_ITEMS).on(SERIAL_ITEMS.ID.eq(STOCK_MOVEMENT.SERIAL_ID))
            .leftJoin(fromLocations).on(fromLocations.ID.eq(STOCK_MOVEMENT.FROM_LOCATION_ID))
            .leftJoin(toLocations).on(toLocations.ID.eq(STOCK_MOVEMENT.TO_LOCATION_ID))
            .join(USERS).on(USERS.ID.eq(STOCK_MOVEMENT.CREATED_BY))
            .where(STOCK_MOVEMENT.NOMENCLATURE_ID.eq(nomenclatureId))
            .orderBy(STOCK_MOVEMENT.CREATED_AT.desc(), STOCK_MOVEMENT.ID.desc())
            .fetch { record ->
                MovementRow(
                    movement = record.into(STOCK_MOVEMENT),
                    lotNumber = record.get(LOTS.LOT_NUMBER),
                    serialNumber = record.get(SERIAL_ITEMS.SERIAL_NUMBER),
                    fromLocationCode = record.get(fromLocations.CODE),
                    toLocationCode = record.get(toLocations.CODE),
                    createdByName = record.get(USERS.FULL_NAME)
                )
            }
    }

    // Склады

    fun findWarehouseById(id: Long): WarehousesRecord? {
        return dsl.selectFrom(WAREHOUSES)
            .where(WAREHOUSES.ID.eq(id))
            .fetchOne()
    }

    fun existsWarehouseByCode(code: String): Boolean {
        return dsl.fetchExists(
            dsl.selectOne().from(WAREHOUSES).where(WAREHOUSES.CODE.equalIgnoreCase(code))
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
                .and(STORAGE_LOCATIONS.CODE.equalIgnoreCase(code))
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
            dsl.selectOne().from(NOMENCLATURE).where(NOMENCLATURE.CODE.equalIgnoreCase(code))
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
        minStock: BigDecimal = BigDecimal.ZERO,
        safetyStock: BigDecimal = BigDecimal.ZERO,
        leadTimeDays: Int = 0,
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
        record.minStock = minStock
        record.safetyStock = safetyStock
        record.leadTimeDays = leadTimeDays
        record.createdBy = createdBy
        record.store()
        return findNomenclatureById(record.id)!!
    }

    fun updateNomenclatureThresholds(
        id: Long,
        minStock: BigDecimal,
        safetyStock: BigDecimal,
        leadTimeDays: Int,
        expectedVersion: Long
    ): NomenclatureRecord? {
        return dsl.update(NOMENCLATURE)
            .set(NOMENCLATURE.MIN_STOCK, minStock)
            .set(NOMENCLATURE.SAFETY_STOCK, safetyStock)
            .set(NOMENCLATURE.LEAD_TIME_DAYS, leadTimeDays)
            .set(NOMENCLATURE.VERSION, NOMENCLATURE.VERSION.plus(1))
            .where(NOMENCLATURE.ID.eq(id))
            .and(NOMENCLATURE.VERSION.eq(expectedVersion))
            .returning()
            .fetchOne()
    }

    fun findActiveNomenclatureReference(id: Long): String? = findActiveNomenclatureReferences(id).firstOrNull()

    /**
     * Все активные ссылки, которые запрещают архивирование (BOM-20).
     * Коды стабильны: подписи для пользователя формирует сервис карточки.
     */
    fun findActiveNomenclatureReferences(id: Long): List<String> {
        val result = mutableListOf<String>()
        val hasStock = dsl.fetchExists(
            dsl.selectOne().from(STOCK_BALANCE)
                .where(STOCK_BALANCE.NOMENCLATURE_ID.eq(id))
                .and(STOCK_BALANCE.PHYSICAL_QTY.gt(BigDecimal.ZERO))
        )
        if (hasStock) result += "stock_balance"
        val hasActiveReservation = dsl.fetchExists(
            dsl.selectOne().from(RESERVATIONS)
                .where(RESERVATIONS.NOMENCLATURE_ID.eq(id))
                .and(RESERVATIONS.STATUS.eq("ACTIVE"))
        )
        if (hasActiveReservation) result += "reservations"
        val hasActiveBom = dsl.fetchExists(
            dsl.selectOne().from(BOM_LINES)
                .join(PRODUCT_REVISIONS).on(PRODUCT_REVISIONS.ID.eq(BOM_LINES.REVISION_ID))
                .where(BOM_LINES.CHILD_NOMENCLATURE_ID.eq(id).or(BOM_LINES.PARENT_NOMENCLATURE_ID.eq(id)))
                .and(PRODUCT_REVISIONS.STATUS.notIn("CANCELLED", "REPLACED"))
        )
        if (hasActiveBom) result += "bom_lines"
        val hasOpenDemand = dsl.fetchExists(
            dsl.selectOne().from(DEMAND_LINES)
                .join(DEMANDS).on(DEMANDS.ID.eq(DEMAND_LINES.DEMAND_ID))
                .where(DEMAND_LINES.NOMENCLATURE_ID.eq(id))
                .and(DEMANDS.STATUS.notIn("COVERED", "CANCELLED"))
        )
        if (hasOpenDemand) result += "demand_lines"
        val hasOpenPurchaseOrder = dsl.fetchExists(
            dsl.selectOne().from(PURCHASE_ORDER_LINES)
                .join(PURCHASE_ORDERS).on(PURCHASE_ORDERS.ID.eq(PURCHASE_ORDER_LINES.ORDER_ID))
                .where(PURCHASE_ORDER_LINES.NOMENCLATURE_ID.eq(id))
                .and(PURCHASE_ORDERS.STATUS.notIn("DELIVERED", "CLOSED", "CANCELLED"))
        )
        if (hasOpenPurchaseOrder) result += "purchase_order_lines"
        val pom = org.example.project.generated.jooq.tables.ProductionOrderMaterials.PRODUCTION_ORDER_MATERIALS
        val po = org.example.project.generated.jooq.tables.ProductionOrders.PRODUCTION_ORDERS
        val hasActiveProduction = dsl.fetchExists(
            dsl.selectOne().from(pom)
                .join(po).on(po.ID.eq(pom.PRODUCTION_ORDER_ID))
                .where(pom.NOMENCLATURE_ID.eq(id))
                .and(po.STATUS.notIn("COMPLETED", "CLOSED", "CANCELLED"))
        )
        if (hasActiveProduction) result += "production_order_materials"
        return result
    }

    fun findNomenclatureByBarcode(barcode: String): NomenclatureRecord? {
        val nomId = dsl.select(NOMENCLATURE_BARCODES.NOMENCLATURE_ID)
            .from(NOMENCLATURE_BARCODES)
            .where(NOMENCLATURE_BARCODES.BARCODE.eq(barcode))
            .fetchOne(NOMENCLATURE_BARCODES.NOMENCLATURE_ID)
            ?: return null
        return findNomenclatureById(nomId)
    }

    fun deleteBarcode(nomenclatureId: Long, barcodeId: Long): Boolean {
        return dsl.deleteFrom(NOMENCLATURE_BARCODES)
            .where(NOMENCLATURE_BARCODES.ID.eq(barcodeId))
            .and(NOMENCLATURE_BARCODES.NOMENCLATURE_ID.eq(nomenclatureId))
            .execute() > 0
    }

    fun listUnitConversions(nomenclatureId: Long) =
        dsl.selectFrom(NOMENCLATURE_UNIT_CONVERSIONS)
            .where(NOMENCLATURE_UNIT_CONVERSIONS.NOMENCLATURE_ID.eq(nomenclatureId))
            .orderBy(NOMENCLATURE_UNIT_CONVERSIONS.ID.asc())
            .fetch()

    fun createUnitConversion(
        nomenclatureId: Long,
        fromUnit: String,
        toUnit: String,
        factor: BigDecimal,
        createdBy: Long
    ) = dsl.insertInto(NOMENCLATURE_UNIT_CONVERSIONS)
        .set(NOMENCLATURE_UNIT_CONVERSIONS.NOMENCLATURE_ID, nomenclatureId)
        .set(NOMENCLATURE_UNIT_CONVERSIONS.FROM_UNIT, fromUnit)
        .set(NOMENCLATURE_UNIT_CONVERSIONS.TO_UNIT, toUnit)
        .set(NOMENCLATURE_UNIT_CONVERSIONS.FACTOR, factor)
        .set(NOMENCLATURE_UNIT_CONVERSIONS.CREATED_BY, createdBy)
        .returning()
        .fetchOne()!!

    fun listAnalogs(nomenclatureId: Long) =
        dsl.select(
            NOMENCLATURE_ANALOGS.ID,
            NOMENCLATURE_ANALOGS.NOMENCLATURE_ID,
            NOMENCLATURE_ANALOGS.ANALOG_NOMENCLATURE_ID,
            NOMENCLATURE.CODE,
            NOMENCLATURE.NAME,
            NOMENCLATURE_ANALOGS.CONVERSION_FACTOR,
            NOMENCLATURE_ANALOGS.APPLICABILITY_CONDITION,
            NOMENCLATURE_ANALOGS.PRIORITY,
            NOMENCLATURE_ANALOGS.ACTIVE,
            NOMENCLATURE_ANALOGS.REQUIRES_APPROVAL
        )
            .from(NOMENCLATURE_ANALOGS)
            .join(NOMENCLATURE).on(NOMENCLATURE.ID.eq(NOMENCLATURE_ANALOGS.ANALOG_NOMENCLATURE_ID))
            .where(NOMENCLATURE_ANALOGS.NOMENCLATURE_ID.eq(nomenclatureId))
            .orderBy(NOMENCLATURE_ANALOGS.PRIORITY.asc(), NOMENCLATURE_ANALOGS.ID.asc())
            .fetch()

    fun analogExists(nomenclatureId: Long, analogNomenclatureId: Long): Boolean =
        dsl.fetchExists(
            dsl.selectOne().from(NOMENCLATURE_ANALOGS)
                .where(NOMENCLATURE_ANALOGS.NOMENCLATURE_ID.eq(nomenclatureId))
                .and(NOMENCLATURE_ANALOGS.ANALOG_NOMENCLATURE_ID.eq(analogNomenclatureId))
        )

    fun createAnalog(
        nomenclatureId: Long,
        analogNomenclatureId: Long,
        factor: BigDecimal,
        condition: String?,
        priority: Int,
        createdBy: Long,
        requiresApproval: Boolean = false
    ) = dsl.insertInto(NOMENCLATURE_ANALOGS)
        .set(NOMENCLATURE_ANALOGS.NOMENCLATURE_ID, nomenclatureId)
        .set(NOMENCLATURE_ANALOGS.ANALOG_NOMENCLATURE_ID, analogNomenclatureId)
        .set(NOMENCLATURE_ANALOGS.CONVERSION_FACTOR, factor)
        .set(NOMENCLATURE_ANALOGS.APPLICABILITY_CONDITION, condition)
        .set(NOMENCLATURE_ANALOGS.PRIORITY, priority)
        .set(NOMENCLATURE_ANALOGS.REQUIRES_APPROVAL, requiresApproval)
        .set(NOMENCLATURE_ANALOGS.CREATED_BY, createdBy)
        .returning()
        .fetchOne()!!

    fun deleteAnalog(nomenclatureId: Long, analogId: Long): Boolean =
        dsl.deleteFrom(NOMENCLATURE_ANALOGS)
            .where(NOMENCLATURE_ANALOGS.ID.eq(analogId))
            .and(NOMENCLATURE_ANALOGS.NOMENCLATURE_ID.eq(nomenclatureId))
            .execute() > 0

    fun unitConversionExists(nomenclatureId: Long, fromUnit: String, toUnit: String): Boolean =
        dsl.fetchExists(
            dsl.selectOne().from(NOMENCLATURE_UNIT_CONVERSIONS)
                .where(NOMENCLATURE_UNIT_CONVERSIONS.NOMENCLATURE_ID.eq(nomenclatureId))
                .and(NOMENCLATURE_UNIT_CONVERSIONS.FROM_UNIT.eq(fromUnit))
                .and(NOMENCLATURE_UNIT_CONVERSIONS.TO_UNIT.eq(toUnit))
        )

    fun deleteUnitConversion(nomenclatureId: Long, conversionId: Long): Boolean =
        dsl.deleteFrom(NOMENCLATURE_UNIT_CONVERSIONS)
            .where(NOMENCLATURE_UNIT_CONVERSIONS.ID.eq(conversionId))
            .and(NOMENCLATURE_UNIT_CONVERSIONS.NOMENCLATURE_ID.eq(nomenclatureId))
            .execute() > 0

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

    fun selectStock(warehouseId: Long?, nomenclatureId: Long?, locationId: Long? = null): List<StockRow> {
        val conditions = mutableListOf<Condition>()
        warehouseId?.let { conditions += STOCK_BALANCE.WAREHOUSE_ID.eq(it) }
        nomenclatureId?.let { conditions += STOCK_BALANCE.NOMENCLATURE_ID.eq(it) }
        locationId?.let { conditions += STOCK_BALANCE.LOCATION_ID.eq(it) }
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

    /**
     * Номера документов-оснований движений: (тип документа, id) → номер.
     * Типы без таблицы с номером (например, начальные остатки) в результат не попадают.
     */
    fun documentNumbers(refs: Collection<Pair<String, Long>>): Map<Pair<String, Long>, String> {
        val tables = mapOf(
            "RECEIPT" to "receipts",
            "ISSUE" to "issue_orders",
            "TRANSFER" to "transfers",
            "RETURN" to "return_orders",
            "WRITE_OFF" to "write_off_orders",
            "INVENTORY" to "inventory_orders"
        )
        val idField = org.jooq.impl.DSL.field(org.jooq.impl.DSL.name("id"), Long::class.java)
        val numberField = org.jooq.impl.DSL.field(org.jooq.impl.DSL.name("number"), String::class.java)
        val result = mutableMapOf<Pair<String, Long>, String>()
        refs.groupBy({ it.first.uppercase() }, { it.second }).forEach { (type, ids) ->
            val table = tables[type] ?: return@forEach
            dsl.select(idField, numberField)
                .from(org.jooq.impl.DSL.table(org.jooq.impl.DSL.name(table)))
                .where(idField.`in`(ids.distinct()))
                .fetch()
                .forEach { row -> result[type to row.get(idField)!!] = row.get(numberField)!! }
        }
        return result
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

    data class ExpectedOrderEnrichment(
        val supplierName: String?,
        val isPaid: Boolean,
        val paidAt: String?,
        val offerId: Long?,
        val invoiceDocumentId: Long?,
        val demandNumbers: List<String>,
        val invoiceNo: String? = null,
        val projectId: Long? = null,
        val projectNumber: String? = null,
        val projectName: String? = null
    )

    data class ExpectedReceiptEnrichments(
        val orders: Map<Long, ExpectedOrderEnrichment>,
        val unitsByOrderLine: Map<Long, String>,
        val nomenclature: Map<Long, Pair<String, String>>
    )

    fun expectedReceiptEnrichments(
        orderIds: Collection<Long>,
        orderLineIds: Collection<Long>,
        nomenclatureIds: Collection<Long>
    ): ExpectedReceiptEnrichments {
        val distinctOrderIds = orderIds.distinct()
        val distinctOrderLineIds = orderLineIds.distinct()
        val distinctNomIds = nomenclatureIds.distinct()

        val demandRowsByOrder = if (distinctOrderIds.isEmpty()) {
            emptyMap()
        } else {
            dsl.select(
                PURCHASE_ORDER_DEMANDS.PURCHASE_ORDER_ID,
                DEMANDS.NUMBER,
                DEMANDS.PROJECT_ID,
                PROJECTS.NUMBER,
                PROJECTS.NAME
            )
                .from(PURCHASE_ORDER_DEMANDS)
                .join(DEMANDS).on(DEMANDS.ID.eq(PURCHASE_ORDER_DEMANDS.DEMAND_ID))
                .leftJoin(PROJECTS).on(PROJECTS.ID.eq(DEMANDS.PROJECT_ID))
                .where(PURCHASE_ORDER_DEMANDS.PURCHASE_ORDER_ID.`in`(distinctOrderIds))
                .orderBy(DEMANDS.ID.asc())
                .fetch()
                .groupBy { it.get(PURCHASE_ORDER_DEMANDS.PURCHASE_ORDER_ID)!! }
        }

        val orders = if (distinctOrderIds.isEmpty()) {
            emptyMap()
        } else {
            dsl.select(
                PURCHASE_ORDERS.ID,
                SUPPLIERS.NAME,
                PURCHASE_ORDERS.PAID_AT,
                PURCHASE_ORDERS.OFFER_ID,
                PURCHASE_ORDERS.INVOICE_NO,
                SUPPLIER_OFFERS.INVOICE_DOCUMENT_ID,
                SUPPLIER_OFFERS.INVOICE_NUMBER
            )
                .from(PURCHASE_ORDERS)
                .join(SUPPLIERS).on(SUPPLIERS.ID.eq(PURCHASE_ORDERS.SUPPLIER_ID))
                .leftJoin(SUPPLIER_OFFERS).on(SUPPLIER_OFFERS.ID.eq(PURCHASE_ORDERS.OFFER_ID))
                .where(PURCHASE_ORDERS.ID.`in`(distinctOrderIds))
                .fetch()
                .associate { r ->
                    val id = r.get(PURCHASE_ORDERS.ID)!!
                    val paidAt = r.get(PURCHASE_ORDERS.PAID_AT)
                    val demandRows = demandRowsByOrder[id].orEmpty()
                    val projectRow = demandRows.firstOrNull { it.get(DEMANDS.PROJECT_ID) != null }
                    val invoiceNo = r.get(PURCHASE_ORDERS.INVOICE_NO)?.takeIf { it.isNotBlank() }
                        ?: r.get(SUPPLIER_OFFERS.INVOICE_NUMBER)?.takeIf { it.isNotBlank() }
                    id to ExpectedOrderEnrichment(
                        supplierName = r.get(SUPPLIERS.NAME),
                        isPaid = paidAt != null,
                        paidAt = paidAt?.toString(),
                        offerId = r.get(PURCHASE_ORDERS.OFFER_ID),
                        invoiceDocumentId = r.get(SUPPLIER_OFFERS.INVOICE_DOCUMENT_ID),
                        demandNumbers = demandRows.map { it.get(DEMANDS.NUMBER)!! },
                        invoiceNo = invoiceNo,
                        projectId = projectRow?.get(DEMANDS.PROJECT_ID),
                        projectNumber = projectRow?.get(PROJECTS.NUMBER),
                        projectName = projectRow?.get(PROJECTS.NAME)
                    )
                }
        }

        val unitsByOrderLine = if (distinctOrderLineIds.isEmpty()) {
            emptyMap()
        } else {
            dsl.select(PURCHASE_ORDER_LINES.ID, PURCHASE_ORDER_LINES.UNIT)
                .from(PURCHASE_ORDER_LINES)
                .where(PURCHASE_ORDER_LINES.ID.`in`(distinctOrderLineIds))
                .fetch()
                .associate { it.get(PURCHASE_ORDER_LINES.ID)!! to it.get(PURCHASE_ORDER_LINES.UNIT)!! }
        }

        val nomenclature = if (distinctNomIds.isEmpty()) {
            emptyMap()
        } else {
            dsl.select(NOMENCLATURE.ID, NOMENCLATURE.CODE, NOMENCLATURE.NAME)
                .from(NOMENCLATURE)
                .where(NOMENCLATURE.ID.`in`(distinctNomIds))
                .fetch()
                .associate { it.get(NOMENCLATURE.ID)!! to (it.get(NOMENCLATURE.CODE)!! to it.get(NOMENCLATURE.NAME)!!) }
        }

        return ExpectedReceiptEnrichments(orders, unitsByOrderLine, nomenclature)
    }

    fun findDraftReceiptForOrder(orderId: Long, warehouseId: Long): ReceiptsRecord? =
        dsl.selectFrom(RECEIPTS)
            .where(RECEIPTS.PURCHASE_ORDER_ID.eq(orderId))
            .and(RECEIPTS.WAREHOUSE_ID.eq(warehouseId))
            .and(RECEIPTS.STATUS.eq("DRAFT"))
            .orderBy(RECEIPTS.ID.asc())
            .limit(1)
            .forUpdate()
            .fetchOne()

    fun prepareDraftLinesForQuickReceive(receiptId: Long, locationId: Long) {
        dsl.update(RECEIPT_LINES)
            .set(RECEIPT_LINES.RECEIVED_QUANTITY, RECEIPT_LINES.EXPECTED_QUANTITY)
            .set(RECEIPT_LINES.LOCATION_ID, locationId)
            .setNull(RECEIPT_LINES.DISCREPANCY_KIND)
            .setNull(RECEIPT_LINES.DISCREPANCY_QUANTITY)
            .setNull(RECEIPT_LINES.DISCREPANCY_REASON)
            .where(RECEIPT_LINES.RECEIPT_ID.eq(receiptId))
            .execute()
    }

    fun findDemandLineIdsForOrderLines(orderLineIds: Collection<Long>): List<Long> {
        val ids = orderLineIds.distinct()
        if (ids.isEmpty()) return emptyList()
        return dsl.selectDistinct(SUPPLIER_OFFER_LINES.DEMAND_LINE_ID)
            .from(PURCHASE_ORDER_LINES)
            .join(SUPPLIER_OFFER_LINES).on(SUPPLIER_OFFER_LINES.ID.eq(PURCHASE_ORDER_LINES.OFFER_LINE_ID))
            .where(PURCHASE_ORDER_LINES.ID.`in`(ids))
            .fetch(SUPPLIER_OFFER_LINES.DEMAND_LINE_ID)
    }

    fun findDemandIdsForOrders(orderIds: Collection<Long>): List<Long> {
        val ids = orderIds.distinct()
        if (ids.isEmpty()) return emptyList()
        return dsl.selectDistinct(PURCHASE_ORDER_DEMANDS.DEMAND_ID)
            .from(PURCHASE_ORDER_DEMANDS)
            .where(PURCHASE_ORDER_DEMANDS.PURCHASE_ORDER_ID.`in`(ids))
            .fetch(PURCHASE_ORDER_DEMANDS.DEMAND_ID)
    }

    fun lockDemandLineById(id: Long): DemandLinesRecord? =
        dsl.selectFrom(DEMAND_LINES).where(DEMAND_LINES.ID.eq(id)).forUpdate().fetchOne()

    fun lockDemandById(id: Long): DemandsRecord? =
        dsl.selectFrom(DEMANDS).where(DEMANDS.ID.eq(id)).forUpdate().fetchOne()

    fun listDemandLinesByDemand(demandId: Long): List<DemandLinesRecord> =
        dsl.selectFrom(DEMAND_LINES)
            .where(DEMAND_LINES.DEMAND_ID.eq(demandId))
            .orderBy(DEMAND_LINES.LINE_NO.asc())
            .fetch()

    fun acceptedQuantityForDemandLine(demandLineId: Long): BigDecimal =
        dsl.fetchOne(
            """
            SELECT COALESCE(SUM(rl.received_quantity), 0) AS total
            FROM receipt_lines rl
                     JOIN receipts r ON r.id = rl.receipt_id
                     JOIN purchase_order_lines pol ON pol.id = rl.purchase_order_line_id
                     JOIN supplier_offer_lines sol ON sol.id = pol.offer_line_id
            WHERE r.status = 'POSTED'
              AND sol.demand_line_id = ?
            """.trimIndent(),
            demandLineId
        )?.get("total", BigDecimal::class.java) ?: BigDecimal.ZERO

    fun updateReceiptLineAutoReserved(receiptLineId: Long, quantity: BigDecimal): Int =
        dsl.update(RECEIPT_LINES)
            .set(RECEIPT_LINES.AUTO_RESERVED_QUANTITY, quantity)
            .where(RECEIPT_LINES.ID.eq(receiptLineId))
            .execute()

    data class OrderLineProjectContext(
        val projectId: Long,
        val projectNumber: String,
        val projectName: String,
        val initiatorId: Long
    )

    fun findOrderLineProjectContext(orderLineIds: Collection<Long>): Map<Long, OrderLineProjectContext> {
        val ids = orderLineIds.distinct()
        if (ids.isEmpty()) return emptyMap()
        return dsl.select(
            PURCHASE_ORDER_LINES.ID,
            DEMANDS.PROJECT_ID,
            DEMANDS.INITIATOR_ID,
            PROJECTS.NUMBER,
            PROJECTS.NAME
        )
            .from(PURCHASE_ORDER_LINES)
            .join(SUPPLIER_OFFER_LINES).on(SUPPLIER_OFFER_LINES.ID.eq(PURCHASE_ORDER_LINES.OFFER_LINE_ID))
            .join(DEMAND_LINES).on(DEMAND_LINES.ID.eq(SUPPLIER_OFFER_LINES.DEMAND_LINE_ID))
            .join(DEMANDS).on(DEMANDS.ID.eq(DEMAND_LINES.DEMAND_ID))
            .join(PROJECTS).on(PROJECTS.ID.eq(DEMANDS.PROJECT_ID))
            .where(PURCHASE_ORDER_LINES.ID.`in`(ids))
            .and(DEMANDS.PROJECT_ID.isNotNull)
            .and(DEMANDS.STATUS.ne("CANCELLED"))
            .fetch()
            .associate { r ->
                r.get(PURCHASE_ORDER_LINES.ID)!! to OrderLineProjectContext(
                    projectId = r.get(DEMANDS.PROJECT_ID)!!,
                    projectNumber = r.get(PROJECTS.NUMBER)!!,
                    projectName = r.get(PROJECTS.NAME)!!,
                    initiatorId = r.get(DEMANDS.INITIATOR_ID)!!
                )
            }
    }

    fun findOrderProjectContext(orderId: Long): OrderLineProjectContext? =
        dsl.select(
            DEMANDS.PROJECT_ID,
            DEMANDS.INITIATOR_ID,
            PROJECTS.NUMBER,
            PROJECTS.NAME
        )
            .from(PURCHASE_ORDER_DEMANDS)
            .join(DEMANDS).on(DEMANDS.ID.eq(PURCHASE_ORDER_DEMANDS.DEMAND_ID))
            .join(PROJECTS).on(PROJECTS.ID.eq(DEMANDS.PROJECT_ID))
            .where(PURCHASE_ORDER_DEMANDS.PURCHASE_ORDER_ID.eq(orderId))
            .and(DEMANDS.PROJECT_ID.isNotNull)
            .and(DEMANDS.STATUS.ne("CANCELLED"))
            .orderBy(DEMANDS.ID.asc())
            .limit(1)
            .fetchOne { r ->
                OrderLineProjectContext(
                    projectId = r.get(DEMANDS.PROJECT_ID)!!,
                    projectNumber = r.get(PROJECTS.NUMBER)!!,
                    projectName = r.get(PROJECTS.NAME)!!,
                    initiatorId = r.get(DEMANDS.INITIATOR_ID)!!
                )
            }

    fun projectRequiredTotal(projectId: Long, nomenclatureId: Long): BigDecimal =
        dsl.fetchOne(
            """
            SELECT COALESCE(SUM(dl.quantity), 0) AS total
            FROM demand_lines dl
                     JOIN demands d ON d.id = dl.demand_id
            WHERE d.project_id = ?
              AND d.status <> 'CANCELLED'
              AND dl.status <> 'CANCELLED'
              AND dl.nomenclature_id = ?
            """.trimIndent(),
            projectId, nomenclatureId
        )?.get("total", BigDecimal::class.java) ?: BigDecimal.ZERO

    fun projectReservedTotal(projectId: Long, nomenclatureId: Long): BigDecimal =
        dsl.fetchOne(
            """
            SELECT COALESCE(SUM(r.quantity), 0) AS total
            FROM reservations r
            WHERE r.project_id = ?
              AND r.nomenclature_id = ?
              AND r.status IN ('ACTIVE', 'FULFILLED')
            """.trimIndent(),
            projectId, nomenclatureId
        )?.get("total", BigDecimal::class.java) ?: BigDecimal.ZERO

    fun updateDemandLineCoverage(
        lineId: Long,
        coveredQuantity: BigDecimal,
        status: String
    ): Int =
        dsl.update(DEMAND_LINES)
            .set(DEMAND_LINES.COVERED_QUANTITY, coveredQuantity)
            .set(DEMAND_LINES.STATUS, status)
            .set(DEMAND_LINES.VERSION, DEMAND_LINES.VERSION.plus(1))
            .where(DEMAND_LINES.ID.eq(lineId))
            .execute()

    fun insertDemandLineHistory(
        demandLineId: Long,
        action: String,
        quantityBefore: BigDecimal?,
        quantityAfter: BigDecimal?,
        fromStatus: String?,
        toStatus: String?,
        reason: String?,
        changedBy: Long
    ) {
        dsl.insertInto(DEMAND_LINE_HISTORY)
            .set(DEMAND_LINE_HISTORY.DEMAND_LINE_ID, demandLineId)
            .set(DEMAND_LINE_HISTORY.ACTION, action)
            .set(DEMAND_LINE_HISTORY.QUANTITY_BEFORE, quantityBefore)
            .set(DEMAND_LINE_HISTORY.QUANTITY_AFTER, quantityAfter)
            .set(DEMAND_LINE_HISTORY.FROM_STATUS, fromStatus)
            .set(DEMAND_LINE_HISTORY.TO_STATUS, toStatus)
            .set(DEMAND_LINE_HISTORY.REASON, reason)
            .set(DEMAND_LINE_HISTORY.CHANGED_BY, changedBy)
            .execute()
    }

    fun updateDemandStatusAfterReceipt(
        demandId: Long,
        newStatus: String,
        fallbackApprovedBy: Long
    ): Int =
        dsl.update(DEMANDS)
            .set(DEMANDS.STATUS, newStatus)
            .set(DEMANDS.APPROVED_BY, org.jooq.impl.DSL.coalesce(DEMANDS.APPROVED_BY, fallbackApprovedBy))
            .set(DEMANDS.APPROVED_AT, org.jooq.impl.DSL.coalesce(DEMANDS.APPROVED_AT, OffsetDateTime.now()))
            .set(DEMANDS.VERSION, DEMANDS.VERSION.plus(1))
            .where(DEMANDS.ID.eq(demandId))
            .execute()

    fun insertDemandHistory(
        demandId: Long,
        action: String,
        fromStatus: String?,
        toStatus: String?,
        reason: String?,
        changedBy: Long
    ) {
        dsl.insertInto(DEMAND_HISTORY)
            .set(DEMAND_HISTORY.DEMAND_ID, demandId)
            .set(DEMAND_HISTORY.ACTION, action)
            .set(DEMAND_HISTORY.FROM_STATUS, fromStatus)
            .set(DEMAND_HISTORY.TO_STATUS, toStatus)
            .set(DEMAND_HISTORY.REASON, reason)
            .set(DEMAND_HISTORY.CHANGED_BY, changedBy)
            .execute()
    }

    fun findOrderProcurementRecipients(demandId: Long, orderIds: Collection<Long>): List<Long> {
        val ids = orderIds.distinct()
        val orderResponsibles = if (ids.isEmpty()) {
            emptyList()
        } else {
            dsl.select(PURCHASE_ORDERS.RESPONSIBLE_ID, SUPPLIER_OFFERS.REQUESTED_BY)
                .from(PURCHASE_ORDERS)
                .leftJoin(SUPPLIER_OFFERS).on(SUPPLIER_OFFERS.ID.eq(PURCHASE_ORDERS.OFFER_ID))
                .where(PURCHASE_ORDERS.ID.`in`(ids))
                .fetch()
                .flatMap { r -> listOfNotNull(r.get(PURCHASE_ORDERS.RESPONSIBLE_ID), r.get(SUPPLIER_OFFERS.REQUESTED_BY)) }
        }
        val selectedOfferAuthor = dsl.select(SUPPLIER_OFFERS.REQUESTED_BY)
            .from(SUPPLIER_OFFERS)
            .where(SUPPLIER_OFFERS.DEMAND_ID.eq(demandId))
            .and(SUPPLIER_OFFERS.STATUS.eq("SELECTED"))
            .fetch(SUPPLIER_OFFERS.REQUESTED_BY)
        return (orderResponsibles + selectedOfferAuthor).distinct()
    }

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
            dsl.fetch(sql.toString(), orderId).toList()
        } else {
            dsl.fetch(sql.toString()).toList()
        }
    }

    /**
     * Принятое по позиции заказа: сумма проведённых приёмок.
     * `excludeReceiptId` исключает текущий документ — так пересчитывается
     * остаток при проведении, не считая приёмку дважды.
     */
    fun acceptedQuantityForOrderLine(orderLineId: Long, excludeReceiptId: Long? = null): BigDecimal {
        val exclusion = if (excludeReceiptId == null) "" else " AND r.id <> ?"
        val parameters = if (excludeReceiptId == null) {
            arrayOf(orderLineId)
        } else {
            arrayOf(orderLineId, excludeReceiptId)
        }
        return dsl.fetchOne(
            """
            SELECT COALESCE(SUM(rl.received_quantity), 0) AS total
            FROM receipt_lines rl
                     JOIN receipts r ON r.id = rl.receipt_id
            WHERE rl.purchase_order_line_id = ?
              AND r.status = 'POSTED'
              $exclusion
            """.trimIndent(),
            *parameters
        )?.get("total", BigDecimal::class.java) ?: BigDecimal.ZERO
    }

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

    fun listReceipts(
        warehouseId: Long?,
        purchaseOrderId: Long?,
        status: String?
    ): List<ReceiptsRecord> {
        val conditions = mutableListOf<Condition>()
        warehouseId?.let { conditions += RECEIPTS.WAREHOUSE_ID.eq(it) }
        purchaseOrderId?.let { conditions += RECEIPTS.PURCHASE_ORDER_ID.eq(it) }
        status?.let { conditions += RECEIPTS.STATUS.eq(it) }
        return dsl.selectFrom(RECEIPTS)
            .where(conditions)
            .orderBy(RECEIPTS.ID.desc())
            .limit(200)
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

    fun listReceiptLinesFor(receiptIds: List<Long>): List<ReceiptLinesRecord> {
        if (receiptIds.isEmpty()) return emptyList()
        return dsl.selectFrom(RECEIPT_LINES)
            .where(RECEIPT_LINES.RECEIPT_ID.`in`(receiptIds))
            .orderBy(RECEIPT_LINES.RECEIPT_ID.asc(), RECEIPT_LINES.LINE_NO.asc())
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
