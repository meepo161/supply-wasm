package org.example.project.service

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import org.example.project.dto.ApiResponse
import org.example.project.dto.CancelWriteOffRequest
import org.example.project.dto.CreateWriteOffRequest
import org.example.project.dto.SetWriteOffLimitRequest
import org.example.project.dto.WriteOffActionRequest
import org.example.project.dto.WriteOffDto
import org.example.project.dto.WriteOffLimitDto
import org.example.project.dto.WriteOffLineDto
import org.example.project.dto.WriteOffReasonType
import org.example.project.dto.WriteOffStatus
import org.example.project.exception.ConflictException
import org.example.project.exception.NotFoundException
import org.example.project.exception.ValidationException
import org.example.project.generated.jooq.tables.records.StockBalanceRecord
import org.example.project.generated.jooq.tables.records.WriteOffLinesRecord
import org.example.project.generated.jooq.tables.records.WriteOffOrdersRecord
import org.example.project.model.CurrentUser
import org.example.project.repository.WmsWriteOffRepository
import org.jooq.DSLContext
import org.jooq.TransactionalCallable
import org.jooq.exception.DataAccessException
import java.math.BigDecimal
import java.security.MessageDigest

/**
 * Списание WMS (8.10 ТЗ, WMS-33/WMS-34).
 *
 * Правила:
 * - списывается только свободный остаток (physical - reserved), строки считаются
 *   по ключу склад + ячейка + номенклатура + партия + серия;
 * - проведение атомарно: проверка остатка, уменьшение physical_qty, движение
 *   stock_movement WRITE_OFF и перевод документа в POSTED — одна транзакция;
 * - сверх лимита склада проведение требует утверждённого маршрута согласования
 *   (WRITE_OFF) либо права wms:write_off_over_limit и основания не короче 10 символов;
 * - повтор команды с тем же телом возвращает сохранённый ответ (идемпотентность).
 */
class WmsWriteOffService(
    private val writeOffRepository: WmsWriteOffRepository,
    private val auditService: AuditService,
    private val dsl: DSLContext
) {

    data class CommandResponse(val status: HttpStatusCode, val body: String)

    fun create(request: CreateWriteOffRequest, currentUser: CurrentUser): CommandResponse {
        currentUser.requirePermission("wms", "post")
        val lines = normalizeLines(request)
        val payload = json.encodeToString(CreateWriteOffRequest.serializer(), request)
        return executeIdempotent(
            "WRITE_OFF_CREATE", request.idempotencyKey, payload, HttpStatusCode.Created, currentUser
        ) {
            createWriteOff(request, lines, currentUser).let { it.id to it }
        }
    }

    fun post(id: Long, request: WriteOffActionRequest, currentUser: CurrentUser): CommandResponse {
        currentUser.requirePermission("wms", "post")
        val payload = json.encodeToString(WriteOffActionRequest.serializer(), request)
        return executeIdempotent(
            "WRITE_OFF_POST", request.idempotencyKey, payload, HttpStatusCode.OK, currentUser
        ) {
            id to postWriteOff(id, request, currentUser)
        }
    }

    fun cancel(id: Long, request: CancelWriteOffRequest, currentUser: CurrentUser): CommandResponse {
        currentUser.requirePermission("wms", "post")
        if (request.reason.isBlank()) {
            throw ValidationException("Cancel reason is required", mapOf("reason" to "required"))
        }
        val payload = json.encodeToString(CancelWriteOffRequest.serializer(), request)
        return executeIdempotent(
            "WRITE_OFF_CANCEL", request.idempotencyKey, payload, HttpStatusCode.OK, currentUser
        ) {
            val cancelled = writeOffRepository.markCancelled(
                id, request.version, request.reason.trim(), currentUser.userId
            ) ?: throw ConflictException("Write-off cannot be cancelled in its current state")
            auditService.log(
                currentUser.userId, "CANCEL", "WRITE_OFF", id,
                mapOf("number" to cancelled.number, "reason" to request.reason.trim())
            )
            id to cancelled.toDto(writeOffRepository.listLines(id))
        }
    }

    fun list(warehouseId: Long?, status: WriteOffStatus?, currentUser: CurrentUser): List<WriteOffDto> {
        currentUser.requirePermission("wms", "read")
        val lines = mutableMapOf<Long, List<WriteOffLinesRecord>>()
        return writeOffRepository.listWriteOffs(warehouseId, status?.name).map { order ->
            val orderLines = lines.getOrPut(order.id) { writeOffRepository.listLines(order.id) }
            order.toDto(orderLines)
        }
    }

    fun get(id: Long, currentUser: CurrentUser): WriteOffDto {
        currentUser.requirePermission("wms", "read")
        val order = writeOffRepository.findWriteOffById(id) ?: throw NotFoundException("write_off", id)
        return order.toDto(writeOffRepository.listLines(id))
    }

    fun getLimit(warehouseId: Long, currentUser: CurrentUser): WriteOffLimitDto {
        currentUser.requirePermission("wms", "read")
        if (writeOffRepository.findWarehouseById(warehouseId) == null) {
            throw NotFoundException("warehouse", warehouseId)
        }
        return WriteOffLimitDto(
            warehouseId = warehouseId,
            limitQuantity = writeOffRepository.findWarehouseLimit(warehouseId).toPlainString()
        )
    }

    fun setLimit(
        warehouseId: Long,
        request: SetWriteOffLimitRequest,
        currentUser: CurrentUser
    ): WriteOffLimitDto {
        currentUser.requirePermission("wms", "manage")
        if (writeOffRepository.findWarehouseById(warehouseId) == null) {
            throw NotFoundException("warehouse", warehouseId)
        }
        val limit = parseNonNegative(request.limitQuantity, "limitQuantity")
        writeOffRepository.upsertWarehouseLimit(warehouseId, limit, currentUser.userId)
        auditService.log(
            currentUser.userId, "SET_LIMIT", "WAREHOUSE", warehouseId,
            mapOf("limitQuantity" to limit.toPlainString())
        )
        return WriteOffLimitDto(warehouseId = warehouseId, limitQuantity = limit.toPlainString())
    }

    /**
     * Вызывается адаптером согласования при старте маршрута: черновик сверх
     * лимита переводится в PENDING_APPROVAL, а в маршрут уходит новая версия.
     * Повторный старт при уже активном маршруте отклоняется.
     */
    fun startApproval(id: Long, currentUser: CurrentUser): Long {
        val order = writeOffRepository.lockWriteOffById(id) ?: throw NotFoundException("write_off", id)
        if (order.status != "DRAFT") {
            throw ConflictException("Approval can be started only for a draft write-off")
        }
        val limit = writeOffRepository.findWarehouseLimit(order.warehouseId)
        if (order.totalQuantity <= limit) {
            throw ValidationException(
                "Write-off total does not exceed the warehouse limit; post it directly",
                mapOf("totalQuantity" to "within_limit")
            )
        }
        if (writeOffRepository.hasActiveApprovalRoute(id)) {
            throw ConflictException("Write-off already has an active approval route")
        }
        val version = order.version ?: 0
        val updated = writeOffRepository.markPendingApproval(
            id = id,
            expectedVersion = version,
            overLimit = true,
            limitQuantity = limit
        ) ?: throw ConflictException("Write-off was modified concurrently")
        auditService.log(
            currentUser.userId, "SUBMIT", "WRITE_OFF", id,
            mapOf("number" to order.number, "limitQuantity" to limit.toPlainString())
        )
        return updated.version ?: 0
    }

    fun currentVersion(id: Long): Long =
        (writeOffRepository.findWriteOffById(id) ?: throw NotFoundException("write_off", id)).version ?: 0

    fun readAllowed(id: Long, currentUser: CurrentUser) {
        currentUser.requirePermission("wms", "read")
        writeOffRepository.findWriteOffById(id) ?: throw NotFoundException("write_off", id)
    }

    // Применяется из WriteOffApprovalTarget при завершении маршрута.
    fun applyApproval(id: Long, expectedVersion: Long, decidedBy: Long): WriteOffDto {
        val approved = writeOffRepository.markApproved(id, expectedVersion, decidedBy)
            ?: throw ConflictException("Write-off changed since the approval route started")
        auditService.log(
            decidedBy, "APPROVE", "WRITE_OFF", id,
            mapOf("number" to approved.number, "via" to "approval_route")
        )
        return approved.toDto(writeOffRepository.listLines(id))
    }

    // --- Реализация ---

    private fun createWriteOff(
        request: CreateWriteOffRequest,
        lines: List<NormalizedLine>,
        currentUser: CurrentUser
    ): WriteOffDto {
        if (request.reasonNote.isBlank()) {
            throw ValidationException("Reason note is required", mapOf("reasonNote" to "required"))
        }
        if (request.reasonType == WriteOffReasonType.OTHER && request.reasonNote.trim().length < 10) {
            throw ValidationException(
                "Reason note must be at least 10 characters for OTHER reason",
                mapOf("reasonNote" to "too_short")
            )
        }
        val warehouse = writeOffRepository.findWarehouseById(request.warehouseId)
            ?: throw NotFoundException("warehouse", request.warehouseId)
        if (warehouse.active != true) {
            throw ValidationException("Warehouse is inactive", mapOf("warehouseId" to "inactive"))
        }
        lines.forEach { line ->
            val nomenclature = writeOffRepository.findNomenclatureById(line.nomenclatureId)
                ?: throw NotFoundException("nomenclature", line.nomenclatureId)
            if (nomenclature.archived == true) {
                throw ConflictException("Nomenclature is archived: ${nomenclature.code}")
            }
            val location = writeOffRepository.findLocationById(line.locationId)
                ?: throw NotFoundException("storage_location", line.locationId)
            if (location.warehouseId != request.warehouseId) {
                throw ValidationException(
                    "Location does not belong to the write-off warehouse",
                    mapOf("locationId" to "other_warehouse")
                )
            }
            if (location.active != true) {
                throw ValidationException("Location is inactive", mapOf("locationId" to "inactive"))
            }
            if (line.serialId != null && line.quantity.compareTo(BigDecimal.ONE) != 0) {
                throw ValidationException(
                    "Serial-tracked write-off quantity must be exactly 1",
                    mapOf("quantity" to "not_serial_unit")
                )
            }
        }
        val total = lines.fold(BigDecimal.ZERO) { acc, line -> acc + line.quantity }
        val number = writeOffRepository.generateNumber()
        val order = writeOffRepository.insertWriteOff(
            number = number,
            warehouseId = request.warehouseId,
            reasonType = request.reasonType.name,
            reasonNote = request.reasonNote.trim(),
            note = request.note?.takeIf { it.isNotBlank() }?.trim(),
            totalQuantity = total,
            createdBy = currentUser.userId
        )
        lines.forEach { line ->
            writeOffRepository.insertLine(
                writeOffId = order.id,
                lineNo = line.lineNo,
                nomenclatureId = line.nomenclatureId,
                locationId = line.locationId,
                lotId = line.lotId,
                serialId = line.serialId,
                quantity = line.quantity,
                note = line.note
            )
        }
        auditService.log(
            currentUser.userId, "CREATE", "WRITE_OFF", order.id,
            mapOf("number" to number, "lines" to lines.size, "quantity" to total.toPlainString())
        )
        return order.toDto(writeOffRepository.listLines(order.id))
    }

    private fun postWriteOff(
        id: Long,
        request: WriteOffActionRequest,
        currentUser: CurrentUser
    ): WriteOffDto {
        val order = writeOffRepository.lockWriteOffById(id) ?: throw NotFoundException("write_off", id)
        if (order.version != request.version) {
            throw ConflictException("Write-off was modified concurrently")
        }
        if (order.status !in setOf("DRAFT", "APPROVED")) {
            throw ConflictException("Write-off can be posted only from DRAFT or APPROVED status")
        }
        val lines = writeOffRepository.listLines(id)
        if (lines.isEmpty()) {
            throw ValidationException("Write-off has no lines", mapOf("lines" to "required"))
        }
        val limit = writeOffRepository.findWarehouseLimit(order.warehouseId)
        val overLimit = order.totalQuantity > limit
        if (overLimit && order.status != "APPROVED") {
            val canOverride = currentUser.hasPermission("wms", "write_off_over_limit")
            if (!canOverride) {
                throw ConflictException(
                    "Write-off exceeds the warehouse limit ${limit.toPlainString()}; " +
                        "approval route or permission wms:write_off_over_limit is required"
                )
            }
            if (order.reasonNote.trim().length < 10) {
                throw ValidationException(
                    "Write-off over the limit requires a reason note of at least 10 characters",
                    mapOf("reasonNote" to "too_short")
                )
            }
        }

        // Строки блокируются в детерминированном порядке против дедлоков.
        val grouped = lines.groupBy {
            WmsWriteOffRepository.BalanceKey(
                warehouseId = order.warehouseId,
                locationId = it.locationId,
                nomenclatureId = it.nomenclatureId,
                lotId = it.lotId,
                serialId = it.serialId
            )
        }
        val balances = mutableMapOf<WmsWriteOffRepository.BalanceKey, StockBalanceRecord>()
        grouped.keys.sorted().forEach { key ->
            val balance = writeOffRepository.lockBalance(key)
                ?: throw ConflictException(
                    "No stock balance for nomenclature ${key.nomenclatureId} " +
                        "at location ${key.locationId}"
                )
            balances[key] = balance
        }
        grouped.forEach { (key, lineRows) ->
            val balance = balances.getValue(key)
            val required = lineRows.fold(BigDecimal.ZERO) { acc, line -> acc + line.quantity }
            val free = balance.physicalQty.minus(balance.reservedQty)
            if (free < required) {
                throw ConflictException(
                    "Insufficient free stock for nomenclature ${key.nomenclatureId} " +
                        "at location ${key.locationId}: required ${required.toPlainString()}, " +
                        "available ${free.toPlainString()}"
                )
            }
            if (writeOffRepository.decreaseFreePhysical(balance.id, required) != 1) {
                throw ConflictException("Stock balance was modified concurrently")
            }
        }
        lines.forEach { line ->
            writeOffRepository.insertMovement(
                movementType = "WRITE_OFF",
                nomenclatureId = line.nomenclatureId,
                lotId = line.lotId,
                serialId = line.serialId,
                fromLocationId = line.locationId,
                quantity = line.quantity,
                documentId = id,
                note = order.reasonNote,
                createdBy = currentUser.userId
            )
        }
        val posted = writeOffRepository.markPosted(id, request.version, currentUser.userId)
            ?: throw ConflictException("Write-off was modified concurrently")
        auditService.log(
            currentUser.userId, "POST", "WRITE_OFF", id,
            mapOf(
                "number" to posted.number,
                "quantity" to posted.totalQuantity.toPlainString(),
                "overLimit" to overLimit
            )
        )
        return posted.toDto(writeOffRepository.listLines(id))
    }

    private data class NormalizedLine(
        val lineNo: Int,
        val nomenclatureId: Long,
        val locationId: Long,
        val lotId: Long?,
        val serialId: Long?,
        val quantity: BigDecimal,
        val note: String?
    )

    private fun normalizeLines(request: CreateWriteOffRequest): List<NormalizedLine> {
        if (request.lines.isEmpty()) {
            throw ValidationException("Write-off must have at least one line", mapOf("lines" to "required"))
        }
        val lineNos = request.lines.map { it.lineNo }
        if (lineNos.size != lineNos.distinct().size) {
            throw ValidationException("Line numbers must be unique", mapOf("lineNo" to "duplicate"))
        }
        return request.lines.map { line ->
            NormalizedLine(
                lineNo = line.lineNo,
                nomenclatureId = line.nomenclatureId,
                locationId = line.locationId,
                lotId = line.lotId,
                serialId = line.serialId,
                quantity = parseQuantity(line.quantity, "quantity"),
                note = line.note?.takeIf { it.isNotBlank() }?.trim()
            )
        }
    }

    private fun parseQuantity(value: String, field: String): BigDecimal {
        val parsed = value.trim().replace(" ", "").replace(',', '.').toBigDecimalOrNull()
            ?: throw ValidationException("Invalid quantity: $value", mapOf(field to "invalid"))
        if (parsed.signum() <= 0) {
            throw ValidationException("Quantity must be positive", mapOf(field to "not_positive"))
        }
        return parsed
    }

    private fun parseNonNegative(value: String, field: String): BigDecimal {
        val parsed = value.trim().replace(" ", "").replace(',', '.').toBigDecimalOrNull()
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
        work: () -> Pair<Long, WriteOffDto>
    ): CommandResponse {
        if (idempotencyKey.isBlank()) {
            throw ValidationException("Idempotency key is required", mapOf("idempotencyKey" to "required"))
        }
        val requestHash = sha256("$operation|$requestPayload")
        try {
            return dsl.transactionResult(TransactionalCallable {
                val existing = writeOffRepository.findIdempotency(currentUser.userId, operation, idempotencyKey)
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
                    ApiResponse.serializer(WriteOffDto.serializer() as KSerializer<Any?>),
                    ApiResponse.success(dto)
                )
                writeOffRepository.insertIdempotency(
                    currentUser.userId, operation, idempotencyKey, requestHash, entityId,
                    successStatus.value, body
                )
                CommandResponse(successStatus, body)
            })
        } catch (e: DataAccessException) {
            if (e.sqlState() == "23505" && e.message?.contains("uq_wms_write_off_idempotency") == true) {
                val existing = writeOffRepository.findIdempotency(currentUser.userId, operation, idempotencyKey)
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

private fun WriteOffLinesRecord.toDto(): WriteOffLineDto = WriteOffLineDto(
    id = this.id,
    writeOffId = this.writeOffId,
    lineNo = this.lineNo,
    nomenclatureId = this.nomenclatureId,
    locationId = this.locationId,
    lotId = this.lotId,
    serialId = this.serialId,
    quantity = this.quantity.toPlainString(),
    note = this.note
)

private fun WriteOffOrdersRecord.toDto(lines: List<WriteOffLinesRecord>): WriteOffDto = WriteOffDto(
    id = this.id,
    number = this.number,
    warehouseId = this.warehouseId,
    reasonType = WriteOffReasonType.valueOf(this.reasonType),
    reasonNote = this.reasonNote,
    status = WriteOffStatus.valueOf(this.status),
    totalQuantity = this.totalQuantity.toPlainString(),
    overLimit = this.overLimit,
    limitQuantity = this.limitQuantity?.toPlainString(),
    approvalRouteId = this.approvalRouteId,
    approvedAt = this.approvedAt?.toString(),
    approvedBy = this.approvedBy,
    postedAt = this.postedAt?.toString(),
    postedBy = this.postedBy,
    cancelledAt = this.cancelledAt?.toString(),
    cancelReason = this.cancelReason,
    createdAt = this.createdAt?.toString(),
    createdBy = this.createdBy,
    version = this.version ?: 0,
    lines = lines.map { it.toDto() }
)
