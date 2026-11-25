package org.example.project.service

import org.example.project.dto.*
import org.example.project.exception.ConflictException
import org.example.project.exception.NotFoundException
import org.example.project.exception.ValidationException
import org.example.project.generated.jooq.tables.records.NomenclatureBarcodesRecord
import org.example.project.generated.jooq.tables.records.NomenclatureRecord
import org.example.project.generated.jooq.tables.records.ReservationHistoryRecord
import org.example.project.generated.jooq.tables.records.ReservationsRecord
import org.example.project.generated.jooq.tables.records.StorageLocationsRecord
import org.example.project.generated.jooq.tables.records.WarehousesRecord
import org.example.project.model.CurrentUser
import org.example.project.repository.WmsRepository
import org.jooq.DSLContext
import org.jooq.TransactionalCallable
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

class WmsService(
    private val wmsRepository: WmsRepository,
    private val auditService: AuditService,
    private val dsl: DSLContext
) {

    // Справочники

    fun createWarehouse(request: CreateWarehouseRequest, currentUser: CurrentUser): WarehouseDto {
        currentUser.requirePermission("wms", "manage")
        if (request.code.isBlank()) {
            throw ValidationException("Warehouse code is required", mapOf("code" to "required"))
        }
        if (request.name.isBlank()) {
            throw ValidationException("Warehouse name is required", mapOf("name" to "required"))
        }
        val code = request.code.trim()
        if (wmsRepository.existsWarehouseByCode(code)) {
            throw ConflictException("Warehouse code already exists: $code")
        }
        return dsl.transactionResult(TransactionalCallable {
            val warehouse = wmsRepository.createWarehouse(
                code = code,
                name = request.name.trim(),
                address = request.address?.trim(),
                createdBy = currentUser.userId
            )
            auditService.log(
                currentUser.userId, "CREATE", "WAREHOUSE", warehouse.id,
                mapOf("code" to warehouse.code, "name" to warehouse.name)
            )
            warehouse.toDto()
        })
    }

    fun listWarehouses(currentUser: CurrentUser): List<WarehouseDto> {
        currentUser.requirePermission("wms", "read")
        return wmsRepository.listWarehouses().map { it.toDto() }
    }

    fun createLocation(request: CreateStorageLocationRequest, currentUser: CurrentUser): StorageLocationDto {
        currentUser.requirePermission("wms", "manage")
        if (request.code.isBlank()) {
            throw ValidationException("Location code is required", mapOf("code" to "required"))
        }
        if (request.name.isBlank()) {
            throw ValidationException("Location name is required", mapOf("name" to "required"))
        }
        if (wmsRepository.findWarehouseById(request.warehouseId) == null) {
            throw NotFoundException("warehouse", request.warehouseId)
        }
        val parentId = request.parentId
        if (parentId != null) {
            val parent = wmsRepository.findLocationById(parentId)
                ?: throw NotFoundException("storage_location", parentId)
            if (parent.warehouseId != request.warehouseId) {
                throw ValidationException(
                    "Parent location belongs to another warehouse",
                    mapOf("parentId" to "other_warehouse")
                )
            }
        }
        val capacity = request.capacity?.let { parseQuantity(it, "capacity") }
        val code = request.code.trim()
        if (wmsRepository.existsLocationByCode(request.warehouseId, code)) {
            throw ConflictException("Location code already exists in warehouse: $code")
        }
        return dsl.transactionResult(TransactionalCallable {
            val location = wmsRepository.createLocation(
                warehouseId = request.warehouseId,
                parentId = request.parentId,
                code = code,
                name = request.name.trim(),
                kind = request.kind.name,
                purpose = request.purpose.name,
                capacity = capacity,
                createdBy = currentUser.userId
            )
            auditService.log(
                currentUser.userId, "CREATE", "STORAGE_LOCATION", location.id,
                mapOf("warehouseId" to location.warehouseId, "code" to location.code, "purpose" to location.purpose)
            )
            location.toDto()
        })
    }

    fun listLocations(warehouseId: Long, currentUser: CurrentUser): List<StorageLocationDto> {
        currentUser.requirePermission("wms", "read")
        if (wmsRepository.findWarehouseById(warehouseId) == null) {
            throw NotFoundException("warehouse", warehouseId)
        }
        return wmsRepository.listLocationsByWarehouse(warehouseId).map { it.toDto() }
    }

    fun createNomenclature(request: CreateNomenclatureRequest, currentUser: CurrentUser): NomenclatureDto {
        currentUser.requirePermission("wms", "manage")
        if (request.code.isBlank()) {
            throw ValidationException("Nomenclature code is required", mapOf("code" to "required"))
        }
        if (request.name.isBlank()) {
            throw ValidationException("Nomenclature name is required", mapOf("name" to "required"))
        }
        if (request.unit.isBlank()) {
            throw ValidationException("Unit is required", mapOf("unit" to "required"))
        }
        val barcodes = request.barcodes.map { it.barcode.trim() }
        if (barcodes.any { it.isEmpty() }) {
            throw ValidationException("Barcode must not be blank", mapOf("barcodes" to "blank"))
        }
        if (barcodes.distinct().size != barcodes.size) {
            throw ValidationException("Duplicate barcodes in request", mapOf("barcodes" to "duplicate"))
        }
        val code = request.code.trim()
        if (wmsRepository.existsNomenclatureByCode(code)) {
            throw ConflictException("Nomenclature code already exists: $code")
        }
        return dsl.transactionResult(TransactionalCallable {
            val nomenclature = wmsRepository.createNomenclature(
                code = code,
                name = request.name.trim(),
                description = request.description?.trim(),
                type = request.type.name,
                unit = request.unit.trim(),
                lotTracked = request.lotTracked,
                serialTracked = request.serialTracked,
                minStock = parseNonNegative(request.minStock, "minStock"),
                safetyStock = parseNonNegative(request.safetyStock, "safetyStock"),
                leadTimeDays = request.leadTimeDays.coerceAtLeast(0),
                createdBy = currentUser.userId
            )
            request.barcodes.forEach { barcode ->
                wmsRepository.insertBarcode(
                    nomenclatureId = nomenclature.id,
                    barcode = barcode.barcode.trim(),
                    packageType = barcode.packageType.name,
                    quantityInPackage = parseQuantity(barcode.quantityInPackage, "quantityInPackage"),
                    createdBy = currentUser.userId
                )
            }
            auditService.log(
                currentUser.userId, "CREATE", "NOMENCLATURE", nomenclature.id,
                mapOf("code" to nomenclature.code, "type" to nomenclature.type)
            )
            nomenclature.toDto(loadBarcodes(listOf(nomenclature.id)))
        })
    }

    fun listNomenclature(currentUser: CurrentUser): List<NomenclatureDto> {
        currentUser.requirePermission("wms", "read")
        val records = wmsRepository.listNomenclature()
        val barcodes = loadBarcodes(records.map { it.id })
        return records.map { it.toDto(barcodes) }
    }

    fun getNomenclatureDetails(id: Long, currentUser: CurrentUser): NomenclatureDetailsDto {
        currentUser.requirePermission("wms", "read")
        val nomenclature = wmsRepository.findNomenclatureById(id)
            ?: throw NotFoundException("nomenclature", id)
        val stock = wmsRepository.selectStock(null, id)
        return NomenclatureDetailsDto(
            item = nomenclature.toDto(loadBarcodes(listOf(id))),
            physicalQty = stock.fold(BigDecimal.ZERO) { total, row -> total.add(row.balance.physicalQty) }.toPlainString(),
            reservedQty = stock.fold(BigDecimal.ZERO) { total, row -> total.add(row.balance.reservedQty) }.toPlainString(),
            availableQty = stock
                .filter { it.locationPurpose !in setOf("QUARANTINE", "DEFECT", "TEMPORARY") }
                .fold(BigDecimal.ZERO) { total, row -> total.add(row.balance.availableQty) }.toPlainString(),
            stock = stock.map { it.toDto() },
            lots = wmsRepository.listLots(id).map { lot ->
                LotDto(
                    id = lot.id,
                    nomenclatureId = lot.nomenclatureId,
                    lotNumber = lot.lotNumber,
                    manufactureDate = lot.manufactureDate?.toString(),
                    expiryDate = lot.expiryDate?.toString(),
                    createdAt = lot.createdAt?.toString()
                )
            },
            serials = wmsRepository.listSerials(id).map { serial ->
                SerialItemDto(
                    id = serial.id,
                    nomenclatureId = serial.nomenclatureId,
                    lotId = serial.lotId,
                    serialNumber = serial.serialNumber,
                    createdAt = serial.createdAt?.toString()
                )
            },
            movements = wmsRepository.listMovements(id).let { rows ->
                val numbers = wmsRepository.documentNumbers(rows.map { it.movement.documentType to it.movement.documentId })
                rows.map { row ->
                val movement = row.movement
                StockMovementDto(
                    id = movement.id,
                    type = StockMovementType.valueOf(movement.movementType),
                    nomenclatureId = movement.nomenclatureId,
                    lotNumber = row.lotNumber,
                    serialNumber = row.serialNumber,
                    fromLocationCode = row.fromLocationCode,
                    toLocationCode = row.toLocationCode,
                    quantity = movement.quantity.toPlainString(),
                    documentType = movement.documentType,
                    documentId = movement.documentId,
                    note = movement.note,
                    createdBy = movement.createdBy,
                    createdByName = row.createdByName,
                    createdAt = movement.createdAt?.toString(),
                    documentNumber = numbers[movement.documentType.uppercase() to movement.documentId]
                )
                }
            }
        )
    }

    fun archiveNomenclature(id: Long, currentUser: CurrentUser): NomenclatureDto {
        currentUser.requirePermission("wms", "manage")
        if (wmsRepository.findNomenclatureById(id) == null) {
            throw NotFoundException("nomenclature", id)
        }
        val activeRefs = wmsRepository.findActiveNomenclatureReferences(id)
        if (activeRefs.isNotEmpty()) {
            throw ConflictException(
                "Нельзя архивировать: " + activeRefs.joinToString("; ") { nomenclatureReferenceLabel(it) }
            )
        }
        return dsl.transactionResult(TransactionalCallable {
            val archived = wmsRepository.archiveNomenclature(id)
                ?: throw ConflictException("Nomenclature is already archived")
            auditService.log(
                currentUser.userId, "ARCHIVE", "NOMENCLATURE", id,
                mapOf("code" to archived.code)
            )
            archived.toDto(loadBarcodes(listOf(id)))
        })
    }

    fun updateNomenclatureThresholds(
        id: Long,
        request: UpdateNomenclatureThresholdsRequest,
        currentUser: CurrentUser
    ): NomenclatureDto {
        currentUser.requirePermission("wms", "manage")
        if (wmsRepository.findNomenclatureById(id) == null) throw NotFoundException("nomenclature", id)
        val minStock = parseNonNegative(request.minStock, "minStock")
        val safetyStock = parseNonNegative(request.safetyStock, "safetyStock")
        if (request.leadTimeDays < 0) {
            throw ValidationException("leadTimeDays must be non-negative", mapOf("leadTimeDays" to "negative"))
        }
        return dsl.transactionResult(TransactionalCallable {
            val updated = wmsRepository.updateNomenclatureThresholds(
                id, minStock, safetyStock, request.leadTimeDays, request.version
            ) ?: throw ConflictException("Nomenclature version conflict")
            auditService.log(currentUser.userId, "UPDATE_THRESHOLDS", "NOMENCLATURE", id)
            updated.toDto(loadBarcodes(listOf(id)))
        })
    }

    fun findByBarcode(code: String, currentUser: CurrentUser): NomenclatureDto {
        currentUser.requirePermission("wms", "read")
        val clean = code.trim()
        if (clean.isEmpty()) throw ValidationException("Barcode is required", mapOf("barcode" to "required"))
        val record = wmsRepository.findNomenclatureByBarcode(clean) ?: throw NotFoundException("barcode", clean)
        return record.toDto(loadBarcodes(listOf(record.id)))
    }

    fun addBarcode(id: Long, request: CreateNomenclatureBarcodeRequest, currentUser: CurrentUser): NomenclatureDto {
        currentUser.requirePermission("wms", "manage")
        val record = wmsRepository.findNomenclatureById(id) ?: throw NotFoundException("nomenclature", id)
        val clean = request.barcode.trim()
        if (clean.isEmpty()) throw ValidationException("Barcode is required", mapOf("barcode" to "required"))
        if (wmsRepository.findNomenclatureByBarcode(clean) != null) {
            throw ConflictException("Barcode already exists: $clean")
        }
        val qty = parseQuantity(request.quantityInPackage, "quantityInPackage")
        dsl.transaction { _ ->
            wmsRepository.insertBarcode(id, clean, request.packageType.name, qty, currentUser.userId)
            auditService.log(currentUser.userId, "ADD_BARCODE", "NOMENCLATURE", id, mapOf("barcode" to clean))
        }
        return record.toDto(loadBarcodes(listOf(id)))
    }

    fun deleteBarcode(id: Long, barcodeId: Long, currentUser: CurrentUser): NomenclatureDto {
        currentUser.requirePermission("wms", "manage")
        val record = wmsRepository.findNomenclatureById(id) ?: throw NotFoundException("nomenclature", id)
        if (!wmsRepository.deleteBarcode(id, barcodeId)) throw NotFoundException("barcode", barcodeId)
        auditService.log(currentUser.userId, "DELETE_BARCODE", "NOMENCLATURE", id, mapOf("barcodeId" to barcodeId))
        return record.toDto(loadBarcodes(listOf(id)))
    }

    fun listUnitConversions(id: Long, currentUser: CurrentUser): List<NomenclatureUnitConversionDto> {
        currentUser.requirePermission("wms", "read")
        if (wmsRepository.findNomenclatureById(id) == null) throw NotFoundException("nomenclature", id)
        return wmsRepository.listUnitConversions(id).map {
            NomenclatureUnitConversionDto(
                id = it.id,
                nomenclatureId = it.nomenclatureId,
                fromUnit = it.fromUnit,
                toUnit = it.toUnit,
                factor = it.factor.stripTrailingZeros().toPlainString()
            )
        }
    }

    fun createUnitConversion(
        id: Long,
        request: CreateUnitConversionRequest,
        currentUser: CurrentUser
    ): NomenclatureUnitConversionDto {
        currentUser.requirePermission("wms", "manage")
        if (wmsRepository.findNomenclatureById(id) == null) throw NotFoundException("nomenclature", id)
        if (request.fromUnit.isBlank() || request.toUnit.isBlank()) {
            throw ValidationException("Units are required")
        }
        val fromUnit = request.fromUnit.trim()
        val toUnit = request.toUnit.trim()
        if (fromUnit == toUnit) {
            throw ValidationException("Единицы пересчёта должны различаться", mapOf("toUnit" to "same_as_from"))
        }
        val factor = parseQuantity(request.factor, "factor")
        if (wmsRepository.unitConversionExists(id, fromUnit, toUnit)) {
            throw ConflictException("Пересчёт $fromUnit → $toUnit уже задан")
        }
        val rec = dsl.transactionResult(TransactionalCallable {
            val created = wmsRepository.createUnitConversion(id, fromUnit, toUnit, factor, currentUser.userId)
            auditService.log(
                currentUser.userId, "ADD_UNIT_CONVERSION", "NOMENCLATURE", id,
                mapOf("fromUnit" to fromUnit, "toUnit" to toUnit, "factor" to factor.stripTrailingZeros().toPlainString())
            )
            created
        })
        return NomenclatureUnitConversionDto(
            id = rec.id,
            nomenclatureId = rec.nomenclatureId,
            fromUnit = rec.fromUnit,
            toUnit = rec.toUnit,
            factor = rec.factor.stripTrailingZeros().toPlainString()
        )
    }

    fun listAnalogs(id: Long, currentUser: CurrentUser): List<NomenclatureAnalogDto> {
        currentUser.requirePermission("wms", "read")
        if (wmsRepository.findNomenclatureById(id) == null) throw NotFoundException("nomenclature", id)
        return wmsRepository.listAnalogs(id).map { row ->
            NomenclatureAnalogDto(
                id = row.get(org.example.project.generated.jooq.tables.NomenclatureAnalogs.NOMENCLATURE_ANALOGS.ID)!!,
                nomenclatureId = row.get(org.example.project.generated.jooq.tables.NomenclatureAnalogs.NOMENCLATURE_ANALOGS.NOMENCLATURE_ID)!!,
                analogNomenclatureId = row.get(org.example.project.generated.jooq.tables.NomenclatureAnalogs.NOMENCLATURE_ANALOGS.ANALOG_NOMENCLATURE_ID)!!,
                analogCode = row.get(org.example.project.generated.jooq.tables.Nomenclature.NOMENCLATURE.CODE)!!,
                analogName = row.get(org.example.project.generated.jooq.tables.Nomenclature.NOMENCLATURE.NAME)!!,
                conversionFactor = row.get(org.example.project.generated.jooq.tables.NomenclatureAnalogs.NOMENCLATURE_ANALOGS.CONVERSION_FACTOR)!!.stripTrailingZeros().toPlainString(),
                applicabilityCondition = row.get(org.example.project.generated.jooq.tables.NomenclatureAnalogs.NOMENCLATURE_ANALOGS.APPLICABILITY_CONDITION),
                priority = row.get(org.example.project.generated.jooq.tables.NomenclatureAnalogs.NOMENCLATURE_ANALOGS.PRIORITY)!!,
                active = row.get(org.example.project.generated.jooq.tables.NomenclatureAnalogs.NOMENCLATURE_ANALOGS.ACTIVE)!!,
                requiresApproval = row.get(org.example.project.generated.jooq.tables.NomenclatureAnalogs.NOMENCLATURE_ANALOGS.REQUIRES_APPROVAL) ?: false
            )
        }
    }

    fun deleteAnalog(id: Long, analogId: Long, currentUser: CurrentUser): List<NomenclatureAnalogDto> {
        currentUser.requirePermission("wms", "manage")
        if (wmsRepository.findNomenclatureById(id) == null) throw NotFoundException("nomenclature", id)
        dsl.transaction { _ ->
            if (!wmsRepository.deleteAnalog(id, analogId)) throw NotFoundException("analog", analogId)
            auditService.log(currentUser.userId, "DELETE_ANALOG", "NOMENCLATURE", id, mapOf("analogId" to analogId))
        }
        return listAnalogs(id, currentUser)
    }

    fun deleteUnitConversion(id: Long, conversionId: Long, currentUser: CurrentUser): List<NomenclatureUnitConversionDto> {
        currentUser.requirePermission("wms", "manage")
        if (wmsRepository.findNomenclatureById(id) == null) throw NotFoundException("nomenclature", id)
        dsl.transaction { _ ->
            if (!wmsRepository.deleteUnitConversion(id, conversionId)) throw NotFoundException("unit_conversion", conversionId)
            auditService.log(
                currentUser.userId, "DELETE_UNIT_CONVERSION", "NOMENCLATURE", id,
                mapOf("conversionId" to conversionId)
            )
        }
        return listUnitConversions(id, currentUser)
    }

    fun createAnalog(
        id: Long,
        request: CreateNomenclatureAnalogRequest,
        currentUser: CurrentUser
    ): List<NomenclatureAnalogDto> {
        currentUser.requirePermission("wms", "manage")
        if (wmsRepository.findNomenclatureById(id) == null) throw NotFoundException("nomenclature", id)
        if (request.analogNomenclatureId == id) throw ValidationException("Analog cannot be the same nomenclature")
        val analog = wmsRepository.findNomenclatureById(request.analogNomenclatureId)
            ?: throw NotFoundException("nomenclature", request.analogNomenclatureId)
        if (analog.archived == true) {
            throw ValidationException("Архивную позицию нельзя указать аналогом", mapOf("analogNomenclatureId" to "archived"))
        }
        if (wmsRepository.analogExists(id, request.analogNomenclatureId)) {
            throw ConflictException("Аналог ${analog.code} уже указан")
        }
        val factor = parseQuantity(request.conversionFactor, "conversionFactor")
        dsl.transaction { _ ->
            wmsRepository.createAnalog(
                id, request.analogNomenclatureId, factor, request.applicabilityCondition?.trim()?.takeIf { it.isNotEmpty() },
                request.priority.coerceAtLeast(1), currentUser.userId, request.requiresApproval
            )
            auditService.log(
                currentUser.userId, "ADD_ANALOG", "NOMENCLATURE", id,
                mapOf("analogCode" to analog.code, "requiresApproval" to request.requiresApproval.toString())
            )
        }
        return listAnalogs(id, currentUser)
    }

    private fun parseNonNegative(raw: String, field: String): BigDecimal {
        val value = raw.trim().replace(" ", "").replace(',', '.').toBigDecimalOrNull()
            ?: throw ValidationException("Invalid number for $field", mapOf(field to "invalid"))
        if (value < BigDecimal.ZERO) throw ValidationException("$field must be >= 0", mapOf(field to "negative"))
        return value
    }

    // Остатки

    fun getStock(warehouseId: Long?, nomenclatureId: Long?, currentUser: CurrentUser, locationId: Long? = null): List<StockBalanceDto> {
        currentUser.requirePermission("wms", "read")
        val tr = org.example.project.generated.jooq.tables.Transfers.TRANSFERS
        val inTransitMap = dsl.select(tr.TO_WAREHOUSE_ID, tr.NOMENCLATURE_ID, org.jooq.impl.DSL.sum(tr.QUANTITY))
            .from(tr)
            .where(tr.STATUS.eq("IN_TRANSIT"))
            .groupBy(tr.TO_WAREHOUSE_ID, tr.NOMENCLATURE_ID)
            .fetch()
            .associate { row ->
                (row.get(tr.TO_WAREHOUSE_ID)!! to row.get(tr.NOMENCLATURE_ID)!!) to
                    (row.get(2, java.math.BigDecimal::class.java) ?: java.math.BigDecimal.ZERO)
            }
        return wmsRepository.selectStock(warehouseId, nomenclatureId, locationId).map { row ->
            val inTransit = inTransitMap[row.balance.warehouseId to row.balance.nomenclatureId] ?: java.math.BigDecimal.ZERO
            row.toDto().copy(inTransitQty = inTransit.stripTrailingZeros().toPlainString())
        }
    }

    fun listMovements(
        warehouseId: Long?,
        nomenclatureId: Long?,
        type: String?,
        limit: Int,
        currentUser: CurrentUser
    ): List<StockMovementDto> {
        currentUser.requirePermission("wms", "read")
        val sm = org.example.project.generated.jooq.tables.StockMovement.STOCK_MOVEMENT
        val slFrom = org.example.project.generated.jooq.tables.StorageLocations.STORAGE_LOCATIONS.`as`("sl_from")
        val slTo = org.example.project.generated.jooq.tables.StorageLocations.STORAGE_LOCATIONS.`as`("sl_to")
        val u = org.example.project.generated.jooq.tables.Users.USERS
        val lot = org.example.project.generated.jooq.tables.Lots.LOTS
        val ser = org.example.project.generated.jooq.tables.SerialItems.SERIAL_ITEMS
        var cond: org.jooq.Condition = org.jooq.impl.DSL.trueCondition()
        if (warehouseId != null) {
            cond = cond.and(slFrom.WAREHOUSE_ID.eq(warehouseId).or(slTo.WAREHOUSE_ID.eq(warehouseId)))
        }
        if (nomenclatureId != null) {
            cond = cond.and(sm.NOMENCLATURE_ID.eq(nomenclatureId))
        }
        type?.trim()?.takeIf { it.isNotEmpty() }?.let {
            cond = cond.and(sm.MOVEMENT_TYPE.eq(it))
        }
        return dsl.select(
            sm.ID, sm.MOVEMENT_TYPE, sm.NOMENCLATURE_ID, lot.LOT_NUMBER, ser.SERIAL_NUMBER,
            slFrom.CODE, slTo.CODE, sm.QUANTITY, sm.DOCUMENT_TYPE, sm.DOCUMENT_ID,
            sm.NOTE, sm.CREATED_BY, u.FULL_NAME, sm.CREATED_AT
        )
            .from(sm)
            .leftJoin(lot).on(lot.ID.eq(sm.LOT_ID))
            .leftJoin(ser).on(ser.ID.eq(sm.SERIAL_ID))
            .leftJoin(slFrom).on(slFrom.ID.eq(sm.FROM_LOCATION_ID))
            .leftJoin(slTo).on(slTo.ID.eq(sm.TO_LOCATION_ID))
            .join(u).on(u.ID.eq(sm.CREATED_BY))
            .where(cond)
            .orderBy(sm.ID.desc())
            .limit(limit.coerceIn(1, 500))
            .fetch { r ->
                StockMovementDto(
                    id = r.get(sm.ID)!!,
                    type = StockMovementType.valueOf(r.get(sm.MOVEMENT_TYPE)!!),
                    nomenclatureId = r.get(sm.NOMENCLATURE_ID)!!,
                    lotNumber = r.get(lot.LOT_NUMBER),
                    serialNumber = r.get(ser.SERIAL_NUMBER),
                    fromLocationCode = r.get(slFrom.CODE),
                    toLocationCode = r.get(slTo.CODE),
                    quantity = r.get(sm.QUANTITY)!!.stripTrailingZeros().toPlainString(),
                    documentType = r.get(sm.DOCUMENT_TYPE)!!,
                    documentId = r.get(sm.DOCUMENT_ID)!!,
                    note = r.get(sm.NOTE),
                    createdBy = r.get(sm.CREATED_BY)!!,
                    createdByName = r.get(u.FULL_NAME)!!,
                    createdAt = r.get(sm.CREATED_AT)?.toString()
                )
            }
            .let { movements ->
                val numbers = wmsRepository.documentNumbers(movements.map { it.documentType to it.documentId })
                movements.map { it.copy(documentNumber = numbers[it.documentType.uppercase() to it.documentId]) }
            }
    }

    fun listLowStock(currentUser: CurrentUser): List<LowStockItemDto> {
        currentUser.requirePermission("wms", "read")
        val nom = org.example.project.generated.jooq.tables.Nomenclature.NOMENCLATURE
        val sb = org.example.project.generated.jooq.tables.StockBalance.STOCK_BALANCE
        val sl = org.example.project.generated.jooq.tables.StorageLocations.STORAGE_LOCATIONS
        val stockRows = dsl.select(sb.NOMENCLATURE_ID, org.jooq.impl.DSL.sum(sb.PHYSICAL_QTY.minus(sb.RESERVED_QTY)))
            .from(sb)
            .join(sl).on(sl.ID.eq(sb.LOCATION_ID))
            .where(sl.PURPOSE.notIn("QUARANTINE", "DEFECT", "TEMPORARY"))
            .groupBy(sb.NOMENCLATURE_ID)
            .fetch()
            .associate { it.get(sb.NOMENCLATURE_ID)!! to (it.get(1, BigDecimal::class.java) ?: BigDecimal.ZERO) }
        return dsl.selectFrom(nom)
            .where(nom.ARCHIVED.eq(false))
            .and(nom.MIN_STOCK.gt(BigDecimal.ZERO))
            .orderBy(nom.CODE.asc())
            .fetch()
            .mapNotNull { item ->
                val minStock = item.minStock ?: BigDecimal.ZERO
                val avail = (stockRows[item.id] ?: BigDecimal.ZERO).max(BigDecimal.ZERO)
                if (avail < minStock) {
                    LowStockItemDto(
                        nomenclatureId = item.id,
                        code = item.code,
                        name = item.name,
                        unit = item.unit,
                        minStock = minStock.stripTrailingZeros().toPlainString(),
                        safetyStock = (item.safetyStock ?: BigDecimal.ZERO).stripTrailingZeros().toPlainString(),
                        availableQty = avail.stripTrailingZeros().toPlainString(),
                        deficitQty = (minStock - avail).stripTrailingZeros().toPlainString()
                    )
                } else null
            }
    }

    fun listLots(nomenclatureId: Long?, currentUser: CurrentUser): List<LotDto> {
        currentUser.requirePermission("wms", "read")
        val lots = org.example.project.generated.jooq.tables.Lots.LOTS
        val q = dsl.selectFrom(lots)
        val rows = if (nomenclatureId != null) q.where(lots.NOMENCLATURE_ID.eq(nomenclatureId)).fetch() else q.fetch()
        return rows.map {
            LotDto(
                id = it.id,
                nomenclatureId = it.nomenclatureId,
                lotNumber = it.lotNumber,
                manufactureDate = it.manufactureDate?.toString(),
                expiryDate = it.expiryDate?.toString(),
                createdAt = it.createdAt?.toString()
            )
        }
    }

    fun listSerials(nomenclatureId: Long?, currentUser: CurrentUser): List<SerialItemDto> {
        currentUser.requirePermission("wms", "read")
        val ser = org.example.project.generated.jooq.tables.SerialItems.SERIAL_ITEMS
        val q = dsl.selectFrom(ser)
        val rows = if (nomenclatureId != null) q.where(ser.NOMENCLATURE_ID.eq(nomenclatureId)).fetch() else q.fetch()
        return rows.map {
            SerialItemDto(
                id = it.id,
                nomenclatureId = it.nomenclatureId,
                lotId = it.lotId,
                serialNumber = it.serialNumber,
                createdAt = it.createdAt?.toString()
            )
        }
    }


    // Резервы (D-30): резерв не является выдачей; доступное уменьшается
    // через reserved_qty и не может стать отрицательным (инварианты 1 и 5).

    fun createReservation(request: CreateReservationRequest, currentUser: CurrentUser): ReservationDto {
        currentUser.requirePermission("wms", "post")
        val quantity = parseQuantity(request.quantity, "quantity")
        validateReason(request)
        val nomenclature = wmsRepository.findNomenclatureById(request.nomenclatureId)
            ?: throw NotFoundException("nomenclature", request.nomenclatureId)
        if (nomenclature.archived == true) {
            throw ConflictException("Nomenclature is archived: ${nomenclature.code}")
        }
        if (wmsRepository.findWarehouseById(request.warehouseId) == null) {
            throw NotFoundException("warehouse", request.warehouseId)
        }
        val location = wmsRepository.findLocationById(request.locationId)
            ?: throw NotFoundException("storage_location", request.locationId)
        if (location.warehouseId != request.warehouseId) {
            throw ValidationException(
                "Location does not belong to the warehouse",
                mapOf("locationId" to "other_warehouse")
            )
        }
        if (location.active != true) {
            throw ValidationException("Location is inactive", mapOf("locationId" to "inactive"))
        }
        // Карантин, брак и транзит недоступны для обычного резерва и выдачи.
        if (location.purpose == "QUARANTINE" || location.purpose == "DEFECT" || location.purpose == "TEMPORARY") {
            throw ConflictException("Location purpose ${location.purpose} is not available for reservation")
        }
        val lotId = request.lotId
        if (lotId != null) {
            val lot = wmsRepository.findLotById(lotId)
                ?: throw NotFoundException("lot", lotId)
            if (lot.nomenclatureId != nomenclature.id) {
                throw ValidationException("Lot belongs to another nomenclature", mapOf("lotId" to "mismatch"))
            }
        }
        val serialId = request.serialId
        if (serialId != null) {
            val serial = wmsRepository.findSerialById(serialId)
                ?: throw NotFoundException("serial_item", serialId)
            if (serial.nomenclatureId != nomenclature.id) {
                throw ValidationException("Serial item belongs to another nomenclature", mapOf("serialId" to "mismatch"))
            }
        }
        val ownerId = request.ownerId ?: currentUser.userId
        if (!wmsRepository.userExists(ownerId)) {
            throw NotFoundException("user", ownerId)
        }
        val expiresAt = parseDateTime(request.expiresAt)
        if (request.expiresAt != null && expiresAt == null) {
            throw ValidationException("Invalid expiration date", mapOf("expiresAt" to "invalid"))
        }

        return dsl.transactionResult(TransactionalCallable {
            val balance = wmsRepository.lockBalance(
                warehouseId = request.warehouseId,
                locationId = request.locationId,
                nomenclatureId = request.nomenclatureId,
                lotId = request.lotId,
                serialId = request.serialId
            )
            val available = if (balance == null) {
                BigDecimal.ZERO
            } else {
                balance.physicalQty - balance.reservedQty
            }
            if (balance == null || available < quantity) {
                throw ConflictException(
                    "Insufficient available quantity: requested ${quantity.toPlainString()}, available ${available.toPlainString()}"
                )
            }
            if (wmsRepository.addReserved(balance.id, quantity) == 0) {
                throw ConflictException("Stock balance was modified concurrently")
            }
            val number = wmsRepository.generateReservationNumber()
            val reservation = wmsRepository.createReservation(
                number = number,
                reasonType = request.reasonType.name,
                projectId = request.projectId,
                taskId = request.taskId,
                reasonNote = request.reasonNote?.trim(),
                nomenclatureId = request.nomenclatureId,
                warehouseId = request.warehouseId,
                locationId = request.locationId,
                lotId = request.lotId,
                serialId = request.serialId,
                quantity = quantity,
                ownerId = ownerId,
                expiresAt = expiresAt,
                createdBy = currentUser.userId
            )
            wmsRepository.insertReservationHistory(
                reservationId = reservation.id,
                action = "CREATED",
                fromStatus = null,
                toStatus = "ACTIVE",
                quantityBefore = null,
                quantityAfter = quantity,
                reason = request.reasonNote?.trim(),
                changedBy = currentUser.userId
            )
            auditService.log(
                currentUser.userId, "CREATE", "RESERVATION", reservation.id,
                mapOf(
                    "number" to reservation.number,
                    "reasonType" to reservation.reasonType,
                    "nomenclatureId" to reservation.nomenclatureId,
                    "quantity" to quantity.toPlainString()
                )
            )
            reservation.toDto(wmsRepository.listReservationHistory(reservation.id))
        })
    }

    fun releaseReservation(id: Long, request: ReleaseReservationRequest, currentUser: CurrentUser): ReservationDto {
        currentUser.requirePermission("wms", "post")
        return dsl.transactionResult(TransactionalCallable {
            val reservation = wmsRepository.lockReservationById(id) ?: throw NotFoundException("reservation", id)
            if (reservation.version != request.version) {
                throw ConflictException("Reservation was modified concurrently")
            }
            if (reservation.status != "ACTIVE") {
                throw ConflictException("Reservation is not active")
            }
            val remaining = reservation.quantity - reservation.issuedQuantity
            val balance = wmsRepository.lockBalance(
                warehouseId = reservation.warehouseId,
                locationId = reservation.locationId,
                nomenclatureId = reservation.nomenclatureId,
                lotId = reservation.lotId,
                serialId = reservation.serialId
            ) ?: throw ConflictException("Stock balance row is missing for the reservation")
            if (wmsRepository.subtractReserved(balance.id, remaining) == 0) {
                throw ConflictException("Reserved quantity is inconsistent with the stock balance")
            }
            val released = wmsRepository.updateReservationStatus(id, request.version, "ACTIVE", "RELEASED")
                ?: throw ConflictException("Reservation was modified concurrently")
            wmsRepository.insertReservationHistory(
                reservationId = id,
                action = "RELEASED",
                fromStatus = "ACTIVE",
                toStatus = "RELEASED",
                quantityBefore = remaining,
                quantityAfter = BigDecimal.ZERO,
                reason = request.reason?.trim(),
                changedBy = currentUser.userId
            )
            auditService.log(
                currentUser.userId, "RELEASE", "RESERVATION", id,
                mapOf("number" to reservation.number, "quantity" to remaining.toPlainString())
            )
            released.toDto(wmsRepository.listReservationHistory(id))
        })
    }

    fun listReservations(
        warehouseId: Long?,
        nomenclatureId: Long?,
        status: ReservationStatus?,
        currentUser: CurrentUser
    ): List<ReservationDto> {
        currentUser.requirePermission("wms", "read")
        return wmsRepository.listReservations(warehouseId, nomenclatureId, status?.name)
            .map { it.toDto(emptyList()) }
    }

    fun getReservation(id: Long, currentUser: CurrentUser): ReservationDto {
        currentUser.requirePermission("wms", "read")
        val reservation = wmsRepository.findReservationById(id) ?: throw NotFoundException("reservation", id)
        return reservation.toDto(wmsRepository.listReservationHistory(id))
    }

    private fun validateReason(request: CreateReservationRequest) {
        when (request.reasonType) {
            ReservationReasonType.PROJECT -> {
                val projectId = request.projectId
                    ?: throw ValidationException("projectId is required for PROJECT reservation", mapOf("projectId" to "required"))
                if (request.taskId != null) {
                    throw ValidationException("taskId must be empty for PROJECT reservation", mapOf("taskId" to "not_allowed"))
                }
                if (!wmsRepository.projectExists(projectId)) {
                    throw NotFoundException("project", projectId)
                }
            }
            ReservationReasonType.DEPARTMENT_TASK -> {
                val taskId = request.taskId
                    ?: throw ValidationException("taskId is required for DEPARTMENT_TASK reservation", mapOf("taskId" to "required"))
                if (request.projectId != null) {
                    throw ValidationException("projectId must be empty for DEPARTMENT_TASK reservation", mapOf("projectId" to "not_allowed"))
                }
                if (!wmsRepository.taskExists(taskId)) {
                    throw NotFoundException("task", taskId)
                }
            }
            else -> {
                if (request.projectId != null || request.taskId != null) {
                    throw ValidationException(
                        "projectId and taskId must be empty for ${request.reasonType} reservation",
                        mapOf("reasonType" to "references_not_allowed")
                    )
                }
            }
        }
    }

    private fun loadBarcodes(nomenclatureIds: List<Long>): Map<Long, List<NomenclatureBarcodeDto>> {
        return wmsRepository.listBarcodesFor(nomenclatureIds)
            .groupBy({ it.nomenclatureId }, { it.toDto() })
    }

    private fun parseQuantity(value: String, field: String, allowZero: Boolean = false): BigDecimal {
        val parsed = value.trim().replace(" ", "").replace(',', '.').toBigDecimalOrNull()
            ?: throw ValidationException("Invalid quantity: $value", mapOf(field to "invalid"))
        val valid = if (allowZero) parsed.signum() >= 0 else parsed.signum() > 0
        if (!valid) {
            throw ValidationException("Quantity must be positive", mapOf(field to "not_positive"))
        }
        return parsed
    }

    private fun parseDateTime(value: String?): OffsetDateTime? {
        if (value.isNullOrBlank()) return null
        return try {
            OffsetDateTime.parse(value)
        } catch (_: DateTimeParseException) {
            null
        }
    }
}

private fun WarehousesRecord.toDto(): WarehouseDto = WarehouseDto(
    id = this.id,
    code = this.code,
    name = this.name,
    address = this.address,
    active = this.active ?: true,
    createdAt = this.createdAt?.toString(),
    createdBy = this.createdBy,
    version = this.version ?: 0
)

private fun StorageLocationsRecord.toDto(): StorageLocationDto = StorageLocationDto(
    id = this.id,
    warehouseId = this.warehouseId,
    parentId = this.parentId,
    code = this.code,
    name = this.name,
    kind = StorageLocationKind.valueOf(this.kind),
    purpose = this.purpose?.let { StorageLocationPurpose.valueOf(it) } ?: StorageLocationPurpose.STORAGE,
    capacity = this.capacity?.toPlainString(),
    active = this.active ?: true,
    createdAt = this.createdAt?.toString(),
    createdBy = this.createdBy,
    version = this.version ?: 0
)

private fun NomenclatureBarcodesRecord.toDto(): NomenclatureBarcodeDto = NomenclatureBarcodeDto(
    id = this.id,
    barcode = this.barcode,
    packageType = this.packageType?.let { BarcodePackageType.valueOf(it) } ?: BarcodePackageType.UNIT,
    quantityInPackage = this.quantityInPackage?.toPlainString() ?: "1"
)

private fun NomenclatureRecord.toDto(barcodes: Map<Long, List<NomenclatureBarcodeDto>>): NomenclatureDto = NomenclatureDto(
    id = this.id,
    code = this.code,
    name = this.name,
    description = this.description,
    type = NomenclatureType.valueOf(this.type),
    unit = this.unit,
    lotTracked = this.lotTracked ?: false,
    serialTracked = this.serialTracked ?: false,
    minStock = (this.minStock ?: BigDecimal.ZERO).stripTrailingZeros().toPlainString(),
    safetyStock = (this.safetyStock ?: BigDecimal.ZERO).stripTrailingZeros().toPlainString(),
    leadTimeDays = this.leadTimeDays ?: 0,
    archived = this.archived ?: false,
    createdAt = this.createdAt?.toString(),
    createdBy = this.createdBy,
    version = this.version ?: 0,
    barcodes = barcodes[this.id] ?: emptyList(),
    categoryId = this.categoryId,
    article = this.article,
    manufacturer = this.manufacturer,
    manufacturerModel = this.manufacturerModel,
    weightKg = this.weightKg?.stripTrailingZeros()?.toPlainString(),
    lengthMm = this.lengthMm?.stripTrailingZeros()?.toPlainString(),
    widthMm = this.widthMm?.stripTrailingZeros()?.toPlainString(),
    heightMm = this.heightMm?.stripTrailingZeros()?.toPlainString(),
    storageConditions = this.storageConditions,
    productModelId = this.productModelId,
    minOrderQty = (this.minOrderQty ?: BigDecimal.ONE).stripTrailingZeros().toPlainString(),
    orderMultiple = (this.orderMultiple ?: BigDecimal.ONE).stripTrailingZeros().toPlainString()
)

/** Подпись причины, по которой позицию нельзя архивировать (BOM-20, D-63). */
internal fun nomenclatureReferenceLabel(code: String): String = when (code) {
    "stock_balance" -> "есть остаток на складе"
    "reservations" -> "есть активные резервы"
    "bom_lines" -> "входит в действующие ревизии BOM"
    "demand_lines" -> "есть в открытых потребностях"
    "purchase_order_lines" -> "есть в незакрытых заказах поставщикам"
    "production_order_materials" -> "есть в активных производственных заказах"
    else -> code
}

/** Общий маппинг записи номенклатуры для сервиса карточки (тот же, что в WMS). */
internal fun NomenclatureRecord.toNomenclatureDto(barcodes: Map<Long, List<NomenclatureBarcodeDto>>): NomenclatureDto =
    this.toDto(barcodes)

internal fun NomenclatureBarcodesRecord.toBarcodeDto(): NomenclatureBarcodeDto = this.toDto()

private fun WmsRepository.StockRow.toDto(): StockBalanceDto = StockBalanceDto(
    id = this.balance.id,
    warehouseId = this.balance.warehouseId,
    locationId = this.balance.locationId,
    locationCode = this.locationCode,
    locationPurpose = StorageLocationPurpose.valueOf(this.locationPurpose),
    nomenclatureId = this.balance.nomenclatureId,
    lotId = this.balance.lotId,
    lotNumber = this.lotNumber,
    serialId = this.balance.serialId,
    serialNumber = this.serialNumber,
    physicalQty = this.balance.physicalQty.toPlainString(),
    reservedQty = this.balance.reservedQty.toPlainString(),
    availableQty = if (this.locationPurpose in setOf("QUARANTINE", "DEFECT", "TEMPORARY")) "0" else this.balance.availableQty.toPlainString(),
    version = this.balance.version ?: 0
)

private fun ReservationHistoryRecord.toDto(): ReservationHistoryDto = ReservationHistoryDto(
    id = this.id,
    reservationId = this.reservationId,
    action = ReservationHistoryAction.valueOf(this.action),
    fromStatus = this.fromStatus?.let { ReservationStatus.valueOf(it) },
    toStatus = this.toStatus?.let { ReservationStatus.valueOf(it) },
    quantityBefore = this.quantityBefore?.toPlainString(),
    quantityAfter = this.quantityAfter?.toPlainString(),
    reason = this.reason,
    changedBy = this.changedBy,
    createdAt = this.createdAt?.toString()
)

private fun ReservationsRecord.toDto(history: List<ReservationHistoryRecord>): ReservationDto = ReservationDto(
    id = this.id,
    number = this.number,
    reasonType = ReservationReasonType.valueOf(this.reasonType),
    projectId = this.projectId,
    taskId = this.taskId,
    reasonNote = this.reasonNote,
    nomenclatureId = this.nomenclatureId,
    warehouseId = this.warehouseId,
    locationId = this.locationId,
    lotId = this.lotId,
    serialId = this.serialId,
    quantity = this.quantity.toPlainString(),
    issuedQuantity = this.issuedQuantity?.toPlainString() ?: "0",
    status = this.status?.let { ReservationStatus.valueOf(it) } ?: ReservationStatus.ACTIVE,
    ownerId = this.ownerId,
    expiresAt = this.expiresAt?.toString(),
    createdAt = this.createdAt?.toString(),
    createdBy = this.createdBy,
    version = this.version ?: 0,
    history = history.map { it.toDto() }
)
