package org.example.project.service

import org.example.project.dto.*
import org.example.project.exception.ConflictException
import org.example.project.exception.NotFoundException
import org.example.project.exception.ValidationException
import org.example.project.generated.jooq.tables.records.NomenclatureBarcodesRecord
import org.example.project.generated.jooq.tables.records.NomenclatureRecord
import org.example.project.generated.jooq.tables.records.ReceiptLinesRecord
import org.example.project.generated.jooq.tables.records.ReceiptsRecord
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
            availableQty = stock.fold(BigDecimal.ZERO) { total, row -> total.add(row.balance.availableQty) }.toPlainString(),
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
            movements = wmsRepository.listMovements(id).map { row ->
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
                    createdAt = movement.createdAt?.toString()
                )
            }
        )
    }

    fun archiveNomenclature(id: Long, currentUser: CurrentUser): NomenclatureDto {
        currentUser.requirePermission("wms", "manage")
        if (wmsRepository.findNomenclatureById(id) == null) {
            throw NotFoundException("nomenclature", id)
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

    // Остатки

    fun getStock(warehouseId: Long?, nomenclatureId: Long?, currentUser: CurrentUser): List<StockBalanceDto> {
        currentUser.requirePermission("wms", "read")
        return wmsRepository.selectStock(warehouseId, nomenclatureId).map { it.toDto() }
    }

    // Приёмка (D-29): кладовщик создаёт черновик, сверяет и проводит без ОТК.

    fun createReceipt(request: CreateReceiptRequest, currentUser: CurrentUser): ReceiptDto {
        currentUser.requirePermission("wms", "post")
        if (request.lines.isEmpty()) {
            throw ValidationException("Receipt must have at least one line", mapOf("lines" to "required"))
        }
        val lineNos = request.lines.map { it.lineNo }
        if (lineNos.distinct().size != lineNos.size) {
            throw ValidationException("Duplicate line numbers", mapOf("lines" to "duplicate_line_no"))
        }
        val warehouse = wmsRepository.findWarehouseById(request.warehouseId)
            ?: throw NotFoundException("warehouse", request.warehouseId)
        if (warehouse.active != true) {
            throw ValidationException("Warehouse is inactive", mapOf("warehouseId" to "inactive"))
        }
        val expectedAt = parseDateTime(request.expectedAt)
        if (request.expectedAt != null && expectedAt == null) {
            throw ValidationException("Invalid expected date", mapOf("expectedAt" to "invalid"))
        }

        return dsl.transactionResult(TransactionalCallable {
            val number = wmsRepository.generateReceiptNumber()
            val receipt = wmsRepository.createReceipt(
                number = number,
                sourceType = request.sourceType.name,
                sourceRef = request.sourceRef?.trim(),
                warehouseId = request.warehouseId,
                expectedAt = expectedAt,
                note = request.note?.trim(),
                createdBy = currentUser.userId
            )
            for (line in request.lines) {
                val nomenclature = wmsRepository.findNomenclatureById(line.nomenclatureId)
                    ?: throw NotFoundException("nomenclature", line.nomenclatureId)
                if (nomenclature.archived == true) {
                    throw ConflictException("Nomenclature is archived: ${nomenclature.code}")
                }
                val location = wmsRepository.findLocationById(line.locationId)
                    ?: throw NotFoundException("storage_location", line.locationId)
                if (location.warehouseId != request.warehouseId) {
                    throw ValidationException(
                        "Location does not belong to the receipt warehouse",
                        mapOf("locationId" to "other_warehouse")
                    )
                }
                if (location.active != true) {
                    throw ValidationException("Location is inactive", mapOf("locationId" to "inactive"))
                }
                val expected = parseQuantity(line.expectedQuantity, "expectedQuantity")
                // Фактическое количество по умолчанию равно ожидаемому; превышение
                // не попадает в свободный остаток без отдельного решения (8.4 ТЗ).
                val received = line.receivedQuantity
                    ?.let { parseQuantity(it, "receivedQuantity", allowZero = true) }
                    ?: expected
                if (received > expected) {
                    throw ValidationException(
                        "Received quantity exceeds expected; surplus requires a separate decision",
                        mapOf("receivedQuantity" to "exceeds_expected")
                    )
                }
                var lotId: Long? = null
                val lotNumber = line.lotNumber
                if (nomenclature.lotTracked == true && lotNumber.isNullOrBlank()) {
                    throw ValidationException(
                        "Lot number is required for lot-tracked nomenclature",
                        mapOf("lotNumber" to "required")
                    )
                }
                if (!lotNumber.isNullOrBlank()) {
                    lotId = (wmsRepository.findLot(nomenclature.id, lotNumber.trim())
                        ?: wmsRepository.createLot(nomenclature.id, lotNumber.trim(), currentUser.userId)).id
                }
                wmsRepository.insertReceiptLine(
                    receiptId = receipt.id,
                    lineNo = line.lineNo,
                    nomenclatureId = nomenclature.id,
                    expectedQuantity = expected,
                    receivedQuantity = received,
                    lotId = lotId,
                    locationId = line.locationId,
                    note = line.note?.trim()
                )
            }
            auditService.log(
                currentUser.userId, "CREATE", "RECEIPT", receipt.id,
                mapOf("number" to receipt.number, "sourceType" to receipt.sourceType, "lines" to request.lines.size)
            )
            receipt.toDto(wmsRepository.listReceiptLines(receipt.id))
        })
    }

    fun getReceipt(id: Long, currentUser: CurrentUser): ReceiptDto {
        currentUser.requirePermission("wms", "read")
        val receipt = wmsRepository.findReceiptById(id) ?: throw NotFoundException("receipt", id)
        return receipt.toDto(wmsRepository.listReceiptLines(id))
    }

    fun listReceipts(
        warehouseId: Long?,
        status: ReceiptStatus?,
        currentUser: CurrentUser
    ): List<ReceiptDto> {
        currentUser.requirePermission("wms", "read")
        val receipts = wmsRepository.listReceipts(warehouseId, status?.name)
        val linesByReceipt = wmsRepository.listReceiptLinesFor(receipts.map { it.id }).groupBy { it.receiptId }
        return receipts.map { it.toDto(linesByReceipt[it.id] ?: emptyList()) }
    }

    // Проведение атомарно: статус POSTED, движения RECEIPT и физический остаток
    // в одной транзакции. Повторное проведение отклоняется условным UPDATE
    // (инварианты 2 и 18 ТЗ), конкурентное изменение — проверкой версии (19).
    fun postReceipt(id: Long, request: PostReceiptRequest, currentUser: CurrentUser): ReceiptDto {
        currentUser.requirePermission("wms", "post")
        return dsl.transactionResult(TransactionalCallable {
            val receipt = wmsRepository.lockReceiptById(id) ?: throw NotFoundException("receipt", id)
            if (receipt.version != request.version) {
                throw ConflictException("Receipt was modified concurrently")
            }
            if (receipt.status == "POSTED") {
                throw ConflictException("Receipt is already posted")
            }
            if (receipt.status != "DRAFT" && receipt.status != "IN_PROGRESS") {
                throw ConflictException("Receipt cannot be posted from status ${receipt.status}")
            }
            val lines = wmsRepository.listReceiptLines(id)
            if (lines.isEmpty()) {
                throw ValidationException("Receipt has no lines", mapOf("lines" to "required"))
            }
            for (line in lines) {
                val received = line.receivedQuantity
                if (received.signum() <= 0) continue
                val nomenclature = wmsRepository.findNomenclatureById(line.nomenclatureId)
                    ?: throw NotFoundException("nomenclature", line.nomenclatureId)
                if (nomenclature.archived == true) {
                    throw ConflictException("Nomenclature is archived: ${nomenclature.code}")
                }
                wmsRepository.insertMovement(
                    movementType = "RECEIPT",
                    nomenclatureId = line.nomenclatureId,
                    lotId = line.lotId,
                    serialId = null,
                    fromLocationId = null,
                    toLocationId = line.locationId,
                    quantity = received,
                    documentType = "RECEIPT",
                    documentId = receipt.id,
                    note = "Receipt ${receipt.number}",
                    createdBy = currentUser.userId
                )
                wmsRepository.increasePhysical(
                    warehouseId = receipt.warehouseId,
                    locationId = line.locationId,
                    nomenclatureId = line.nomenclatureId,
                    lotId = line.lotId,
                    serialId = null,
                    quantity = received
                )
            }
            val posted = wmsRepository.postReceipt(id, request.version, currentUser.userId)
                ?: throw ConflictException("Receipt was modified concurrently")
            auditService.log(
                currentUser.userId, "POST", "RECEIPT", id,
                mapOf("number" to receipt.number, "lines" to lines.size)
            )
            posted.toDto(wmsRepository.listReceiptLines(id))
        })
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
        val parsed = value.trim().toBigDecimalOrNull()
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
    archived = this.archived ?: false,
    createdAt = this.createdAt?.toString(),
    createdBy = this.createdBy,
    version = this.version ?: 0,
    barcodes = barcodes[this.id] ?: emptyList()
)

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
    availableQty = this.balance.availableQty.toPlainString(),
    version = this.balance.version ?: 0
)

private fun ReceiptLinesRecord.toDto(): ReceiptLineDto = ReceiptLineDto(
    id = this.id,
    lineNo = this.lineNo,
    nomenclatureId = this.nomenclatureId,
    expectedQuantity = this.expectedQuantity.toPlainString(),
    receivedQuantity = this.receivedQuantity.toPlainString(),
    lotId = this.lotId,
    locationId = this.locationId,
    note = this.note
)

private fun ReceiptsRecord.toDto(lines: List<ReceiptLinesRecord>): ReceiptDto = ReceiptDto(
    id = this.id,
    number = this.number,
    sourceType = ReceiptSourceType.valueOf(this.sourceType),
    sourceRef = this.sourceRef,
    warehouseId = this.warehouseId,
    status = this.status?.let { ReceiptStatus.valueOf(it) } ?: ReceiptStatus.DRAFT,
    expectedAt = this.expectedAt?.toString(),
    note = this.note,
    postedAt = this.postedAt?.toString(),
    postedBy = this.postedBy,
    createdAt = this.createdAt?.toString(),
    createdBy = this.createdBy,
    version = this.version ?: 0,
    lines = lines.map { it.toDto() }
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
