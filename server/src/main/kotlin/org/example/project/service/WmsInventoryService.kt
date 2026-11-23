package org.example.project.service

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import org.example.project.dto.ApiResponse
import org.example.project.dto.CancelInventoryRequest
import org.example.project.dto.CreateInventoryRequest
import org.example.project.dto.InventoryActionRequest
import org.example.project.dto.InventoryDiscrepancyDto
import org.example.project.dto.InventoryDiscrepancyKind
import org.example.project.dto.InventoryDto
import org.example.project.dto.InventoryScopeType
import org.example.project.dto.InventorySnapshotDto
import org.example.project.dto.InventoryStatus
import org.example.project.dto.SubmitInventoryCountsRequest
import org.example.project.exception.ConflictException
import org.example.project.exception.NotFoundException
import org.example.project.exception.ValidationException
import org.example.project.generated.jooq.tables.records.InventoryDiscrepanciesRecord
import org.example.project.generated.jooq.tables.records.InventoryCountsRecord
import org.example.project.generated.jooq.tables.records.InventoryOrdersRecord
import org.example.project.generated.jooq.tables.records.InventorySnapshotRecord
import org.example.project.model.CurrentUser
import org.example.project.repository.WmsInventoryRepository
import org.jooq.DSLContext
import org.jooq.TransactionalCallable
import org.jooq.exception.DataAccessException
import java.math.BigDecimal
import java.security.MessageDigest

/**
 * Инвентаризация WMS (8.11 ТЗ, WMS-35/WMS-36).
 *
 * Правила:
 * - область: склад, зона/ячейка или выбранная номенклатура;
 * - учётный срез фиксируется снимком остатков в момент перехода DRAFT -> BOOKED
 *   и далее неизменяем; параллельные движения не блокируются, расхождение
 *   считается как «факт минус срез»;
 * - подсчёт добавляется комиссией, пересчёт спорных позиций — повторным
 *   подсчётом с флагом recount (в ведомость идёт последнее значение);
 * - ведомость расхождений материализуется при закрытии подсчёта;
 * - проведение применяет корректирующие движения ADJUSTMENT_IN/ADJUSTMENT_OUT
 *   и правит остатки в одной транзакции; недостача не уводит physical_qty
 *   ниже reserved_qty.
 */
class WmsInventoryService(
    private val inventoryRepository: WmsInventoryRepository,
    private val auditService: AuditService,
    private val dsl: DSLContext
) {

    data class CommandResponse(val status: HttpStatusCode, val body: String)

    fun create(request: CreateInventoryRequest, currentUser: CurrentUser): CommandResponse {
        currentUser.requirePermission("wms", "post")
        val payload = json.encodeToString(CreateInventoryRequest.serializer(), request)
        return executeIdempotent(
            "INVENTORY_CREATE", request.idempotencyKey, payload, HttpStatusCode.Created, currentUser
        ) {
            createInventory(request, currentUser).let { it.id to it }
        }
    }

    fun book(id: Long, request: InventoryActionRequest, currentUser: CurrentUser): CommandResponse {
        currentUser.requirePermission("wms", "post")
        val payload = json.encodeToString(InventoryActionRequest.serializer(), request)
        return executeIdempotent(
            "INVENTORY_BOOK", request.idempotencyKey, payload, HttpStatusCode.OK, currentUser
        ) {
            id to bookInventory(id, request, currentUser)
        }
    }

    fun submitCounts(id: Long, request: SubmitInventoryCountsRequest, currentUser: CurrentUser): CommandResponse {
        currentUser.requirePermission("wms", "post")
        if (request.counts.isEmpty()) {
            throw ValidationException("Counts are required", mapOf("counts" to "required"))
        }
        val payload = json.encodeToString(SubmitInventoryCountsRequest.serializer(), request)
        return executeIdempotent(
            "INVENTORY_COUNT", request.idempotencyKey, payload, HttpStatusCode.OK, currentUser
        ) {
            id to submitInventoryCounts(id, request, currentUser)
        }
    }

    fun approve(id: Long, request: InventoryActionRequest, currentUser: CurrentUser): CommandResponse {
        currentUser.requirePermission("wms", "manage")
        val payload = json.encodeToString(InventoryActionRequest.serializer(), request)
        return executeIdempotent(
            "INVENTORY_APPROVE", request.idempotencyKey, payload, HttpStatusCode.OK, currentUser
        ) {
            id to approveInventory(id, request, currentUser)
        }
    }

    fun post(id: Long, request: InventoryActionRequest, currentUser: CurrentUser): CommandResponse {
        currentUser.requirePermission("wms", "post")
        val payload = json.encodeToString(InventoryActionRequest.serializer(), request)
        return executeIdempotent(
            "INVENTORY_POST", request.idempotencyKey, payload, HttpStatusCode.OK, currentUser
        ) {
            id to postInventory(id, request, currentUser)
        }
    }

    fun cancel(id: Long, request: CancelInventoryRequest, currentUser: CurrentUser): CommandResponse {
        currentUser.requirePermission("wms", "post")
        if (request.reason.isBlank()) {
            throw ValidationException("Cancel reason is required", mapOf("reason" to "required"))
        }
        val payload = json.encodeToString(CancelInventoryRequest.serializer(), request)
        return executeIdempotent(
            "INVENTORY_CANCEL", request.idempotencyKey, payload, HttpStatusCode.OK, currentUser
        ) {
            val cancelled = inventoryRepository.markCancelled(
                id, request.version, request.reason.trim(), currentUser.userId
            ) ?: throw ConflictException("Inventory cannot be cancelled in its current state")
            auditService.log(
                currentUser.userId, "CANCEL", "INVENTORY", id,
                mapOf("number" to cancelled.number, "reason" to request.reason.trim())
            )
            id to cancelled.toDto(
                inventoryRepository.listSnapshot(id),
                inventoryRepository.latestCounts(id),
                inventoryRepository.latestDiscrepancies(id)
            )
        }
    }

    fun list(warehouseId: Long?, status: InventoryStatus?, currentUser: CurrentUser): List<InventoryDto> {
        currentUser.requirePermission("wms", "read")
        return inventoryRepository.listInventories(warehouseId, status?.name)
            .map { it.toDto(emptyList(), emptyMap<Long, InventoryCountsRecord>(), emptyList()) }
    }

    fun get(id: Long, currentUser: CurrentUser): InventoryDto {
        currentUser.requirePermission("wms", "read")
        val order = inventoryRepository.findInventoryById(id) ?: throw NotFoundException("inventory", id)
        return order.toDto(
            inventoryRepository.listSnapshot(id),
            inventoryRepository.latestCounts(id),
            inventoryRepository.latestDiscrepancies(id)
        )
    }

    // --- Реализация ---

    private fun createInventory(request: CreateInventoryRequest, currentUser: CurrentUser): InventoryDto {
        val warehouse = inventoryRepository.findWarehouseById(request.warehouseId)
            ?: throw NotFoundException("warehouse", request.warehouseId)
        if (warehouse.active != true) {
            throw ValidationException("Warehouse is inactive", mapOf("warehouseId" to "inactive"))
        }
        when (request.scopeType) {
            InventoryScopeType.WAREHOUSE -> {
                if (request.scopeLocationId != null || request.scopeNomenclatureId != null) {
                    throw ValidationException(
                        "Warehouse scope must not have location or nomenclature",
                        mapOf("scopeType" to "extra_scope")
                    )
                }
            }
            InventoryScopeType.ZONE, InventoryScopeType.CELL -> {
                val locationId = request.scopeLocationId
                    ?: throw ValidationException("Location is required for this scope", mapOf("scopeLocationId" to "required"))
                val location = inventoryRepository.findLocationById(locationId)
                    ?: throw NotFoundException("storage_location", locationId)
                if (location.warehouseId != request.warehouseId) {
                    throw ValidationException("Location belongs to another warehouse", mapOf("scopeLocationId" to "other_warehouse"))
                }
                if (request.scopeNomenclatureId != null) {
                    throw ValidationException("Nomenclature is not allowed for this scope", mapOf("scopeNomenclatureId" to "not_allowed"))
                }
            }
            InventoryScopeType.NOMENCLATURE -> {
                val nomenclatureId = request.scopeNomenclatureId
                    ?: throw ValidationException("Nomenclature is required", mapOf("scopeNomenclatureId" to "required"))
                inventoryRepository.findNomenclatureById(nomenclatureId)
                    ?: throw NotFoundException("nomenclature", nomenclatureId)
                if (request.scopeLocationId != null) {
                    throw ValidationException("Location is not allowed for nomenclature scope", mapOf("scopeLocationId" to "not_allowed"))
                }
            }
        }
        if (inventoryRepository.activeInventoryExists(request.warehouseId)) {
            throw ConflictException("Warehouse already has an active inventory; finish or cancel it first")
        }
        val number = inventoryRepository.generateNumber()
        val order = inventoryRepository.insertInventory(
            number = number,
            warehouseId = request.warehouseId,
            scopeType = request.scopeType.name,
            scopeLocationId = request.scopeLocationId,
            scopeNomenclatureId = request.scopeNomenclatureId,
            note = request.note?.takeIf { it.isNotBlank() }?.trim(),
            createdBy = currentUser.userId
        )
        auditService.log(
            currentUser.userId, "CREATE", "INVENTORY", order.id,
            mapOf("number" to number, "scopeType" to request.scopeType.name)
        )
        return order.toDto(emptyList(), emptyMap<Long, InventoryCountsRecord>(), emptyList())
    }

    private fun bookInventory(
        id: Long,
        request: InventoryActionRequest,
        currentUser: CurrentUser
    ): InventoryDto {
        val order = inventoryRepository.lockInventoryById(id) ?: throw NotFoundException("inventory", id)
        if (order.version != request.version) {
            throw ConflictException("Inventory was modified concurrently")
        }
        if (order.status != "DRAFT") {
            throw ConflictException("Inventory can be booked only from DRAFT status")
        }
        val balances = inventoryRepository.listBalancesInScope(
            warehouseId = order.warehouseId,
            scopeType = order.scopeType,
            scopeLocationId = order.scopeLocationId,
            scopeNomenclatureId = order.scopeNomenclatureId
        )
        if (balances.isEmpty()) {
            throw ConflictException("Inventory scope has no stock to count")
        }
        balances.forEach { balance ->
            inventoryRepository.insertSnapshot(
                inventoryId = id,
                locationId = balance.locationId,
                nomenclatureId = balance.nomenclatureId,
                lotId = balance.lotId,
                serialId = balance.serialId,
                bookQuantity = balance.physicalQty
            )
        }
        val booked = inventoryRepository.markBooked(id, request.version, currentUser.userId)
            ?: throw ConflictException("Inventory was modified concurrently")
        auditService.log(
            currentUser.userId, "BOOK", "INVENTORY", id,
            mapOf("number" to booked.number, "snapshotLines" to balances.size)
        )
        return booked.toDto(
            inventoryRepository.listSnapshot(id),
            emptyMap<Long, InventoryCountsRecord>(),
            emptyList()
        )
    }

    private fun submitInventoryCounts(
        id: Long,
        request: SubmitInventoryCountsRequest,
        currentUser: CurrentUser
    ): InventoryDto {
        val order = inventoryRepository.lockInventoryById(id) ?: throw NotFoundException("inventory", id)
        if (order.version != request.version) {
            throw ConflictException("Inventory was modified concurrently")
        }
        if (order.status !in setOf("BOOKED", "COUNTING", "DISCREPANCY")) {
            throw ConflictException("Counts are accepted only while the inventory is being counted")
        }
        val snapshot = inventoryRepository.listSnapshot(id).associateBy { it.id }
        request.counts.forEach { count ->
            if (snapshot[count.snapshotId] == null) {
                throw ValidationException(
                    "Snapshot line ${count.snapshotId} does not belong to this inventory",
                    mapOf("snapshotId" to "mismatch")
                )
            }
        }
        request.counts.forEach { count ->
            inventoryRepository.insertCount(
                inventoryId = id,
                snapshotId = count.snapshotId,
                countedQuantity = parseQuantity(count.countedQuantity, "countedQuantity"),
                recount = count.recount,
                note = count.note?.takeIf { it.isNotBlank() }?.trim(),
                countedBy = currentUser.userId
            )
        }
        val latest = inventoryRepository.latestCounts(id)
        val missing = snapshot.keys - latest.keys
        if (missing.isNotEmpty()) {
            // Пока подсчитана не вся область, документ остаётся в COUNTING.
            val counted = inventoryRepository.markCounted(id, request.version, currentUser.userId, "COUNTING")
                ?: throw ConflictException("Inventory was modified concurrently")
            auditService.log(
                currentUser.userId, "COUNT_PARTIAL", "INVENTORY", id,
                mapOf("remaining" to missing.size)
            )
            return counted.toDto(
                inventoryRepository.listSnapshot(id),
                latest,
                inventoryRepository.latestDiscrepancies(id)
            )
        }
        // Все строки подсчитаны: материализуется ведомость расхождений.
        val discrepancies = mutableListOf<InventoryDiscrepanciesRecord>()
        snapshot.values.forEach { line ->
            val countedRecord = latest.getValue(line.id)
            val book = line.bookQuantity
            val counted = countedRecord.countedQuantity
            val discrepancy = counted.minus(book)
            val kind = when {
                discrepancy.signum() > 0 -> "SURPLUS"
                discrepancy.signum() < 0 -> "SHORTAGE"
                else -> null
            }
            discrepancies += inventoryRepository.insertDiscrepancy(
                inventoryId = id,
                snapshotId = line.id,
                locationId = line.locationId,
                nomenclatureId = line.nomenclatureId,
                lotId = line.lotId,
                serialId = line.serialId,
                bookQuantity = book,
                countedQuantity = counted,
                discrepancy = discrepancy,
                kind = kind,
                note = countedRecord.note
            )
        }
        val hasDiscrepancy = discrepancies.any { it.kind != null }
        val status = if (hasDiscrepancy) "DISCREPANCY" else "COUNTING"
        val counted = inventoryRepository.markCounted(id, request.version, currentUser.userId, status)
            ?: throw ConflictException("Inventory was modified concurrently")
        auditService.log(
            currentUser.userId, "COUNT", "INVENTORY", id,
            mapOf(
                "number" to counted.number,
                "lines" to discrepancies.size,
                "discrepancies" to discrepancies.count { it.kind != null }
            )
        )
        return counted.toDto(inventoryRepository.listSnapshot(id), latest, discrepancies)
    }

    private fun approveInventory(
        id: Long,
        request: InventoryActionRequest,
        currentUser: CurrentUser
    ): InventoryDto {
        val order = inventoryRepository.lockInventoryById(id) ?: throw NotFoundException("inventory", id)
        if (order.version != request.version) {
            throw ConflictException("Inventory was modified concurrently")
        }
        val discrepancies = inventoryRepository.latestDiscrepancies(id)
        if (discrepancies.none { it.kind != null }) {
            throw ValidationException(
                "Inventory has no discrepancies to approve",
                mapOf("status" to "no_discrepancy")
            )
        }
        val approved = inventoryRepository.markApproved(id, request.version, currentUser.userId)
            ?: throw ConflictException("Inventory can be approved only after counting")
        auditService.log(
            currentUser.userId, "APPROVE", "INVENTORY", id,
            mapOf("number" to approved.number, "discrepancies" to discrepancies.count { it.kind != null })
        )
        return approved.toDto(
            inventoryRepository.listSnapshot(id),
            inventoryRepository.latestCounts(id),
            discrepancies
        )
    }

    private fun postInventory(
        id: Long,
        request: InventoryActionRequest,
        currentUser: CurrentUser
    ): InventoryDto {
        val order = inventoryRepository.lockInventoryById(id) ?: throw NotFoundException("inventory", id)
        if (order.version != request.version) {
            throw ConflictException("Inventory was modified concurrently")
        }
        val discrepancies = inventoryRepository.latestDiscrepancies(id).filter { it.kind != null }
        val allowedStatuses = if (discrepancies.isEmpty()) {
            setOf("COUNTING", "DISCREPANCY", "APPROVED")
        } else {
            setOf("DISCREPANCY", "APPROVED")
        }
        if (order.status !in allowedStatuses) {
            throw ConflictException("Inventory can be posted only after counting")
        }
        if (discrepancies.isEmpty()) {
            val postedNoChanges = inventoryRepository.markPosted(id, request.version, currentUser.userId)
                ?: throw ConflictException("Inventory was modified concurrently")
            return postedNoChanges.toDto(
                inventoryRepository.listSnapshot(id),
                inventoryRepository.latestCounts(id),
                inventoryRepository.latestDiscrepancies(id)
            )
        }
        // Блокировки в детерминированном порядке против дедлоков.
        val keys = discrepancies.map {
            WmsInventoryRepository.BalanceKey(
                warehouseId = order.warehouseId,
                locationId = it.locationId,
                nomenclatureId = it.nomenclatureId,
                lotId = it.lotId,
                serialId = it.serialId
            )
        }
        val balances = mutableMapOf<WmsInventoryRepository.BalanceKey, org.example.project.generated.jooq.tables.records.StockBalanceRecord?>()
        keys.distinct().sorted().forEach { key -> balances[key] = inventoryRepository.lockBalance(key) }

        discrepancies.forEach { line ->
            val key = WmsInventoryRepository.BalanceKey(
                warehouseId = order.warehouseId,
                locationId = line.locationId,
                nomenclatureId = line.nomenclatureId,
                lotId = line.lotId,
                serialId = line.serialId
            )
            val quantity = line.discrepancy.abs()
            if (quantity.signum() == 0) return@forEach
            if (line.kind == "SURPLUS") {
                inventoryRepository.increasePhysical(key, order.warehouseId, quantity)
                inventoryRepository.insertMovement(
                    movementType = "ADJUSTMENT_IN",
                    nomenclatureId = line.nomenclatureId,
                    lotId = line.lotId,
                    serialId = line.serialId,
                    locationId = line.locationId,
                    quantity = quantity,
                    documentId = id,
                    note = "Инвентаризация ${order.number}: излишек",
                    createdBy = currentUser.userId
                )
            } else {
                val balance = balances[key]
                    ?: throw ConflictException(
                        "Stock balance for nomenclature ${line.nomenclatureId} at location " +
                            "${line.locationId} disappeared during inventory"
                    )
                // Недостача не может увести остаток ниже зарезервированного.
                val writable = balance.physicalQty.minus(balance.reservedQty)
                if (writable < quantity) {
                    throw ConflictException(
                        "Shortage for nomenclature ${line.nomenclatureId} at location " +
                            "${line.locationId} exceeds free stock: shortage ${quantity.toPlainString()}, " +
                            "free ${writable.toPlainString()}; release reservations first"
                    )
                }
                if (inventoryRepository.decreasePhysical(balance.id, quantity) != 1) {
                    throw ConflictException("Stock balance was modified concurrently")
                }
                inventoryRepository.insertMovement(
                    movementType = "ADJUSTMENT_OUT",
                    nomenclatureId = line.nomenclatureId,
                    lotId = line.lotId,
                    serialId = line.serialId,
                    locationId = line.locationId,
                    quantity = quantity,
                    documentId = id,
                    note = "Инвентаризация ${order.number}: недостача",
                    createdBy = currentUser.userId
                )
            }
        }
        val posted = inventoryRepository.markPosted(id, request.version, currentUser.userId)
            ?: throw ConflictException("Inventory was modified concurrently")
        auditService.log(
            currentUser.userId, "POST", "INVENTORY", id,
            mapOf(
                "number" to posted.number,
                "surplusLines" to discrepancies.count { it.kind == "SURPLUS" },
                "shortageLines" to discrepancies.count { it.kind == "SHORTAGE" }
            )
        )
        return posted.toDto(
            inventoryRepository.listSnapshot(id),
            inventoryRepository.latestCounts(id),
            inventoryRepository.latestDiscrepancies(id)
        )
    }

    private fun parseQuantity(value: String, field: String): BigDecimal {
        val parsed = value.trim().toBigDecimalOrNull()
            ?: throw ValidationException("Invalid quantity: $value", mapOf(field to "invalid"))
        if (parsed.signum() < 0) {
            throw ValidationException("Quantity must not be negative", mapOf(field to "negative"))
        }
        return parsed
    }

    private fun executeIdempotent(
        operation: String,
        idempotencyKey: String,
        requestPayload: String,
        successStatus: HttpStatusCode,
        currentUser: CurrentUser,
        work: () -> Pair<Long, InventoryDto>
    ): CommandResponse {
        if (idempotencyKey.isBlank()) {
            throw ValidationException("Idempotency key is required", mapOf("idempotencyKey" to "required"))
        }
        val requestHash = sha256("$operation|$requestPayload")
        try {
            return dsl.transactionResult(TransactionalCallable {
                val existing = inventoryRepository.findIdempotency(currentUser.userId, operation, idempotencyKey)
                if (existing != null) {
                    if (existing.requestHash != requestHash) {
                        throw ConflictException("Idempotency key was already used with a different request")
                    }
                    return@TransactionalCallable CommandResponse(
                        HttpStatusCode.fromValue(existing.responseStatus),
                        existing.responseBody
                    )
                }
                val (entityId, dto) = work()
                val body = json.encodeToString(
                    ApiResponse.serializer(InventoryDto.serializer() as KSerializer<Any?>),
                    ApiResponse.success(dto)
                )
                inventoryRepository.insertIdempotency(
                    currentUser.userId, operation, idempotencyKey, requestHash, entityId,
                    successStatus.value, body
                )
                CommandResponse(successStatus, body)
            })
        } catch (e: DataAccessException) {
            if (e.sqlState() == "23505" && e.message?.contains("uq_wms_inventory_idempotency") == true) {
                val existing = inventoryRepository.findIdempotency(currentUser.userId, operation, idempotencyKey)
                if (existing != null && existing.requestHash == requestHash) {
                    return CommandResponse(HttpStatusCode.fromValue(existing.responseStatus), existing.responseBody)
                }
                throw ConflictException("Idempotency key was already used with a different request")
            }
            throw e
        }
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private val json = Json { encodeDefaults = true }
    }
}

private fun InventorySnapshotRecord.toDto(counted: BigDecimal? = null, recount: Boolean = false): InventorySnapshotDto =
    InventorySnapshotDto(
        id = this.id,
        inventoryId = this.inventoryId,
        locationId = this.locationId,
        nomenclatureId = this.nomenclatureId,
        lotId = this.lotId,
        serialId = this.serialId,
        bookQuantity = this.bookQuantity.toPlainString(),
        countedQuantity = counted?.toPlainString(),
        recount = recount
    )

private fun InventoryDiscrepanciesRecord.toDto(): InventoryDiscrepancyDto = InventoryDiscrepancyDto(
    id = this.id,
    inventoryId = this.inventoryId,
    snapshotId = this.snapshotId,
    locationId = this.locationId,
    nomenclatureId = this.nomenclatureId,
    lotId = this.lotId,
    serialId = this.serialId,
    bookQuantity = this.bookQuantity.toPlainString(),
    countedQuantity = this.countedQuantity.toPlainString(),
    discrepancy = this.discrepancy.toPlainString(),
    kind = this.kind?.let { InventoryDiscrepancyKind.valueOf(it) },
    note = this.note
)

private fun InventoryOrdersRecord.toDto(
    snapshot: List<InventorySnapshotRecord>,
    counts: Map<Long, InventoryCountsRecord>,
    discrepancies: List<InventoryDiscrepanciesRecord>
): InventoryDto = InventoryDto(
    id = this.id,
    number = this.number,
    warehouseId = this.warehouseId,
    scopeType = InventoryScopeType.valueOf(this.scopeType),
    scopeLocationId = this.scopeLocationId,
    scopeNomenclatureId = this.scopeNomenclatureId,
    status = InventoryStatus.valueOf(this.status),
    note = this.note,
    bookedAt = this.bookedAt?.toString(),
    bookedBy = this.bookedBy,
    countedAt = this.countedAt?.toString(),
    countedBy = this.countedBy,
    approvedAt = this.approvedAt?.toString(),
    approvedBy = this.approvedBy,
    postedAt = this.postedAt?.toString(),
    postedBy = this.postedBy,
    cancelledAt = this.cancelledAt?.toString(),
    cancelReason = this.cancelReason,
    createdAt = this.createdAt?.toString(),
    createdBy = this.createdBy,
    version = this.version ?: 0,
    snapshot = snapshot.map { line ->
        val counted = counts[line.id]
        line.toDto(counted?.countedQuantity, counted?.recount ?: false)
            .copy(countedAt = counted?.createdAt?.toString())
    },
    discrepancies = discrepancies.map { it.toDto() }
)
