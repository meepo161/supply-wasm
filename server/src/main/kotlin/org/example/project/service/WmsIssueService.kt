package org.example.project.service

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import org.example.project.dto.*
import org.example.project.exception.ConflictException
import org.example.project.exception.ForbiddenException
import org.example.project.exception.NotFoundException
import org.example.project.exception.ValidationException
import org.example.project.generated.jooq.tables.records.IssueLinesRecord
import org.example.project.generated.jooq.tables.records.IssueOrdersRecord
import org.example.project.generated.jooq.tables.records.IssuePicksRecord
import org.example.project.generated.jooq.tables.records.ReturnLinesRecord
import org.example.project.generated.jooq.tables.records.ReturnOrdersRecord
import org.example.project.generated.jooq.tables.records.StockBalanceRecord
import org.example.project.generated.jooq.tables.records.StorageLocationsRecord
import org.example.project.model.CurrentUser
import org.example.project.repository.WmsIssueRepository
import org.jooq.DSLContext
import org.jooq.TransactionalCallable
import org.jooq.exception.DataAccessException
import java.math.BigDecimal
import java.security.MessageDigest

class WmsIssueService(
    private val issueRepository: WmsIssueRepository,
    private val auditService: AuditService,
    private val dsl: DSLContext
) {

    data class CommandResponse(val status: HttpStatusCode, val body: String)

    // Выдача

    fun createIssue(request: CreateIssueOrderRequest, currentUser: CurrentUser): CommandResponse {
        currentUser.requirePermission("wms", "post")
        val payload = json.encodeToString(CreateIssueOrderRequest.serializer(), request)
        return executeIdempotent(
            "ISSUE_CREATE", request.idempotencyKey, payload, HttpStatusCode.Created, "ISSUE", currentUser,
            IssueOrderDto.serializer()
        ) {
            createIssueOrder(request, currentUser)
        }
    }

    fun addPicks(id: Long, request: PickIssueRequest, currentUser: CurrentUser): CommandResponse {
        currentUser.requirePermission("wms", "post")
        if (request.picks.isEmpty()) {
            throw ValidationException("Picks are required", mapOf("picks" to "required"))
        }
        val payload = json.encodeToString(PickIssueRequest.serializer(), request)
        return executeIdempotent(
            "ISSUE_PICK", request.idempotencyKey, payload, HttpStatusCode.OK, "ISSUE", currentUser,
            IssueOrderDto.serializer()
        ) {
            id to addIssuePicks(id, request, currentUser)
        }
    }

    fun issue(id: Long, request: IssueActionRequest, currentUser: CurrentUser): CommandResponse {
        currentUser.requirePermission("wms", "post")
        val payload = json.encodeToString(IssueActionRequest.serializer(), request)
        return executeIdempotent(
            "ISSUE_HANDOVER", request.idempotencyKey, payload, HttpStatusCode.OK, "ISSUE", currentUser,
            IssueOrderDto.serializer()
        ) {
            id to issueOrder(id, request, currentUser)
        }
    }

    fun confirm(id: Long, request: IssueActionRequest, currentUser: CurrentUser): CommandResponse {
        currentUser.requirePermission("wms", "post")
        val payload = json.encodeToString(IssueActionRequest.serializer(), request)
        return executeIdempotent(
            "ISSUE_CONFIRM", request.idempotencyKey, payload, HttpStatusCode.OK, "ISSUE", currentUser,
            IssueOrderDto.serializer()
        ) {
            id to confirmIssue(id, request, currentUser)
        }
    }

    fun refuse(id: Long, request: RefuseIssueRequest, currentUser: CurrentUser): CommandResponse {
        currentUser.requirePermission("wms", "post")
        if (request.reason.isBlank()) {
            throw ValidationException("Refusal reason is required", mapOf("reason" to "required"))
        }
        val payload = json.encodeToString(RefuseIssueRequest.serializer(), request)
        return executeIdempotent(
            "ISSUE_REFUSE", request.idempotencyKey, payload, HttpStatusCode.OK, "ISSUE", currentUser,
            IssueOrderDto.serializer()
        ) {
            id to refuseIssue(id, request, currentUser)
        }
    }

    fun cancel(id: Long, request: IssueActionRequest, currentUser: CurrentUser): CommandResponse {
        currentUser.requirePermission("wms", "post")
        val payload = json.encodeToString(IssueActionRequest.serializer(), request)
        return executeIdempotent(
            "ISSUE_CANCEL", request.idempotencyKey, payload, HttpStatusCode.OK, "ISSUE", currentUser,
            IssueOrderDto.serializer()
        ) {
            id to cancelIssue(id, request, currentUser)
        }
    }

    fun listIssues(warehouseId: Long?, status: IssueStatus?, currentUser: CurrentUser): List<IssueOrderDto> {
        currentUser.requirePermission("wms", "read")
        return issueRepository.listIssues(warehouseId, status?.name).map { it.toDto(emptyList()) }
    }

    fun getIssue(id: Long, currentUser: CurrentUser): IssueOrderDto {
        currentUser.requirePermission("wms", "read")
        val order = issueRepository.findIssueById(id) ?: throw NotFoundException("issue_order", id)
        return order.toDto(loadLinesWithPicks(id))
    }

    // Возвраты

    fun createReturn(request: CreateReturnOrderRequest, currentUser: CurrentUser): CommandResponse {
        currentUser.requirePermission("wms", "post")
        if (request.reason.isBlank()) {
            throw ValidationException("Return reason is required", mapOf("reason" to "required"))
        }
        val payload = json.encodeToString(CreateReturnOrderRequest.serializer(), request)
        return executeIdempotent(
            "RETURN_CREATE", request.idempotencyKey, payload, HttpStatusCode.Created, "RETURN", currentUser,
            ReturnOrderDto.serializer()
        ) {
            createReturnOrder(request, currentUser)
        }
    }

    fun receiveReturn(id: Long, request: ReturnActionRequest, currentUser: CurrentUser): CommandResponse {
        currentUser.requirePermission("wms", "post")
        val payload = json.encodeToString(ReturnActionRequest.serializer(), request)
        return executeIdempotent(
            "RETURN_RECEIVE", request.idempotencyKey, payload, HttpStatusCode.OK, "RETURN", currentUser,
            ReturnOrderDto.serializer()
        ) {
            id to receiveReturnOrder(id, request, currentUser)
        }
    }

    fun inspectReturnLine(
        returnId: Long,
        lineId: Long,
        request: InspectReturnLineRequest,
        currentUser: CurrentUser
    ): CommandResponse {
        currentUser.requirePermission("wms", "inspect_returns")
        val payload = json.encodeToString(InspectReturnLineRequest.serializer(), request)
        return executeIdempotent(
            "RETURN_INSPECT", request.idempotencyKey, payload, HttpStatusCode.OK, "RETURN", currentUser,
            ReturnOrderDto.serializer()
        ) {
            returnId to inspectLine(returnId, lineId, request, currentUser)
        }
    }

    fun listReturns(warehouseId: Long?, status: ReturnStatus?, currentUser: CurrentUser): List<ReturnOrderDto> {
        currentUser.requirePermission("wms", "read")
        return issueRepository.listReturns(warehouseId, status?.name).map { it.toDto(emptyList()) }
    }

    fun getReturn(id: Long, currentUser: CurrentUser): ReturnOrderDto {
        currentUser.requirePermission("wms", "read")
        val order = issueRepository.findReturnById(id) ?: throw NotFoundException("return_order", id)
        return order.toDto(issueRepository.listReturnLines(id))
    }

    // --- Реализация выдачи ---

    private fun createIssueOrder(request: CreateIssueOrderRequest, currentUser: CurrentUser): Pair<Long, IssueOrderDto> {
        if (request.lines.isEmpty()) {
            throw ValidationException("Issue order must have at least one line", mapOf("lines" to "required"))
        }
        val lineNos = request.lines.map { it.lineNo }
        if (lineNos.distinct().size != lineNos.size) {
            throw ValidationException("Duplicate line numbers", mapOf("lines" to "duplicate_line_no"))
        }
        validateReason(request.reasonType, request.projectId, request.taskId)
        val warehouse = issueRepository.findWarehouseById(request.warehouseId)
            ?: throw NotFoundException("warehouse", request.warehouseId)
        if (warehouse.active != true) {
            throw ValidationException("Warehouse is inactive", mapOf("warehouseId" to "inactive"))
        }
        val recipientId = request.recipientId ?: currentUser.userId
        if (!issueRepository.userExists(recipientId)) {
            throw NotFoundException("user", recipientId)
        }
        val parsedLines = request.lines.map { line ->
            val nomenclature = issueRepository.findNomenclatureById(line.nomenclatureId)
                ?: throw NotFoundException("nomenclature", line.nomenclatureId)
            if (nomenclature.archived == true) {
                throw ConflictException("Nomenclature is archived: ${nomenclature.code}")
            }
            val requested = parseQuantity(line.requestedQuantity, "requestedQuantity")
            val reservationId = line.reservationId
            if (reservationId != null) {
                val reservation = issueRepository.findReservationById(reservationId)
                    ?: throw NotFoundException("reservation", reservationId)
                if (reservation.status != "ACTIVE") {
                    throw ConflictException("Reservation is not active")
                }
                if (reservation.nomenclatureId != line.nomenclatureId) {
                    throw ValidationException("Reservation is for another nomenclature", mapOf("reservationId" to "mismatch"))
                }
                if (reservation.warehouseId != request.warehouseId) {
                    throw ValidationException("Reservation belongs to another warehouse", mapOf("reservationId" to "other_warehouse"))
                }
                val remaining = reservation.quantity - reservation.issuedQuantity
                if (requested > remaining) {
                    throw ConflictException(
                        "Requested quantity exceeds the remaining reservation: requested ${requested.toPlainString()}, remaining ${remaining.toPlainString()}"
                    )
                }
            }
            Triple(line, requested, line.reservationId)
        }
        val number = issueRepository.generateIssueNumber()
        val order = issueRepository.insertIssue(
            number = number,
            warehouseId = request.warehouseId,
            reasonType = request.reasonType.name,
            projectId = request.projectId,
            taskId = request.taskId,
            reasonNote = request.reasonNote?.trim(),
            recipientId = recipientId,
            note = request.note?.trim(),
            createdBy = currentUser.userId
        )
        parsedLines.forEach { (line, requested, reservationId) ->
            issueRepository.insertIssueLine(order.id, line.lineNo, line.nomenclatureId, requested, reservationId, line.note?.trim())
        }
        auditService.log(
            currentUser.userId, "CREATE", "ISSUE_ORDER", order.id,
            mapOf("number" to number, "reasonType" to request.reasonType.name, "lines" to request.lines.size)
        )
        return order.id to order.toDto(loadLinesWithPicks(order.id))
    }

    private fun addIssuePicks(id: Long, request: PickIssueRequest, currentUser: CurrentUser): IssueOrderDto {
        val order = issueRepository.lockIssueById(id) ?: throw NotFoundException("issue_order", id)
        if (order.version != request.version) {
            throw ConflictException("Issue order was modified concurrently")
        }
        if (order.status != "DRAFT") {
            throw ConflictException("Picks can be added only while the issue order is DRAFT")
        }
        val lines = issueRepository.listIssueLines(id).associateBy { it.id }
        for (pick in request.picks) {
            val line = lines[pick.lineId]
                ?: throw ValidationException("Pick line does not belong to the issue order", mapOf("lineId" to "mismatch"))
            val quantity = parseQuantity(pick.quantity, "quantity")
            val location = validatePickLocation(pick.locationId, order.warehouseId)
            if (line.reservationId != null) {
                // Отбор под резерв — только из ячейки резерва и с его партией/серией.
                val reservation = issueRepository.findReservationById(line.reservationId)!!
                if (pick.locationId != reservation.locationId ||
                    pick.lotId != reservation.lotId ||
                    pick.serialId != reservation.serialId
                ) {
                    throw ValidationException(
                        "Pick for a reserved line must match the reservation location, lot and serial",
                        mapOf("lineId" to "reservation_mismatch")
                    )
                }
            }
            val lotId = pick.lotId
            if (lotId != null) {
                val lot = issueRepository.findLotById(lotId) ?: throw NotFoundException("lot", lotId)
                if (lot.nomenclatureId != line.nomenclatureId) {
                    throw ValidationException("Lot belongs to another nomenclature", mapOf("lotId" to "mismatch"))
                }
            }
            val serialId = pick.serialId
            if (serialId != null) {
                val serial = issueRepository.findSerialById(serialId) ?: throw NotFoundException("serial_item", serialId)
                if (serial.nomenclatureId != line.nomenclatureId) {
                    throw ValidationException("Serial item belongs to another nomenclature", mapOf("serialId" to "mismatch"))
                }
            }
            val pickedSoFar = issueRepository.sumPicksForLine(line.id)
            if (pickedSoFar + quantity > line.requestedQuantity) {
                throw ValidationException(
                    "Picked quantity exceeds the requested quantity",
                    mapOf("quantity" to "exceeds_requested")
                )
            }
            issueRepository.insertPick(line.id, location.id, pick.lotId, pick.serialId, quantity, currentUser.userId)
        }
        auditService.log(
            currentUser.userId, "PICK", "ISSUE_ORDER", id,
            mapOf("number" to order.number, "picks" to request.picks.size)
        )
        return order.toDto(loadLinesWithPicks(id))
    }

    private fun issueOrder(id: Long, request: IssueActionRequest, currentUser: CurrentUser): IssueOrderDto {
        val order = issueRepository.lockIssueById(id) ?: throw NotFoundException("issue_order", id)
        if (order.version != request.version) {
            throw ConflictException("Issue order was modified concurrently")
        }
        if (order.status != "DRAFT") {
            throw ConflictException("Issue order can be handed over only from DRAFT status")
        }
        val lines = issueRepository.listIssueLines(id)
        if (lines.isEmpty()) {
            throw ValidationException("Issue order has no lines", mapOf("lines" to "required"))
        }
        val totalPicked = lines.fold(BigDecimal.ZERO) { acc, line -> acc + issueRepository.sumPicksForLine(line.id) }
        if (totalPicked.signum() <= 0) {
            throw ValidationException("Nothing picked for the issue order", mapOf("picks" to "required"))
        }
        val issued = issueRepository.markIssued(id, request.version, currentUser.userId)
            ?: throw ConflictException("Issue order was modified concurrently")
        auditService.log(currentUser.userId, "ISSUE", "ISSUE_ORDER", id, mapOf("number" to order.number))
        return issued.toDto(loadLinesWithPicks(id))
    }

    // Подтверждение получения проводит расход атомарно: движения ISSUE,
    // согласованное уменьшение physical/reserved для строк из резерва,
    // issued_quantity резерва и его история.
    private fun confirmIssue(id: Long, request: IssueActionRequest, currentUser: CurrentUser): IssueOrderDto {
        val order = issueRepository.lockIssueById(id) ?: throw NotFoundException("issue_order", id)
        if (order.version != request.version) {
            throw ConflictException("Issue order was modified concurrently")
        }
        if (order.status != "ISSUED") {
            throw ConflictException("Issue order can be confirmed only from ISSUED status")
        }
        val lines = issueRepository.listIssueLines(id)
        val picks = issueRepository.listPicks(lines.map { it.id }).groupBy { it.issueLineId }

        val reservationIds = lines.mapNotNull { it.reservationId }.distinct().sorted()
        val reservations = reservationIds.associateWith {
            issueRepository.lockReservationById(it) ?: throw ConflictException("Reservation is missing")
        }
        val hasNonReservedPicks = lines.any { line ->
            line.reservationId == null && (picks[line.id] ?: emptyList()).isNotEmpty()
        }
        if (hasNonReservedPicks && !currentUser.hasPermission("wms", "issue_without_reservation")) {
            throw ForbiddenException("Missing permission wms:issue_without_reservation")
        }

        // Блокировка строк остатков в детерминированном порядке ключей,
        // после блокировки резервов (тот же порядок, что у снятия резерва).
        val balanceKeys = picks.values.flatten().map { pick ->
            WmsIssueRepository.BalanceKey(order.warehouseId, pick.locationId, pick.nomenclatureIdOf(lines), pick.lotId, pick.serialId)
        }.distinct().sorted()
        val lockedBalances = balanceKeys.associateWith { issueRepository.lockBalance(it) }

        var shortage = false
        for (line in lines) {
            val linePicks = picks[line.id] ?: emptyList()
            val pickedSum = linePicks.fold(BigDecimal.ZERO) { acc, pick -> acc + pick.quantity }
            if (pickedSum < line.requestedQuantity) {
                shortage = true
            }
            if (pickedSum.signum() == 0) {
                issueRepository.setLineIssuedQuantity(line.id, BigDecimal.ZERO)
                continue
            }
            if (line.reservationId != null) {
                val reservation = reservations[line.reservationId]!!
                if (reservation.status != "ACTIVE") {
                    throw ConflictException("Reservation is not active")
                }
                val remaining = reservation.quantity - reservation.issuedQuantity
                if (pickedSum > remaining) {
                    throw ConflictException(
                        "Picked quantity exceeds the remaining reservation: picked ${pickedSum.toPlainString()}, remaining ${remaining.toPlainString()}"
                    )
                }
                val balanceKey = WmsIssueRepository.BalanceKey(
                    order.warehouseId, reservation.locationId, line.nomenclatureId, reservation.lotId, reservation.serialId
                )
                val balanceRow = lockedBalances[balanceKey]
                    ?: throw ConflictException("Stock balance row is missing for the reservation")
                if (issueRepository.decreaseReservedPhysical(balanceRow.id, pickedSum) == 0) {
                    throw ConflictException("Reserved stock is inconsistent with the reservation")
                }
                val updatedReservation = issueRepository.addIssuedToReservation(reservation.id, pickedSum)
                    ?: throw ConflictException("Reservation was modified concurrently")
                issueRepository.insertReservationHistory(
                    reservation.id, "ISSUED", "ACTIVE", updatedReservation.status,
                    reservation.issuedQuantity, updatedReservation.issuedQuantity,
                    "Выдача ${order.number}", currentUser.userId
                )
                if (updatedReservation.status == "FULFILLED") {
                    issueRepository.insertReservationHistory(
                        reservation.id, "FULFILLED", "ACTIVE", "FULFILLED",
                        null, null, "Выдача ${order.number}", currentUser.userId
                    )
                }
                issueRepository.insertMovement(
                    "ISSUE", line.nomenclatureId, reservation.lotId, reservation.serialId,
                    fromLocationId = reservation.locationId, toLocationId = null,
                    quantity = pickedSum, documentType = "ISSUE", documentId = id,
                    note = "Выдача ${order.number}", createdBy = currentUser.userId
                )
            } else {
                for (pick in linePicks) {
                    val balanceKey = WmsIssueRepository.BalanceKey(
                        order.warehouseId, pick.locationId, line.nomenclatureId, pick.lotId, pick.serialId
                    )
                    val balanceRow = lockedBalances[balanceKey]
                        ?: throw ConflictException("Stock balance row is missing for the pick")
                    val available = balanceRow.physicalQty - balanceRow.reservedQty
                    if (available < pick.quantity) {
                        throw ConflictException(
                            "Insufficient available quantity: requested ${pick.quantity.toPlainString()}, available ${available.toPlainString()}"
                        )
                    }
                    if (issueRepository.decreaseFreePhysical(balanceRow.id, pick.quantity) == 0) {
                        throw ConflictException("Stock balance was modified concurrently")
                    }
                    issueRepository.insertMovement(
                        "ISSUE", line.nomenclatureId, pick.lotId, pick.serialId,
                        fromLocationId = pick.locationId, toLocationId = null,
                        quantity = pick.quantity, documentType = "ISSUE", documentId = id,
                        note = "Выдача ${order.number}", createdBy = currentUser.userId
                    )
                }
            }
            issueRepository.setLineIssuedQuantity(line.id, pickedSum)
        }
        val newStatus = if (shortage) "SHORTAGE" else "RECEIVED"
        val confirmed = issueRepository.markConfirmed(id, request.version, newStatus, currentUser.userId)
            ?: throw ConflictException("Issue order was modified concurrently")
        auditService.log(
            currentUser.userId, "CONFIRM", "ISSUE_ORDER", id,
            mapOf("number" to order.number, "status" to newStatus)
        )
        return confirmed.toDto(loadLinesWithPicks(id))
    }

    private fun refuseIssue(id: Long, request: RefuseIssueRequest, currentUser: CurrentUser): IssueOrderDto {
        val order = issueRepository.lockIssueById(id) ?: throw NotFoundException("issue_order", id)
        if (order.version != request.version) {
            throw ConflictException("Issue order was modified concurrently")
        }
        val refused = issueRepository.markRefused(id, request.version, request.reason.trim())
            ?: throw ConflictException("Issue order can be refused only from DRAFT or ISSUED status")
        auditService.log(
            currentUser.userId, "REFUSE", "ISSUE_ORDER", id,
            mapOf("number" to order.number, "reason" to request.reason.trim())
        )
        return refused.toDto(loadLinesWithPicks(id))
    }

    private fun cancelIssue(id: Long, request: IssueActionRequest, currentUser: CurrentUser): IssueOrderDto {
        val order = issueRepository.lockIssueById(id) ?: throw NotFoundException("issue_order", id)
        if (order.version != request.version) {
            throw ConflictException("Issue order was modified concurrently")
        }
        val cancelled = issueRepository.markCancelled(id, request.version)
            ?: throw ConflictException("Issue order can be cancelled only from DRAFT status")
        auditService.log(currentUser.userId, "CANCEL", "ISSUE_ORDER", id, mapOf("number" to order.number))
        return cancelled.toDto(loadLinesWithPicks(id))
    }

    // --- Реализация возврата ---

    private fun createReturnOrder(request: CreateReturnOrderRequest, currentUser: CurrentUser): Pair<Long, ReturnOrderDto> {
        if (request.lines.isEmpty()) {
            throw ValidationException("Return order must have at least one line", mapOf("lines" to "required"))
        }
        val issue = issueRepository.findIssueById(request.issueOrderId)
            ?: throw NotFoundException("issue_order", request.issueOrderId)
        if (issue.status != "RECEIVED" && issue.status != "SHORTAGE") {
            throw ConflictException("Return is possible only for a posted issue order")
        }
        val quarantine = issueRepository.findLocationById(request.quarantineLocationId)
            ?: throw NotFoundException("storage_location", request.quarantineLocationId)
        if (quarantine.warehouseId != issue.warehouseId) {
            throw ValidationException("Quarantine location belongs to another warehouse", mapOf("quarantineLocationId" to "other_warehouse"))
        }
        if (quarantine.purpose != "QUARANTINE" || quarantine.active != true) {
            throw ValidationException("Return must be received into an active QUARANTINE location", mapOf("quarantineLocationId" to "not_quarantine"))
        }
        val returnedBy = request.returnedById ?: currentUser.userId
        if (!issueRepository.userExists(returnedBy)) {
            throw NotFoundException("user", returnedBy)
        }
        val number = issueRepository.generateReturnNumber()
        val order = issueRepository.insertReturn(
            number = number,
            issueOrderId = issue.id,
            warehouseId = issue.warehouseId,
            quarantineLocationId = quarantine.id,
            returnedBy = returnedBy,
            reason = request.reason.trim(),
            createdBy = currentUser.userId
        )
        for (line in request.lines) {
            val quantity = parseQuantity(line.quantity, "quantity")
            // Блокировка строки выдачи сериализует конкурентные возвраты.
            val issueLine = issueRepository.lockIssueLineById(line.issueLineId)
                ?: throw NotFoundException("issue_line", line.issueLineId)
            if (issueLine.issueOrderId != issue.id) {
                throw ValidationException("Return line refers to another issue order", mapOf("issueLineId" to "mismatch"))
            }
            if (issueLine.issuedQuantity.signum() <= 0) {
                throw ConflictException("Nothing was issued for the issue line")
            }
            val alreadyReturned = issueRepository.sumReturnedForIssueLine(line.issueLineId)
            if (alreadyReturned + quantity > issueLine.issuedQuantity) {
                throw ConflictException(
                    "Return quantity exceeds the issued quantity: issued ${issueLine.issuedQuantity.toPlainString()}, already returned ${alreadyReturned.toPlainString()}, requested ${quantity.toPlainString()}"
                )
            }
            val lotId = line.lotId
            if (lotId != null) {
                val lot = issueRepository.findLotById(lotId) ?: throw NotFoundException("lot", lotId)
                if (lot.nomenclatureId != issueLine.nomenclatureId) {
                    throw ValidationException("Lot belongs to another nomenclature", mapOf("lotId" to "mismatch"))
                }
            }
            val serialId = line.serialId
            if (serialId != null) {
                val serial = issueRepository.findSerialById(serialId) ?: throw NotFoundException("serial_item", serialId)
                if (serial.nomenclatureId != issueLine.nomenclatureId) {
                    throw ValidationException("Serial item belongs to another nomenclature", mapOf("serialId" to "mismatch"))
                }
            }
            issueRepository.insertReturnLine(order.id, line.issueLineId, quantity, line.lotId, line.serialId)
        }
        auditService.log(
            currentUser.userId, "CREATE", "RETURN_ORDER", order.id,
            mapOf("number" to number, "issueOrderId" to issue.id, "lines" to request.lines.size)
        )
        return order.id to order.toDto(issueRepository.listReturnLines(order.id))
    }

    // Приёмка возврата: количество попадает в карантинную ячейку и НЕ
    // увеличивает доступный остаток до проверки пригодности (8.9 ТЗ).
    private fun receiveReturnOrder(id: Long, request: ReturnActionRequest, currentUser: CurrentUser): ReturnOrderDto {
        val order = issueRepository.lockReturnById(id) ?: throw NotFoundException("return_order", id)
        if (order.version != request.version) {
            throw ConflictException("Return order was modified concurrently")
        }
        if (order.status != "DRAFT") {
            throw ConflictException("Return order can be received only from DRAFT status")
        }
        val lines = issueRepository.listReturnLines(id)
        if (lines.isEmpty()) {
            throw ValidationException("Return order has no lines", mapOf("lines" to "required"))
        }
        for (line in lines) {
            val issueLine = issueRepository.lockIssueLineById(line.issueLineId)
                ?: throw ConflictException("Issue line is missing for the return")
            val othersReturned = issueRepository.sumReturnedForIssueLine(line.issueLineId, excludeReturnOrderId = id)
            if (othersReturned + line.quantity > issueLine.issuedQuantity) {
                throw ConflictException("Return quantity exceeds the issued quantity")
            }
            issueRepository.increasePhysical(
                WmsIssueRepository.BalanceKey(
                    order.warehouseId, order.quarantineLocationId, issueLine.nomenclatureId, line.lotId, line.serialId
                ),
                line.quantity
            )
            issueRepository.insertMovement(
                "RETURN", issueLine.nomenclatureId, line.lotId, line.serialId,
                fromLocationId = null, toLocationId = order.quarantineLocationId,
                quantity = line.quantity, documentType = "RETURN", documentId = id,
                note = "Возврат ${order.number}", createdBy = currentUser.userId
            )
        }
        val received = issueRepository.markReturnReceived(id, request.version, currentUser.userId)
            ?: throw ConflictException("Return order was modified concurrently")
        auditService.log(
            currentUser.userId, "RECEIVE", "RETURN_ORDER", id,
            mapOf("number" to order.number, "lines" to lines.size)
        )
        return received.toDto(issueRepository.listReturnLines(id))
    }

    // Проверка пригодности (wms:inspect_returns): GOOD — размещение в
    // разрешённой ячейке (остаток становится доступным), DEFECT — перевод в
    // зону брака либо оставление в карантине (недоступно обычной выдаче).
    private fun inspectLine(
        returnId: Long,
        lineId: Long,
        request: InspectReturnLineRequest,
        currentUser: CurrentUser
    ): ReturnOrderDto {
        val order = issueRepository.lockReturnById(returnId) ?: throw NotFoundException("return_order", returnId)
        if (order.version != request.version) {
            throw ConflictException("Return order was modified concurrently")
        }
        if (order.status != "RECEIVED") {
            throw ConflictException("Return lines can be inspected only while the return order is RECEIVED")
        }
        val line = issueRepository.findReturnLineById(lineId) ?: throw NotFoundException("return_line", lineId)
        if (line.returnOrderId != returnId) {
            throw ValidationException("Line does not belong to the return order", mapOf("lineId" to "mismatch"))
        }
        val issueLine = issueRepository.findIssueLineById(line.issueLineId)
            ?: throw ConflictException("Issue line is missing for the return")
        var moveTarget: StorageLocationsRecord? = null
        when (request.decision) {
            ReturnInspectionDecision.GOOD -> {
                val targetLocationId = request.targetLocationId
                if (targetLocationId == null) {
                    throw ValidationException("Target location is required for GOOD decision", mapOf("targetLocationId" to "required"))
                }
                val target = issueRepository.findLocationById(targetLocationId)
                    ?: throw NotFoundException("storage_location", targetLocationId)
                if (target.warehouseId != order.warehouseId) {
                    throw ValidationException("Target location belongs to another warehouse", mapOf("targetLocationId" to "other_warehouse"))
                }
                if (target.active != true || target.purpose !in PLACEMENT_PURPOSES) {
                    throw ValidationException("Target location is not available for placement", mapOf("targetLocationId" to "not_allowed"))
                }
                moveTarget = target
            }
            ReturnInspectionDecision.DEFECT -> {
                val targetLocationId = request.targetLocationId
                if (targetLocationId != null) {
                    val target = issueRepository.findLocationById(targetLocationId)
                        ?: throw NotFoundException("storage_location", targetLocationId)
                    if (target.warehouseId != order.warehouseId) {
                        throw ValidationException("Target location belongs to another warehouse", mapOf("targetLocationId" to "other_warehouse"))
                    }
                    if (target.active != true || target.purpose != "DEFECT") {
                        throw ValidationException("DEFECT decision requires a DEFECT location", mapOf("targetLocationId" to "not_defect"))
                    }
                    moveTarget = target
                }
            }
        }
        if (moveTarget != null) {
            val quarantineKey = WmsIssueRepository.BalanceKey(
                order.warehouseId, order.quarantineLocationId, issueLine.nomenclatureId, line.lotId, line.serialId
            )
            val targetKey = WmsIssueRepository.BalanceKey(
                order.warehouseId, moveTarget.id, issueLine.nomenclatureId, line.lotId, line.serialId
            )
            val (quarantineRow, _) = lockPairOrdered(quarantineKey, targetKey)
            if (quarantineRow == null || issueRepository.decreasePhysical(quarantineRow.id, line.quantity) == 0) {
                throw ConflictException("Quarantine balance is inconsistent with the return")
            }
            issueRepository.increasePhysical(targetKey, line.quantity)
            issueRepository.insertMovement(
                "TRANSFER", issueLine.nomenclatureId, line.lotId, line.serialId,
                fromLocationId = order.quarantineLocationId, toLocationId = moveTarget.id,
                quantity = line.quantity, documentType = "RETURN", documentId = returnId,
                note = "Inspection ${request.decision} for return ${order.number}",
                createdBy = currentUser.userId
            )
        }
        issueRepository.inspectReturnLine(
            lineId, request.decision.name, moveTarget?.id, request.note?.trim(), currentUser.userId
        ) ?: throw ConflictException("Return line inspection was already decided")
        var updatedOrder = order
        if (issueRepository.countPendingReturnLines(returnId) == 0) {
            updatedOrder = issueRepository.markReturnInspected(returnId, request.version)
                ?: throw ConflictException("Return order was modified concurrently")
        }
        auditService.log(
            currentUser.userId, "INSPECT", "RETURN_ORDER", returnId,
            mapOf(
                "number" to order.number, "lineId" to lineId,
                "decision" to request.decision.name, "targetLocationId" to (moveTarget?.id?.toString() ?: "")
            )
        )
        return updatedOrder.toDto(issueRepository.listReturnLines(returnId))
    }

    // --- Общие helpers ---

    private fun validateReason(reasonType: ReservationReasonType, projectId: Long?, taskId: Long?) {
        when (reasonType) {
            ReservationReasonType.PROJECT -> {
                if (projectId == null) {
                    throw ValidationException("projectId is required for PROJECT issue", mapOf("projectId" to "required"))
                }
                if (taskId != null) {
                    throw ValidationException("taskId must be empty for PROJECT issue", mapOf("taskId" to "not_allowed"))
                }
                if (!issueRepository.projectExists(projectId)) {
                    throw NotFoundException("project", projectId)
                }
            }
            ReservationReasonType.DEPARTMENT_TASK -> {
                if (taskId == null) {
                    throw ValidationException("taskId is required for DEPARTMENT_TASK issue", mapOf("taskId" to "required"))
                }
                if (projectId != null) {
                    throw ValidationException("projectId must be empty for DEPARTMENT_TASK issue", mapOf("projectId" to "not_allowed"))
                }
                if (!issueRepository.taskExists(taskId)) {
                    throw NotFoundException("task", taskId)
                }
            }
            else -> {
                if (projectId != null || taskId != null) {
                    throw ValidationException(
                        "projectId and taskId must be empty for $reasonType issue",
                        mapOf("reasonType" to "references_not_allowed")
                    )
                }
            }
        }
    }

    private fun validatePickLocation(locationId: Long, warehouseId: Long): StorageLocationsRecord {
        val location = issueRepository.findLocationById(locationId)
            ?: throw NotFoundException("storage_location", locationId)
        if (location.warehouseId != warehouseId) {
            throw ValidationException("Location does not belong to the warehouse", mapOf("locationId" to "other_warehouse"))
        }
        if (location.active != true) {
            throw ValidationException("Location is inactive", mapOf("locationId" to "inactive"))
        }
        // Карантин, брак и транзитная зона недоступны для обычной выдачи.
        if (location.purpose !in PLACEMENT_PURPOSES) {
            throw ConflictException("Location purpose ${location.purpose} is not allowed for picking")
        }
        return location
    }

    private fun lockPairOrdered(
        first: WmsIssueRepository.BalanceKey,
        second: WmsIssueRepository.BalanceKey
    ): Pair<StockBalanceRecord?, StockBalanceRecord?> {
        return if (first <= second) {
            issueRepository.lockBalance(first) to issueRepository.lockBalance(second)
        } else {
            val secondRow = issueRepository.lockBalance(second)
            val firstRow = issueRepository.lockBalance(first)
            firstRow to secondRow
        }
    }

    private fun loadLinesWithPicks(issueOrderId: Long): List<Pair<IssueLinesRecord, List<IssuePicksRecord>>> {
        val lines = issueRepository.listIssueLines(issueOrderId)
        val picks = issueRepository.listPicks(lines.map { it.id }).groupBy { it.issueLineId }
        return lines.map { it to (picks[it.id] ?: emptyList()) }
    }

    private fun IssuePicksRecord.nomenclatureIdOf(lines: List<IssueLinesRecord>): Long {
        return lines.first { it.id == this.issueLineId }.nomenclatureId
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

    private fun executeIdempotent(
        operation: String,
        idempotencyKey: String,
        requestPayload: String,
        successStatus: HttpStatusCode,
        entityType: String,
        currentUser: CurrentUser,
        serializer: KSerializer<*>,
        work: () -> Pair<Long, *>
    ): CommandResponse {
        if (idempotencyKey.isBlank()) {
            throw ValidationException("Idempotency key is required", mapOf("idempotencyKey" to "required"))
        }
        val requestHash = sha256("$operation|$requestPayload")
        try {
            return dsl.transactionResult(TransactionalCallable {
                val existing = issueRepository.findIdempotency(currentUser.userId, operation, idempotencyKey)
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
                    ApiResponse.serializer(serializer as KSerializer<Any?>),
                    ApiResponse.success(dto)
                )
                issueRepository.insertIdempotency(
                    currentUser.userId, operation, idempotencyKey, requestHash,
                    entityType, entityId, successStatus.value, body
                )
                CommandResponse(successStatus, body)
            })
        } catch (e: DataAccessException) {
            if (e.sqlState() == "23505" && e.message?.contains("uq_wms_issue_idempotency") == true) {
                val existing = issueRepository.findIdempotency(currentUser.userId, operation, idempotencyKey)
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
        private val PLACEMENT_PURPOSES = setOf("STORAGE", "RECEIVING", "SHIPPING")
    }
}

private val json = Json {
    encodeDefaults = true
}

private fun IssuePicksRecord.toDto(): IssuePickDto = IssuePickDto(
    id = this.id,
    issueLineId = this.issueLineId,
    locationId = this.locationId,
    lotId = this.lotId,
    serialId = this.serialId,
    quantity = this.quantity.toPlainString(),
    pickedBy = this.pickedBy,
    createdAt = this.createdAt?.toString()
)

private fun Pair<IssueLinesRecord, List<IssuePicksRecord>>.toLineDto(): IssueLineDto = IssueLineDto(
    id = this.first.id,
    lineNo = this.first.lineNo,
    nomenclatureId = this.first.nomenclatureId,
    requestedQuantity = this.first.requestedQuantity.toPlainString(),
    reservationId = this.first.reservationId,
    issuedQuantity = this.first.issuedQuantity.toPlainString(),
    note = this.first.note,
    picks = this.second.map { it.toDto() }
)

private fun IssueOrdersRecord.toDto(
    lines: List<Pair<IssueLinesRecord, List<IssuePicksRecord>>>
): IssueOrderDto = IssueOrderDto(
    id = this.id,
    number = this.number,
    warehouseId = this.warehouseId,
    reasonType = ReservationReasonType.valueOf(this.reasonType),
    projectId = this.projectId,
    taskId = this.taskId,
    reasonNote = this.reasonNote,
    recipientId = this.recipientId,
    status = this.status?.let { IssueStatus.valueOf(it) } ?: IssueStatus.DRAFT,
    note = this.note,
    issuedAt = this.issuedAt?.toString(),
    issuedBy = this.issuedBy,
    confirmedAt = this.confirmedAt?.toString(),
    confirmedBy = this.confirmedBy,
    refusalReason = this.refusalReason,
    createdAt = this.createdAt?.toString(),
    createdBy = this.createdBy,
    version = this.version ?: 0,
    lines = lines.map { it.toLineDto() }
)

private fun ReturnLinesRecord.toDto(): ReturnLineDto = ReturnLineDto(
    id = this.id,
    returnOrderId = this.returnOrderId,
    issueLineId = this.issueLineId,
    quantity = this.quantity.toPlainString(),
    lotId = this.lotId,
    serialId = this.serialId,
    inspection = this.inspection?.let { ReturnInspection.valueOf(it) } ?: ReturnInspection.PENDING,
    inspectedAt = this.inspectedAt?.toString(),
    inspectedBy = this.inspectedBy,
    targetLocationId = this.targetLocationId,
    inspectionNote = this.inspectionNote
)

private fun ReturnOrdersRecord.toDto(lines: List<ReturnLinesRecord>): ReturnOrderDto = ReturnOrderDto(
    id = this.id,
    number = this.number,
    issueOrderId = this.issueOrderId,
    warehouseId = this.warehouseId,
    quarantineLocationId = this.quarantineLocationId,
    status = this.status?.let { ReturnStatus.valueOf(it) } ?: ReturnStatus.DRAFT,
    returnedBy = this.returnedBy,
    reason = this.reason,
    receivedAt = this.receivedAt?.toString(),
    receivedBy = this.receivedBy,
    createdAt = this.createdAt?.toString(),
    createdBy = this.createdBy,
    version = this.version ?: 0,
    lines = lines.map { it.toDto() }
)
