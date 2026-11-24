package org.example.project.service

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import org.example.project.dto.*
import org.example.project.exception.ConflictException
import org.example.project.exception.NotFoundException
import org.example.project.exception.ValidationException
import org.example.project.generated.jooq.tables.records.StockBalanceRecord
import org.example.project.generated.jooq.tables.records.StorageLocationsRecord
import org.example.project.generated.jooq.tables.records.TransferEventsRecord
import org.example.project.generated.jooq.tables.records.TransfersRecord
import org.example.project.model.CurrentUser
import org.example.project.repository.WmsTransferRepository
import org.jooq.DSLContext
import org.jooq.TransactionalCallable
import org.jooq.exception.DataAccessException
import java.math.BigDecimal
import java.security.MessageDigest

class WmsTransferService(
    private val transferRepository: WmsTransferRepository,
    private val auditService: AuditService,
    private val dsl: DSLContext
) {

    // Ответ команды: тело хранится в wms_idempotency_keys и при повторе
    // возвращается без повторного выполнения операции.
    data class CommandResponse(val status: HttpStatusCode, val body: String)

    fun create(request: CreateTransferRequest, currentUser: CurrentUser): CommandResponse {
        currentUser.requirePermission("wms", "post")
        val quantity = parseQuantity(request.quantity, "quantity")
        val payload = json.encodeToString(CreateTransferRequest.serializer(), request)
        return executeIdempotent("TRANSFER_CREATE", request.idempotencyKey, payload, HttpStatusCode.Created, currentUser) {
            createTransfer(request, quantity, currentUser)
        }
    }

    fun ship(id: Long, request: ShipTransferRequest, currentUser: CurrentUser): CommandResponse {
        currentUser.requirePermission("wms", "post")
        val payload = json.encodeToString(ShipTransferRequest.serializer(), request)
        return executeIdempotent("TRANSFER_SHIP", request.idempotencyKey, payload, HttpStatusCode.OK, currentUser) {
            shipTransfer(id, request, currentUser)
        }
    }

    fun markInTransit(id: Long, request: MarkTransferInTransitRequest, currentUser: CurrentUser): CommandResponse {
        currentUser.requirePermission("wms", "post")
        val payload = json.encodeToString(MarkTransferInTransitRequest.serializer(), request)
        return executeIdempotent("TRANSFER_IN_TRANSIT", request.idempotencyKey, payload, HttpStatusCode.OK, currentUser) {
            markTransferInTransit(id, request, currentUser)
        }
    }

    fun receive(id: Long, request: ReceiveTransferRequest, currentUser: CurrentUser): CommandResponse {
        currentUser.requirePermission("wms", "post")
        val receivedQuantity = parseQuantity(request.receivedQuantity, "receivedQuantity", allowZero = true)
        val payload = json.encodeToString(ReceiveTransferRequest.serializer(), request)
        return executeIdempotent("TRANSFER_RECEIVE", request.idempotencyKey, payload, HttpStatusCode.OK, currentUser) {
            receiveTransfer(id, request, receivedQuantity, currentUser)
        }
    }

    fun cancel(id: Long, request: CancelTransferRequest, currentUser: CurrentUser): CommandResponse {
        currentUser.requirePermission("wms", "post")
        if (request.reason.isBlank()) {
            throw ValidationException("Cancel reason is required", mapOf("reason" to "required"))
        }
        val payload = json.encodeToString(CancelTransferRequest.serializer(), request)
        return executeIdempotent("TRANSFER_CANCEL", request.idempotencyKey, payload, HttpStatusCode.OK, currentUser) {
            cancelTransfer(id, request, currentUser)
        }
    }

    fun list(
        warehouseId: Long?,
        nomenclatureId: Long?,
        status: TransferStatus?,
        currentUser: CurrentUser
    ): List<TransferDto> {
        currentUser.requirePermission("wms", "read")
        return transferRepository.listTransfers(warehouseId, nomenclatureId, status?.name)
            .map { it.toDto(emptyList()) }
    }

    fun get(id: Long, currentUser: CurrentUser): TransferDto {
        currentUser.requirePermission("wms", "read")
        val transfer = transferRepository.findTransferById(id) ?: throw NotFoundException("transfer", id)
        return transfer.toDto(transferRepository.listEvents(id))
    }

    // Внутрискладское перемещение проводится атомарно при создании
    // (статус RECEIVED), межскладское создаётся в CREATED без движения остатков.
    private fun createTransfer(
        request: CreateTransferRequest,
        quantity: BigDecimal,
        currentUser: CurrentUser
    ): TransferDto {
        val nomenclature = transferRepository.findNomenclatureById(request.nomenclatureId)
            ?: throw NotFoundException("nomenclature", request.nomenclatureId)
        if (nomenclature.archived == true) {
            throw ConflictException("Nomenclature is archived: ${nomenclature.code}")
        }
        val lotId = request.lotId
        if (lotId != null) {
            val lot = transferRepository.findLotById(lotId)
                ?: throw NotFoundException("lot", lotId)
            if (lot.nomenclatureId != nomenclature.id) {
                throw ValidationException("Lot belongs to another nomenclature", mapOf("lotId" to "mismatch"))
            }
        }
        val serialId = request.serialId
        if (serialId != null) {
            val serial = transferRepository.findSerialById(serialId)
                ?: throw NotFoundException("serial_item", serialId)
            if (serial.nomenclatureId != nomenclature.id) {
                throw ValidationException("Serial item belongs to another nomenclature", mapOf("serialId" to "mismatch"))
            }
            if (quantity.compareTo(BigDecimal.ONE) != 0) {
                throw ValidationException("Serial-tracked transfer quantity must be exactly 1", mapOf("quantity" to "not_serial_unit"))
            }
        }
        val fromWarehouse = transferRepository.findWarehouseById(request.fromWarehouseId)
            ?: throw NotFoundException("warehouse", request.fromWarehouseId)
        val toWarehouse = transferRepository.findWarehouseById(request.toWarehouseId)
            ?: throw NotFoundException("warehouse", request.toWarehouseId)
        if (fromWarehouse.active != true || toWarehouse.active != true) {
            throw ValidationException("Warehouse is inactive", mapOf("warehouseId" to "inactive"))
        }
        when (request.kind) {
            TransferKind.INTERNAL -> {
                if (request.fromWarehouseId != request.toWarehouseId) {
                    throw ValidationException("Internal transfer must stay within one warehouse", mapOf("toWarehouseId" to "other_warehouse"))
                }
                if (request.fromLocationId == request.toLocationId) {
                    throw ValidationException("Source and target locations must differ", mapOf("toLocationId" to "same_location"))
                }
            }
            TransferKind.INTER_WAREHOUSE -> {
                if (request.fromWarehouseId == request.toWarehouseId) {
                    throw ValidationException("Inter-warehouse transfer requires different warehouses", mapOf("toWarehouseId" to "same_warehouse"))
                }
            }
        }
        validateTransferLocation(request.fromLocationId, request.fromWarehouseId, "fromLocationId")
        validateTransferLocation(request.toLocationId, request.toWarehouseId, "toLocationId")

        val number = transferRepository.generateTransferNumber()
        return when (request.kind) {
            TransferKind.INTERNAL -> {
                val sourceKey = WmsTransferRepository.BalanceKey(
                    request.fromWarehouseId, request.fromLocationId, request.nomenclatureId, request.lotId, request.serialId
                )
                val targetKey = WmsTransferRepository.BalanceKey(
                    request.toWarehouseId, request.toLocationId, request.nomenclatureId, request.lotId, request.serialId
                )
                // Блокировка строк в детерминированном порядке ключей — защита
                // от дедлоков при встречных перемещениях.
                val (sourceRow, _) = lockPairOrdered(sourceKey, targetKey)
                val available = sourceRow?.let { it.physicalQty - it.reservedQty } ?: BigDecimal.ZERO
                if (sourceRow == null || available < quantity) {
                    throw ConflictException(
                        "Insufficient available quantity: requested ${quantity.toPlainString()}, available ${available.toPlainString()}"
                    )
                }
                if (transferRepository.decreaseFreePhysical(sourceRow.id, quantity) == 0) {
                    throw ConflictException("Stock balance was modified concurrently")
                }
                transferRepository.increasePhysical(targetKey, quantity)
                val transfer = transferRepository.insertTransfer(
                    number = number,
                    kind = request.kind.name,
                    fromWarehouseId = request.fromWarehouseId,
                    fromLocationId = request.fromLocationId,
                    toWarehouseId = request.toWarehouseId,
                    toLocationId = request.toLocationId,
                    transitLocationId = null,
                    nomenclatureId = request.nomenclatureId,
                    lotId = request.lotId,
                    serialId = request.serialId,
                    quantity = quantity,
                    status = "RECEIVED",
                    note = request.note?.trim(),
                    shippedAt = null,
                    shippedBy = null,
                    receivedAt = java.time.OffsetDateTime.now(),
                    receivedBy = currentUser.userId,
                    receivedQuantity = quantity,
                    createdBy = currentUser.userId
                )
                transferRepository.insertMovement(
                    nomenclatureId = request.nomenclatureId,
                    lotId = request.lotId,
                    serialId = request.serialId,
                    fromLocationId = request.fromLocationId,
                    toLocationId = request.toLocationId,
                    quantity = quantity,
                    documentId = transfer.id,
                    note = "Перемещение $number",
                    createdBy = currentUser.userId
                )
                transferRepository.insertEvent(transfer.id, "CREATED", null, "CREATED", quantity, null, request.note?.trim(), currentUser.userId)
                transferRepository.insertEvent(transfer.id, "RECEIVED", "CREATED", "RECEIVED", quantity, null, "Internal transfer executed atomically", currentUser.userId)
                auditService.log(
                    currentUser.userId, "CREATE", "TRANSFER", transfer.id,
                    mapOf("number" to number, "kind" to request.kind.name, "quantity" to quantity.toPlainString())
                )
                transfer.toDto(transferRepository.listEvents(transfer.id))
            }
            TransferKind.INTER_WAREHOUSE -> {
                val transfer = transferRepository.insertTransfer(
                    number = number,
                    kind = request.kind.name,
                    fromWarehouseId = request.fromWarehouseId,
                    fromLocationId = request.fromLocationId,
                    toWarehouseId = request.toWarehouseId,
                    toLocationId = request.toLocationId,
                    transitLocationId = null,
                    nomenclatureId = request.nomenclatureId,
                    lotId = request.lotId,
                    serialId = request.serialId,
                    quantity = quantity,
                    status = "CREATED",
                    note = request.note?.trim(),
                    shippedAt = null,
                    shippedBy = null,
                    receivedAt = null,
                    receivedBy = null,
                    receivedQuantity = null,
                    createdBy = currentUser.userId
                )
                transferRepository.insertEvent(transfer.id, "CREATED", null, "CREATED", quantity, null, request.note?.trim(), currentUser.userId)
                auditService.log(
                    currentUser.userId, "CREATE", "TRANSFER", transfer.id,
                    mapOf("number" to number, "kind" to request.kind.name, "quantity" to quantity.toPlainString())
                )
                transfer.toDto(transferRepository.listEvents(transfer.id))
            }
        }
    }

    // Отгрузка: свободный остаток исходной ячейки уходит в транзитную зону
    // исходного склада (purpose TEMPORARY) — недоступен на обоих складах.
    private fun shipTransfer(id: Long, request: ShipTransferRequest, currentUser: CurrentUser): TransferDto {
        val transfer = transferRepository.lockTransferById(id) ?: throw NotFoundException("transfer", id)
        if (transfer.version != request.version) {
            throw ConflictException("Transfer was modified concurrently")
        }
        if (transfer.transferKind != "INTER_WAREHOUSE") {
            throw ConflictException("Only inter-warehouse transfers can be shipped")
        }
        if (transfer.status != "CREATED") {
            throw ConflictException("Transfer can only be shipped from CREATED status")
        }
        val quantity = transfer.quantity
        val sourceKey = WmsTransferRepository.BalanceKey(
            transfer.fromWarehouseId, transfer.fromLocationId, transfer.nomenclatureId, transfer.lotId, transfer.serialId
        )
        val sourceRow = transferRepository.lockBalance(sourceKey)
        val available = sourceRow?.let { it.physicalQty - it.reservedQty } ?: BigDecimal.ZERO
        if (sourceRow == null || available < quantity) {
            throw ConflictException(
                "Insufficient available quantity: requested ${quantity.toPlainString()}, available ${available.toPlainString()}"
            )
        }
        val transit = ensureTransitLocation(transfer.fromWarehouseId, currentUser.userId)
        if (transferRepository.decreaseFreePhysical(sourceRow.id, quantity) == 0) {
            throw ConflictException("Stock balance was modified concurrently")
        }
        transferRepository.increasePhysical(
            WmsTransferRepository.BalanceKey(
                transfer.fromWarehouseId, transit.id, transfer.nomenclatureId, transfer.lotId, transfer.serialId
            ),
            quantity
        )
        val shipped = transferRepository.markShipped(id, request.version, transit.id, currentUser.userId)
            ?: throw ConflictException("Transfer was modified concurrently")
        transferRepository.insertMovement(
            nomenclatureId = transfer.nomenclatureId,
            lotId = transfer.lotId,
            serialId = transfer.serialId,
            fromLocationId = transfer.fromLocationId,
            toLocationId = transit.id,
            quantity = quantity,
            documentId = id,
            note = "Отгрузка ${transfer.number}",
            createdBy = currentUser.userId
        )
        transferRepository.insertEvent(id, "SHIPPED", "CREATED", "SHIPPED", quantity, null, null, currentUser.userId)
        auditService.log(
            currentUser.userId, "SHIP", "TRANSFER", id,
            mapOf("number" to transfer.number, "quantity" to quantity.toPlainString())
        )
        return shipped.toDto(transferRepository.listEvents(id))
    }

    private fun markTransferInTransit(id: Long, request: MarkTransferInTransitRequest, currentUser: CurrentUser): TransferDto {
        val transfer = transferRepository.lockTransferById(id) ?: throw NotFoundException("transfer", id)
        if (transfer.version != request.version) {
            throw ConflictException("Transfer was modified concurrently")
        }
        if (transfer.status != "SHIPPED") {
            throw ConflictException("Transfer can only go in transit from SHIPPED status")
        }
        val updated = transferRepository.markInTransit(id, request.version)
            ?: throw ConflictException("Transfer was modified concurrently")
        transferRepository.insertEvent(id, "IN_TRANSIT", "SHIPPED", "IN_TRANSIT", null, null, null, currentUser.userId)
        auditService.log(currentUser.userId, "IN_TRANSIT", "TRANSFER", id, mapOf("number" to transfer.number))
        return updated.toDto(transferRepository.listEvents(id))
    }

    // Приёмка на целевом складе: принятое количество уходит из транзитной зоны
    // в целевую ячейку; при расхождении разница остаётся в транзите (не
    // доступна), документ получает статус DISCREPANCY с зафиксированной суммой.
    private fun receiveTransfer(
        id: Long,
        request: ReceiveTransferRequest,
        receivedQuantity: BigDecimal,
        currentUser: CurrentUser
    ): TransferDto {
        val transfer = transferRepository.lockTransferById(id) ?: throw NotFoundException("transfer", id)
        if (transfer.version != request.version) {
            throw ConflictException("Transfer was modified concurrently")
        }
        if (transfer.status != "IN_TRANSIT") {
            throw ConflictException("Transfer can only be received from IN_TRANSIT status")
        }
        if (receivedQuantity > transfer.quantity) {
            throw ValidationException(
                "Received quantity exceeds shipped quantity",
                mapOf("receivedQuantity" to "exceeds_shipped")
            )
        }
        val discrepancy = transfer.quantity - receivedQuantity
        val newStatus = if (discrepancy.signum() == 0) "RECEIVED" else "DISCREPANCY"
        val transitLocationId = transfer.transitLocationId
            ?: throw ConflictException("Transfer has no transit location")
        if (receivedQuantity.signum() > 0) {
            val transitKey = WmsTransferRepository.BalanceKey(
                transfer.fromWarehouseId, transitLocationId, transfer.nomenclatureId, transfer.lotId, transfer.serialId
            )
            val targetKey = WmsTransferRepository.BalanceKey(
                transfer.toWarehouseId, transfer.toLocationId, transfer.nomenclatureId, transfer.lotId, transfer.serialId
            )
            // Блокировка строк в детерминированном порядке ключей — защита
            // от дедлоков при встречных приёмках.
            val (transitRow, _) = lockPairOrdered(transitKey, targetKey)
            if (transitRow == null) {
                throw ConflictException("Transit balance is missing for the transfer")
            }
            if (transferRepository.decreaseFreePhysical(transitRow.id, receivedQuantity) == 0) {
                throw ConflictException("Transit balance is inconsistent with the transfer")
            }
            transferRepository.increasePhysical(targetKey, receivedQuantity)
            transferRepository.insertMovement(
                nomenclatureId = transfer.nomenclatureId,
                lotId = transfer.lotId,
                serialId = transfer.serialId,
                fromLocationId = transitLocationId,
                toLocationId = transfer.toLocationId,
                quantity = receivedQuantity,
                documentId = id,
                note = "Приёмка перемещения ${transfer.number}",
                createdBy = currentUser.userId
            )
        }
        val updated = transferRepository.markReceived(
            id, request.version, newStatus, receivedQuantity,
            if (discrepancy.signum() > 0) discrepancy else null,
            currentUser.userId
        ) ?: throw ConflictException("Transfer was modified concurrently")
        transferRepository.insertEvent(
            id, newStatus, "IN_TRANSIT", newStatus, receivedQuantity,
            if (discrepancy.signum() > 0) discrepancy else null,
            request.note?.trim(), currentUser.userId
        )
        auditService.log(
            currentUser.userId, "RECEIVE", "TRANSFER", id,
            mapOf(
                "number" to transfer.number,
                "receivedQuantity" to receivedQuantity.toPlainString(),
                "discrepancyQuantity" to discrepancy.toPlainString()
            )
        )
        return updated.toDto(transferRepository.listEvents(id))
    }

    // Отмена допускается только до отправки: остатки в CREATED не тронуты.
    private fun cancelTransfer(id: Long, request: CancelTransferRequest, currentUser: CurrentUser): TransferDto {
        val transfer = transferRepository.lockTransferById(id) ?: throw NotFoundException("transfer", id)
        if (transfer.version != request.version) {
            throw ConflictException("Transfer was modified concurrently")
        }
        if (transfer.status != "CREATED") {
            throw ConflictException("Transfer can only be cancelled before shipping (from CREATED status)")
        }
        val updated = transferRepository.markCancelled(id, request.version, request.reason.trim(), currentUser.userId)
            ?: throw ConflictException("Transfer was modified concurrently")
        transferRepository.insertEvent(id, "CANCELLED", "CREATED", "CANCELLED", null, null, request.reason.trim(), currentUser.userId)
        auditService.log(
            currentUser.userId, "CANCEL", "TRANSFER", id,
            mapOf("number" to transfer.number, "reason" to request.reason.trim())
        )
        return updated.toDto(transferRepository.listEvents(id))
    }

    // Идемпотентное выполнение: ключ привязан к пользователю, операции и хешу
    // канонического тела. Повтор с тем же телом возвращает сохранённый ответ,
    // повтор с другим телом — 409.
    private fun executeIdempotent(
        operation: String,
        idempotencyKey: String,
        requestPayload: String,
        successStatus: HttpStatusCode,
        currentUser: CurrentUser,
        work: () -> TransferDto
    ): CommandResponse {
        if (idempotencyKey.isBlank()) {
            throw ValidationException("Idempotency key is required", mapOf("idempotencyKey" to "required"))
        }
        val requestHash = sha256("$operation|$requestPayload")
        try {
            return dsl.transactionResult(TransactionalCallable {
                val existing = transferRepository.findIdempotency(currentUser.userId, operation, idempotencyKey)
                if (existing != null) {
                    if (existing.requestHash != requestHash) {
                        throw ConflictException("Idempotency key was already used with a different request")
                    }
                    return@TransactionalCallable CommandResponse(
                        HttpStatusCode.fromValue(existing.responseStatus),
                        existing.responseBody
                    )
                }
                val dto = work()
                val body = json.encodeToString(
                    ApiResponse.serializer(TransferDto.serializer()),
                    ApiResponse.success(dto)
                )
                transferRepository.insertIdempotency(
                    currentUser.userId, operation, idempotencyKey, requestHash, dto.id, successStatus.value, body
                )
                CommandResponse(successStatus, body)
            })
        } catch (e: DataAccessException) {
            if (e.sqlState() == "23505" && e.message?.contains("uq_wms_idempotency") == true) {
                val existing = transferRepository.findIdempotency(currentUser.userId, operation, idempotencyKey)
                if (existing != null && existing.requestHash == requestHash) {
                    return CommandResponse(HttpStatusCode.fromValue(existing.responseStatus), existing.responseBody)
                }
                throw ConflictException("Idempotency key was already used with a different request")
            }
            throw e
        }
    }

    private fun ensureTransitLocation(warehouseId: Long, userId: Long): StorageLocationsRecord {
        transferRepository.findTransitLocation(warehouseId)?.let { return it }
        return try {
            transferRepository.createTransitLocation(warehouseId, userId)
        } catch (e: DataAccessException) {
            if (e.sqlState() == "23505") {
                transferRepository.findTransitLocation(warehouseId) ?: throw e
            } else {
                throw e
            }
        }
    }

    // Блокировка двух строк остатков в порядке возрастания ключа.
    // Возвращает записи в порядке аргументов.
    private fun lockPairOrdered(
        first: WmsTransferRepository.BalanceKey,
        second: WmsTransferRepository.BalanceKey
    ): Pair<StockBalanceRecord?, StockBalanceRecord?> {
        return if (first <= second) {
            transferRepository.lockBalance(first) to transferRepository.lockBalance(second)
        } else {
            val secondRow = transferRepository.lockBalance(second)
            val firstRow = transferRepository.lockBalance(first)
            firstRow to secondRow
        }
    }

    private fun validateTransferLocation(locationId: Long, warehouseId: Long, field: String): StorageLocationsRecord {
        val location = transferRepository.findLocationById(locationId)
            ?: throw NotFoundException("storage_location", locationId)
        if (location.warehouseId != warehouseId) {
            throw ValidationException("Location does not belong to the warehouse", mapOf(field to "other_warehouse"))
        }
        if (location.active != true) {
            throw ValidationException("Location is inactive", mapOf(field to "inactive"))
        }
        // Карантин, брак и транзитная зона недоступны для обычных перемещений.
        if (location.purpose !in TRANSFERABLE_PURPOSES) {
            throw ConflictException("Location purpose ${location.purpose} is not allowed for transfers")
        }
        return location
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

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private val TRANSFERABLE_PURPOSES = setOf("STORAGE", "RECEIVING", "SHIPPING")
    }
}

private val json = Json {
    encodeDefaults = true
}

private fun TransferEventsRecord.toDto(): TransferEventDto = TransferEventDto(
    id = this.id,
    transferId = this.transferId,
    action = TransferEventAction.valueOf(this.action),
    fromStatus = this.fromStatus?.let { TransferStatus.valueOf(it) },
    toStatus = this.toStatus?.let { TransferStatus.valueOf(it) },
    quantity = this.quantity?.toPlainString(),
    discrepancyQuantity = this.discrepancyQuantity?.toPlainString(),
    note = this.note,
    changedBy = this.changedBy,
    createdAt = this.createdAt?.toString()
)

private fun TransfersRecord.toDto(events: List<TransferEventsRecord>): TransferDto = TransferDto(
    id = this.id,
    number = this.number,
    kind = TransferKind.valueOf(this.transferKind),
    fromWarehouseId = this.fromWarehouseId,
    fromLocationId = this.fromLocationId,
    toWarehouseId = this.toWarehouseId,
    toLocationId = this.toLocationId,
    transitLocationId = this.transitLocationId,
    nomenclatureId = this.nomenclatureId,
    lotId = this.lotId,
    serialId = this.serialId,
    quantity = this.quantity.toPlainString(),
    receivedQuantity = this.receivedQuantity?.toPlainString(),
    discrepancyQuantity = this.discrepancyQuantity?.toPlainString(),
    status = this.status?.let { TransferStatus.valueOf(it) } ?: TransferStatus.CREATED,
    note = this.note,
    shippedAt = this.shippedAt?.toString(),
    shippedBy = this.shippedBy,
    receivedAt = this.receivedAt?.toString(),
    receivedBy = this.receivedBy,
    cancelledAt = this.cancelledAt?.toString(),
    cancelledBy = this.cancelledBy,
    cancelReason = this.cancelReason,
    createdAt = this.createdAt?.toString(),
    createdBy = this.createdBy,
    version = this.version ?: 0,
    events = events.map { it.toDto() }
)
