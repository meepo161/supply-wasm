package org.example.project.service

import org.example.project.dto.*
import org.example.project.exception.ConflictException
import org.example.project.generated.jooq.tables.records.DemandLinesRecord
import org.example.project.generated.jooq.tables.records.DemandsRecord
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.example.project.exception.ForbiddenException
import org.example.project.exception.NotFoundException
import org.example.project.exception.ValidationException
import org.example.project.model.CurrentUser
import org.example.project.repository.BomRepository
import org.example.project.repository.DemandRepository
import org.example.project.repository.ProjectRepository
import org.example.project.repository.WmsRepository
import org.jooq.DSLContext
import org.jooq.TransactionalCallable
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.LocalDate
import java.time.OffsetDateTime

class DemandService(
    private val demandRepository: DemandRepository,
    private val projectRepository: ProjectRepository,
    private val bomRepository: BomRepository,
    private val supplierService: SupplierService,
    private val purchaseOrderService: PurchaseOrderService,
    private val auditService: AuditService,
    private val dsl: DSLContext,
    private val approvalGuard: ApprovalGuard,
    private val wmsRepository: WmsRepository,
    private val bomService: BomService,
    private val notificationService: NotificationService
) {

    private val calculator = BomExplosionCalculator(bomRepository)

    fun create(request: CreateDemandRequest, currentUser: CurrentUser): DemandDetailDto {
        currentUser.requirePermission("demands", "create")
        validateSourceSpecifics(request, currentUser)
        validateManualLines(request.lines)

        val idempotencyKey = request.idempotencyKey?.trim()?.takeIf { it.isNotEmpty() }
        val requestHash = idempotencyKey?.let { fingerprint(request.copy(idempotencyKey = it)) }
        if (idempotencyKey != null) {
            val existing = demandRepository.findByIdempotencyKey(idempotencyKey, currentUser.userId)
            if (existing != null) {
                if (existing.idempotencyRequestHash != requestHash) {
                    throw ConflictException("Idempotency key was already used for another request")
                }
                return getDetail(existing.id, currentUser)
            }
        }

        val now = OffsetDateTime.now()
        return dsl.transactionResult(TransactionalCallable {
            val number = demandRepository.generateNumber()
            val created = demandRepository.createDemand(
                number = number,
                sourceType = request.sourceType.name,
                projectId = request.projectId,
                stockpileNote = request.stockpileNote?.trim(),
                initiatorId = currentUser.userId,
                requiredDate = request.requiredDate?.let { parseDate(it) },
                priority = (request.priority ?: DemandPriority.NORMAL).name,
                createdBy = currentUser.userId,
                sourceBomRevisionId = null,
                bomReleaseQuantity = null,
                bomRevisionVersion = null,
                idempotencyKey = idempotencyKey,
                idempotencyRequestHash = requestHash,
                status = DemandStatus.APPROVED.name,
                approvedBy = currentUser.userId,
                approvedAt = now
            )
            val lines = demandRepository.createDemandLines(
                demandId = created.id,
                lines = request.lines.mapIndexed { index, line ->
                    DemandRepository.NewDemandLine(
                        lineNo = line.lineNo,
                        nomenclatureId = line.nomenclatureId,
                        quantity = parseQuantity(line.quantity, "lines[$index].quantity"),
                        unit = line.unit.trim()
                    )
                },
                createdBy = currentUser.userId
            )
            demandRepository.insertDemandHistory(
                demandId = created.id,
                action = "CREATED",
                fromStatus = null,
                toStatus = created.status,
                reason = null,
                changedBy = currentUser.userId
            )
            lines.forEach { line ->
                demandRepository.insertDemandLineHistory(
                    demandLineId = line.id,
                    action = "CREATED",
                    quantityBefore = null,
                    quantityAfter = line.quantity,
                    fromStatus = null,
                    toStatus = line.status,
                    reason = null,
                    changedBy = currentUser.userId
                )
            }
            auditService.log(
                currentUser.userId, "CREATE", "DEMAND", created.id,
                mapOf("number" to created.number, "sourceType" to created.sourceType, "status" to created.status)
            )
            toDetail(created, lines)
        })
    }

    fun createFromBom(request: CreateDemandFromBomRequest, currentUser: CurrentUser): DemandDetailDto {
        currentUser.requirePermission("demands", "create")
        return createFromBomInternal(request, currentUser, stockpileNote = null)
    }

    private fun createFromBomInternal(
        request: CreateDemandFromBomRequest,
        currentUser: CurrentUser,
        stockpileNote: String?
    ): DemandDetailDto {
        val project = projectRepository.findById(request.projectId)
            ?: throw NotFoundException("project", request.projectId)
        val hasProjectAccess = projectRepository.isMember(project.id, currentUser.userId) ||
            project.ownerId == currentUser.userId ||
            currentUser.hasPermission("projects", "read_any")
        if (!hasProjectAccess) {
            throw ForbiddenException("You are not a member of this project")
        }
        val revision = bomRepository.findRevisionById(request.revisionId)
            ?: throw NotFoundException("bom_revision", request.revisionId)
        if (revision.status != "APPROVED" && revision.status != "ACTIVE") {
            throw ConflictException("Demand can only be created from APPROVED or ACTIVE BOM revision")
        }
        val releaseQuantity = parseQuantity(request.releaseQuantity, "releaseQuantity")

        val idempotencyKey = request.idempotencyKey?.trim()?.takeIf { it.isNotEmpty() }
        val requestHash = idempotencyKey?.let { fingerprint(request.copy(idempotencyKey = it)) }
        if (idempotencyKey != null) {
            val existing = demandRepository.findByIdempotencyKey(idempotencyKey, currentUser.userId)
            if (existing != null) {
                if (existing.idempotencyRequestHash != requestHash) {
                    throw ConflictException("Idempotency key was already used for another request")
                }
                return getDetail(existing.id, currentUser)
            }
        }

        val explosion = calculator.explode(revision.id, releaseQuantity)
        if (explosion.totalRequired.isEmpty()) {
            throw ValidationException("BOM explosion produced no material requirements", mapOf("revisionId" to "empty"))
        }
        val unitsByNomenclature = explosion.lines
            .groupBy { it.nomenclatureId }
            .mapValues { it.value.first().unit }

        val now = OffsetDateTime.now()
        return dsl.transactionResult(TransactionalCallable {
            val number = demandRepository.generateNumber()
            val header = demandRepository.createDemand(
                number = number,
                sourceType = "PROJECT",
                projectId = project.id,
                stockpileNote = stockpileNote,
                initiatorId = currentUser.userId,
                requiredDate = request.requiredDate?.let { parseDate(it) },
                priority = (request.priority ?: DemandPriority.NORMAL).name,
                createdBy = currentUser.userId,
                sourceBomRevisionId = revision.id,
                bomReleaseQuantity = releaseQuantity,
                bomRevisionVersion = revision.version,
                idempotencyKey = idempotencyKey,
                idempotencyRequestHash = requestHash,
                status = DemandStatus.APPROVED.name,
                approvedBy = currentUser.userId,
                approvedAt = now
            )
            val sortedTotals = explosion.totalRequired.entries.sortedBy { it.key }
            val lines = demandRepository.createDemandLines(
                demandId = header.id,
                lines = sortedTotals.mapIndexed { index, entry ->
                    DemandRepository.NewDemandLine(
                        lineNo = index + 1,
                        nomenclatureId = entry.key,
                        quantity = entry.value,
                        unit = unitsByNomenclature[entry.key] ?: "pcs",
                        sourceBomRevisionId = revision.id
                    )
                },
                createdBy = currentUser.userId
            )
            demandRepository.insertDemandHistory(
                demandId = header.id,
                action = "CREATED",
                fromStatus = null,
                toStatus = header.status,
                reason = null,
                changedBy = currentUser.userId
            )
            lines.forEach { line ->
                demandRepository.insertDemandLineHistory(
                    demandLineId = line.id,
                    action = "CREATED",
                    quantityBefore = null,
                    quantityAfter = line.quantity,
                    fromStatus = null,
                    toStatus = line.status,
                    reason = null,
                    changedBy = currentUser.userId
                )
            }
            auditService.log(
                currentUser.userId, "CREATE", "DEMAND", header.id,
                mapOf(
                    "number" to header.number,
                    "sourceType" to "PROJECT",
                    "sourceBomRevisionId" to revision.id,
                    "bomRevisionVersion" to revision.version,
                    "bomReleaseQuantity" to releaseQuantity.stripTrailingZeros().toPlainString()
                )
            )
            toDetail(header, lines)
        })
    }

    fun handoffProjectSpecification(
        projectId: Long,
        request: HandoffProjectSpecRequest,
        currentUser: CurrentUser
    ): DemandDetailDto {
        val project = projectRepository.findById(projectId)
            ?: throw NotFoundException("project", projectId)
        val hasProjectAccess = projectRepository.isMember(project.id, currentUser.userId) ||
            project.ownerId == currentUser.userId ||
            currentUser.hasPermission("projects", "read_any")
        if (!hasProjectAccess) {
            throw ForbiddenException("You are not a member of this project")
        }
        if (!currentUser.hasPermission("demands", "create") && !currentUser.hasPermission("bom", "create")) {
            throw ForbiddenException("Missing permission demands:create or bom:create")
        }

        if (request.revisionId != null) {
            val revision = bomService.ensureRevisionReadyForHandoff(request.revisionId!!, currentUser)
            demandRepository.linkProjectBomRevision(project.id, revision.productModelId, revision.id, currentUser.userId)
            val releaseQty = request.releaseQuantity?.trim()?.takeIf { it.isNotEmpty() } ?: "1"
            return createFromBomInternal(
                CreateDemandFromBomRequest(
                    projectId = projectId,
                    revisionId = revision.id,
                    releaseQuantity = releaseQty,
                    requiredDate = request.requiredDate,
                    priority = DemandPriority.NORMAL,
                    idempotencyKey = request.idempotencyKey
                ),
                currentUser = currentUser,
                stockpileNote = request.note?.trim()?.takeIf { it.isNotEmpty() }
            )
        }

        if (request.items.isEmpty()) {
            throw ValidationException(
                "Укажите ревизию спецификации или позиции номенклатуры",
                mapOf("items" to "required")
            )
        }
        validateManualLines(request.items)

        val idempotencyKey = request.idempotencyKey?.trim()?.takeIf { it.isNotEmpty() }
        val requestHash = idempotencyKey?.let { fingerprint(request.copy(idempotencyKey = it)) }
        if (idempotencyKey != null) {
            val existing = demandRepository.findByIdempotencyKey(idempotencyKey, currentUser.userId)
            if (existing != null) {
                if (existing.idempotencyRequestHash != requestHash) {
                    throw ConflictException("Idempotency key was already used for another request")
                }
                return getDetail(existing.id, currentUser)
            }
        }

        val now = OffsetDateTime.now()
        return dsl.transactionResult(TransactionalCallable {
            val number = demandRepository.generateNumber()
            val created = demandRepository.createDemand(
                number = number,
                sourceType = DemandSourceType.PROJECT.name,
                projectId = project.id,
                stockpileNote = request.note?.trim()?.takeIf { it.isNotEmpty() },
                initiatorId = currentUser.userId,
                requiredDate = request.requiredDate?.trim()?.takeIf { it.isNotEmpty() }?.let { parseDate(it) },
                priority = DemandPriority.NORMAL.name,
                createdBy = currentUser.userId,
                sourceBomRevisionId = null,
                bomReleaseQuantity = null,
                bomRevisionVersion = null,
                idempotencyKey = idempotencyKey,
                idempotencyRequestHash = requestHash,
                status = DemandStatus.APPROVED.name,
                approvedBy = currentUser.userId,
                approvedAt = now
            )
            val lines = demandRepository.createDemandLines(
                demandId = created.id,
                lines = request.items.mapIndexed { index, line ->
                    DemandRepository.NewDemandLine(
                        lineNo = line.lineNo,
                        nomenclatureId = line.nomenclatureId,
                        quantity = parseQuantity(line.quantity, "items[$index].quantity"),
                        unit = line.unit.trim()
                    )
                },
                createdBy = currentUser.userId
            )
            demandRepository.insertDemandHistory(
                demandId = created.id,
                action = "CREATED",
                fromStatus = null,
                toStatus = created.status,
                reason = "Передано из спецификации проекта ${project.number}",
                changedBy = currentUser.userId
            )
            lines.forEach { line ->
                demandRepository.insertDemandLineHistory(
                    demandLineId = line.id,
                    action = "CREATED",
                    quantityBefore = null,
                    quantityAfter = line.quantity,
                    fromStatus = null,
                    toStatus = line.status,
                    reason = null,
                    changedBy = currentUser.userId
                )
            }
            auditService.log(
                currentUser.userId, "HANDOFF_SPECIFICATION", "DEMAND", created.id,
                mapOf("number" to created.number, "projectId" to project.id, "lines" to lines.size)
            )
            toDetail(created, lines)
        })
    }

    fun get(id: Long, currentUser: CurrentUser): DemandDetailDto {
        return getDetail(id, currentUser)
    }

    /**
     * Потребности проекта для вкладки карточки (PRJ-15): участник проекта
     * видит их, остальным нужен `projects:read_any`.
     */
    fun listByProject(projectId: Long, currentUser: CurrentUser): List<DemandHeaderDto> {
        val project = projectRepository.findById(projectId) ?: throw NotFoundException("project", projectId)
        val isMember = projectRepository.isMember(projectId, currentUser.userId) ||
            project.ownerId == currentUser.userId
        if (!isMember && !currentUser.hasPermission("projects", "read_any")) {
            throw ForbiddenException("Access to project demands denied")
        }
        val records = demandRepository.listByProject(projectId)
        return enrichHeaders(records)
    }

    fun list(currentUser: CurrentUser): List<DemandHeaderDto> {
        val readAny = hasDemandReadAccess(currentUser)
        val records = demandRepository.listAccessible(currentUser.userId, readAny)
        return enrichHeaders(records)
    }

    private fun enrichHeaders(records: List<DemandsRecord>): List<DemandHeaderDto> {
        if (records.isEmpty()) return emptyList()
        val demandIds = records.map { it.id }
        val summaries = demandRepository.lineSummaries(demandIds)
        val enrichments = demandRepository.headerEnrichments(
            demandIds = demandIds,
            initiatorIds = records.map { it.initiatorId }
        )
        val projectNames = demandRepository.projectNamesBatch(records.mapNotNull { it.projectId })
        val linesByDemand = demandRepository.demandLinesBatch(demandIds)
        val allLineIds = linesByDemand.values.flatten().map { it.id }
        val orderStatsByLine = demandRepository.demandLineOrderStatsBatch(allLineIds)
        val receivedByLine = demandRepository.demandLineReceivedQtyBatch(allLineIds)
        val offersByDemand = demandRepository.offersForDemandsBatch(demandIds)
        return records.map { record ->
            val lines = summaries[record.id].orEmpty()
            val info = enrichments.byDemand[record.id]
            val projectInfo = record.projectId?.let { projectNames[it] }
            val diag = computeDemandDiagnostics(
                demandStatus = record.status,
                sourceType = record.sourceType,
                lines = linesByDemand[record.id].orEmpty(),
                offers = offersByDemand[record.id].orEmpty(),
                orderStatsByLine = orderStatsByLine,
                receivedByLine = receivedByLine
            )
            record.toHeaderDto().copy(
                lineCount = lines.size,
                itemsSummary = itemsSummary(lines),
                quantitySummary = quantitySummary(lines),
                initiatorName = enrichments.initiatorNames[record.initiatorId],
                selectedSupplierName = info?.selectedSupplierName,
                selectedGrossTotal = info?.selectedGrossTotal,
                isPaid = info?.isPaid ?: false,
                paidAt = info?.paidAt,
                projectNumber = projectInfo?.first,
                projectName = projectInfo?.second,
                nextActorRole = diag.nextActorRole,
                nextActionHint = diag.nextActionHint,
                stallReason = diag.stallReason,
                hasReturnedOffers = diag.hasReturnedOffers
            )
        }
    }

    /** «Болт М8» или «Болт М8 и ещё 2» — коротко о составе потребности. */
    private fun itemsSummary(lines: List<DemandRepository.LineSummary>): String? =
        when (lines.size) {
            0 -> null
            1 -> lines.first().name
            else -> "${lines.first().name} и ещё ${lines.size - 1}"
        }

    /** Количество по единицам измерения: «10 шт», «10 шт; 5 кг». */
    private fun quantitySummary(lines: List<DemandRepository.LineSummary>): String? =
        lines.groupBy { it.unit }
            .map { (unit, group) ->
                val total = group.fold(java.math.BigDecimal.ZERO) { acc, line -> acc + line.quantity }
                "${total.stripTrailingZeros().toPlainString()} $unit"
            }
            .takeIf { it.isNotEmpty() }
            ?.joinToString("; ")

    fun submit(id: Long, request: SubmitDemandRequest, currentUser: CurrentUser): DemandDetailDto {
        currentUser.requirePermission("demands", "update")
        val demand = demandRepository.findById(id) ?: throw NotFoundException("demand", id)
        ensureAccess(demand, currentUser)
        if (demand.version != request.version) {
            throw ConflictException("Demand was modified concurrently")
        }
        if (demand.status != "DRAFT") {
            throw ConflictException("Only DRAFT demand can be submitted")
        }
        val lines = demandRepository.listLines(id)
        if (lines.isEmpty()) {
            throw ValidationException("Cannot submit demand without lines", mapOf("lines" to "empty"))
        }

        return dsl.transactionResult(TransactionalCallable {
            val updated = demandRepository.transitionStatus(
                id = id,
                expectedVersion = request.version,
                fromStatus = "DRAFT",
                newStatus = "PENDING_APPROVAL"
            ) ?: throw ConflictException("Demand was modified concurrently")
            demandRepository.insertDemandHistory(
                demandId = id,
                action = "STATUS_CHANGED",
                fromStatus = "DRAFT",
                toStatus = "PENDING_APPROVAL",
                reason = null,
                changedBy = currentUser.userId
            )
            auditService.log(
                currentUser.userId, "SUBMIT", "DEMAND", id,
                mapOf("number" to updated.number)
            )
            toDetail(updated, demandRepository.listLines(id))
        })
    }

    fun approve(id: Long, request: ApproveDemandRequest, currentUser: CurrentUser): DemandDetailDto {
        currentUser.requirePermission("demands", "approve")
        val demand = demandRepository.findById(id) ?: throw NotFoundException("demand", id)
        if (approvalGuard.hasActiveRoute(APPROVAL_TARGET_DEMAND, id)) {
            throw ConflictException(
                "Demand has an active approval route; direct approve is not allowed, use the approval route"
            )
        }
        if (demand.version != request.version) {
            throw ConflictException("Demand was modified concurrently")
        }
        if (demand.status != "PENDING_APPROVAL") {
            throw ConflictException("Only PENDING_APPROVAL demand can be approved")
        }

        return dsl.transactionResult(TransactionalCallable {
            val updated = demandRepository.transitionStatus(
                id = id,
                expectedVersion = request.version,
                fromStatus = "PENDING_APPROVAL",
                newStatus = "APPROVED",
                approvedBy = currentUser.userId,
                approvedAt = OffsetDateTime.now()
            ) ?: throw ConflictException("Demand was modified concurrently")
            demandRepository.insertDemandHistory(
                demandId = id,
                action = "APPROVED",
                fromStatus = "PENDING_APPROVAL",
                toStatus = "APPROVED",
                reason = null,
                changedBy = currentUser.userId
            )
            auditService.log(
                currentUser.userId, "APPROVE", "DEMAND", id,
                mapOf("number" to updated.number)
            )
            toDetail(updated, demandRepository.listLines(id))
        })
    }

    fun cancel(id: Long, request: CancelDemandRequest, currentUser: CurrentUser): DemandDetailDto {
        currentUser.requirePermission("demands", "update")
        val demand = demandRepository.findById(id) ?: throw NotFoundException("demand", id)
        if (approvalGuard.hasActiveRoute(APPROVAL_TARGET_DEMAND, id)) {
            throw ConflictException(
                "Demand has an active approval route; cancel the approval route first instead of direct cancel"
            )
        }
        ensureAccess(demand, currentUser)
        if (demand.version != request.version) {
            throw ConflictException("Demand was modified concurrently")
        }
        val reason = request.reason.trim()
        if (reason.isEmpty()) {
            throw ValidationException("Cancellation reason is required", mapOf("reason" to "required"))
        }
        if (demand.status == "CANCELLED") {
            throw ConflictException("Demand is already cancelled")
        }
        val allowedFrom = setOf("DRAFT", "PENDING_APPROVAL", "APPROVED")
        if (demand.status !in allowedFrom) {
            throw ConflictException("Cannot cancel demand from status ${demand.status}")
        }

        return dsl.transactionResult(TransactionalCallable {
            val updated = demandRepository.cancelDemand(
                id = id,
                expectedVersion = request.version,
                fromStatuses = allowedFrom
            ) ?: throw ConflictException("Demand was modified concurrently")
            demandRepository.cancelAllLines(id)
            demandRepository.insertDemandHistory(
                demandId = id,
                action = "CANCELLED",
                fromStatus = demand.status,
                toStatus = "CANCELLED",
                reason = reason,
                changedBy = currentUser.userId
            )
            demandRepository.listLines(id).forEach { line ->
                demandRepository.insertDemandLineHistory(
                    demandLineId = line.id,
                    action = "CANCELLED",
                    quantityBefore = line.quantity,
                    quantityAfter = line.quantity,
                    fromStatus = line.status,
                    toStatus = "CANCELLED",
                    reason = reason,
                    changedBy = currentUser.userId
                )
            }
            auditService.log(
                currentUser.userId, "CANCEL", "DEMAND", id,
                mapOf("number" to updated.number, "reason" to reason)
            )
            toDetail(updated, demandRepository.listLines(id))
        })
    }

    fun coveragePreview(id: Long, currentUser: CurrentUser): DemandCoverageDto {
        val demand = demandRepository.findHeaderById(id) ?: throw NotFoundException("demand", id)
        ensureAccess(demand, currentUser)
        val lines = demandRepository.listLines(id)
        val nomenclatureIds = lines.map { it.nomenclatureId }.toSet()
        val stock = bomRepository.stockSummary(nomenclatureIds, null)
        val expected = bomRepository.expectedReceipts(nomenclatureIds)
        val reservations = if (demand.sourceType == "PROJECT") {
            demandRepository.projectReservations(nomenclatureIds, demand.projectId)
        } else {
            emptyMap()
        }

        return DemandCoverageDto(
            demandId = id,
            lines = lines.map { line ->
                val required = line.quantity
                val available = stock[line.nomenclatureId]?.availableQty ?: BigDecimal.ZERO
                val reserved = reservations[line.nomenclatureId] ?: BigDecimal.ZERO
                val expectedReceipt = expected[line.nomenclatureId] ?: BigDecimal.ZERO
                val deficit = required.subtract(available).max(BigDecimal.ZERO)
                val recommendedPurchase = deficit.subtract(expectedReceipt).max(BigDecimal.ZERO)
                DemandCoverageLineDto(
                    lineNo = line.lineNo,
                    nomenclatureId = line.nomenclatureId,
                    unit = line.unit,
                    requiredQuantity = required.stripTrailingZeros().toPlainString(),
                    stockAvailable = available.stripTrailingZeros().toPlainString(),
                    projectReservations = reserved.stripTrailingZeros().toPlainString(),
                    expectedReceipts = expectedReceipt.stripTrailingZeros().toPlainString(),
                    deficit = deficit.stripTrailingZeros().toPlainString(),
                    recommendedPurchase = recommendedPurchase.stripTrailingZeros().toPlainString()
                )
            }
        )
    }

    fun markDemandPaid(demandId: Long, request: MarkDemandPaidRequest, currentUser: CurrentUser): DemandDetailDto {
        currentUser.requirePermission("suppliers", "order")
        val demand = demandRepository.findById(demandId) ?: throw NotFoundException("demand", demandId)
        if (demand.status == "CANCELLED") {
            throw ConflictException("Потребность ${demand.number} отменена")
        }
        val selectedOffer = demandRepository.findSelectedOfferForDemand(demandId)
            ?: throw ConflictException("Сначала должен быть выбран вариант предложения")
        purchaseOrderService.markOfferPaidAndEnsureSentOrder(
            demandId = demandId,
            demandNumber = demand.number,
            demandInitiatorId = demand.initiatorId,
            offer = selectedOffer,
            request = request,
            currentUser = currentUser
        )
        return getDetail(demandId, currentUser)
    }

    fun placeDemandOrder(
        demandId: Long,
        request: PlaceDemandOrderRequest,
        currentUser: CurrentUser
    ): DemandDetailDto {
        currentUser.requirePermission("suppliers", "order")
        val demand = demandRepository.findById(demandId) ?: throw NotFoundException("demand", demandId)
        if (demand.status == "CANCELLED") {
            throw ConflictException("Потребность ${demand.number} отменена")
        }
        val selectedOffer = demandRepository.findSelectedOfferForDemand(demandId)
            ?: throw ConflictException("Сначала должен быть выбран вариант предложения")
        purchaseOrderService.placeOrderForDemandOffer(
            demandId = demandId,
            demandNumber = demand.number,
            demandInitiatorId = demand.initiatorId,
            offer = selectedOffer,
            request = request,
            currentUser = currentUser
        )
        return getDetail(demandId, currentUser)
    }

    fun reserveDemandLineStock(
        demandId: Long,
        lineId: Long,
        request: ReserveDemandStockRequest,
        currentUser: CurrentUser
    ): DemandDetailDto {
        if (!currentUser.hasPermission("suppliers", "offer") &&
            !currentUser.hasPermission("suppliers", "order") &&
            !currentUser.hasPermission("wms", "manage")
        ) {
            throw ForbiddenException("Missing permission to reserve stock for demand")
        }
        request.warehouseId?.let { whId ->
            val warehouse = demandRepository.findActiveWarehouse(whId)
                ?: throw NotFoundException("warehouse", whId)
            if (warehouse.active != true) {
                throw ValidationException("Warehouse is inactive", mapOf("warehouseId" to "inactive"))
            }
        }
        val requestedQty = request.quantity?.trim()?.takeIf { it.isNotEmpty() }?.let {
            parseQuantity(it, "quantity")
        }

        dsl.transactionResult(TransactionalCallable {
            val demand = demandRepository.lockDemandById(demandId)
                ?: throw NotFoundException("demand", demandId)
            if (demand.status == "CANCELLED") {
                throw ConflictException("Потребность ${demand.number} отменена")
            }
            val line = demandRepository.lockDemandLineById(lineId)
                ?: throw NotFoundException("demand_line", lineId)
            if (line.demandId != demand.id) {
                throw ValidationException("Demand line does not belong to this demand", mapOf("lineId" to "mismatch"))
            }
            if (line.status == "CANCELLED") {
                throw ConflictException("Позиция заявки отменена")
            }
            val openDeficit = (line.quantity - line.coveredQuantity).max(BigDecimal.ZERO)
            val balances = demandRepository.lockFreeStockBalancesForNomenclature(
                nomenclatureId = line.nomenclatureId,
                warehouseId = request.warehouseId
            )
            val totalFreeStock = balances.fold(BigDecimal.ZERO) { acc, b ->
                acc + (b.physicalQty - b.reservedQty).max(BigDecimal.ZERO)
            }
            if (openDeficit <= BigDecimal.ZERO || totalFreeStock <= BigDecimal.ZERO) {
                throw ConflictException("Нет свободного остатка на складе или потребность по позиции уже закрыта")
            }
            val targetQty = if (requestedQty != null) {
                if (requestedQty > openDeficit) {
                    throw ConflictException(
                        "Запрошенное количество превышает открытый дефицит (${openDeficit.stripTrailingZeros().toPlainString()})"
                    )
                }
                if (requestedQty > totalFreeStock) {
                    throw ConflictException(
                        "Недостаточно свободного остатка на складе: доступно ${totalFreeStock.stripTrailingZeros().toPlainString()}"
                    )
                }
                requestedQty
            } else {
                openDeficit.min(totalFreeStock)
            }
            if (targetQty <= BigDecimal.ZERO) {
                throw ConflictException("Нет свободного остатка на складе или потребность по позиции уже закрыта")
            }

            var remaining = targetQty
            val isProject = demand.projectId != null
            val reasonNote = "Резерв по заявке ${demand.number}"
            for (balance in balances) {
                val rowFree = (balance.physicalQty - balance.reservedQty).max(BigDecimal.ZERO)
                if (rowFree <= BigDecimal.ZERO) continue
                val take = remaining.min(rowFree)
                if (take <= BigDecimal.ZERO) break
                if (wmsRepository.addReserved(balance.id, take) == 0) {
                    throw ConflictException("Stock balance was modified concurrently")
                }
                val number = wmsRepository.generateReservationNumber()
                val reservation = wmsRepository.createReservation(
                    number = number,
                    reasonType = if (isProject) "PROJECT" else "OTHER",
                    projectId = if (isProject) demand.projectId else null,
                    taskId = null,
                    reasonNote = reasonNote,
                    nomenclatureId = line.nomenclatureId,
                    warehouseId = balance.warehouseId,
                    locationId = balance.locationId,
                    lotId = balance.lotId,
                    serialId = balance.serialId,
                    quantity = take,
                    ownerId = demand.initiatorId,
                    expiresAt = null,
                    createdBy = currentUser.userId
                )
                wmsRepository.insertReservationHistory(
                    reservationId = reservation.id,
                    action = "CREATED",
                    fromStatus = null,
                    toStatus = "ACTIVE",
                    quantityBefore = null,
                    quantityAfter = take,
                    reason = reasonNote,
                    changedBy = currentUser.userId
                )
                remaining -= take
                if (remaining <= BigDecimal.ZERO) break
            }
            if (remaining > BigDecimal.ZERO) {
                throw ConflictException("Недостаточно свободного остатка на складе")
            }

            val newStockReserved = line.stockReservedQuantity + targetQty
            val acceptedByWms = wmsRepository.acceptedQuantityForDemandLine(line.id)
            val newCovered = (newStockReserved + acceptedByWms).min(line.quantity)
            val newLineStatus = when {
                newCovered >= line.quantity -> "COVERED"
                newCovered > BigDecimal.ZERO -> "PARTIALLY_COVERED"
                else -> "OPEN"
            }
            demandRepository.updateDemandLineStockReserve(
                lineId = line.id,
                stockReservedQuantity = newStockReserved,
                coveredQuantity = newCovered,
                status = newLineStatus
            )
            demandRepository.insertDemandLineHistory(
                demandLineId = line.id,
                action = "STOCK_RESERVED",
                quantityBefore = line.coveredQuantity,
                quantityAfter = newCovered,
                fromStatus = line.status,
                toStatus = newLineStatus,
                reason = "Резерв со склада: ${targetQty.stripTrailingZeros().toPlainString()} ${line.unit}",
                changedBy = currentUser.userId
            )

            val allLines = demandRepository.listLines(demand.id).filter { it.status != "CANCELLED" }
            val allCovered = allLines.isNotEmpty() && allLines.all { it.status == "COVERED" }
            val anyCovered = allLines.any { it.coveredQuantity > BigDecimal.ZERO }
            val newDemandStatus = when {
                allCovered -> "COVERED"
                anyCovered -> "PARTIALLY_COVERED"
                else -> demand.status
            }
            if (newDemandStatus != demand.status) {
                demandRepository.updateDemandStatusDirect(demand.id, newDemandStatus)
                demandRepository.insertDemandHistory(
                    demandId = demand.id,
                    action = "STATUS_CHANGED",
                    fromStatus = demand.status,
                    toStatus = newDemandStatus,
                    reason = "Резерв свободного остатка по строке ${line.lineNo}",
                    changedBy = currentUser.userId
                )
            }
            auditService.log(
                currentUser.userId, "RESERVE_STOCK", "DEMAND", demand.id,
                mapOf(
                    "number" to demand.number,
                    "demandLineId" to line.id,
                    "nomenclatureId" to line.nomenclatureId,
                    "quantity" to targetQty.stripTrailingZeros().toPlainString()
                )
            )
        })
        return getDetail(demandId, currentUser)
    }

    fun projectSupplyOverview(projectId: Long, currentUser: CurrentUser): ProjectSupplyOverviewDto {
        val project = projectRepository.findById(projectId)
            ?: throw NotFoundException("project", projectId)
        val hasAccess = projectRepository.isMember(projectId, currentUser.userId) ||
            project.ownerId == currentUser.userId ||
            currentUser.hasPermission("projects", "read_any") ||
            currentUser.hasPermission("demands", "read_any") ||
            currentUser.hasPermission("suppliers", "read") ||
            currentUser.hasPermission("wms", "read")
        if (!hasAccess) {
            throw ForbiddenException("Access to project supply overview denied")
        }

        val activeDemands = demandRepository.listByProject(projectId)
            .filter { it.status != "CANCELLED" }
        val demandIds = activeDemands.map { it.id }
        val linesByDemand = demandRepository.demandLinesBatch(demandIds)
        val allActiveLines = activeDemands.flatMap { d ->
            linesByDemand[d.id].orEmpty().filter { it.status != "CANCELLED" }
        }
        val nomenclatureIds = allActiveLines.map { it.nomenclatureId }.toSet()
        val nomenclatureNames = demandRepository.nomenclatureNamesBatch(nomenclatureIds)
        val freeStockByNom = demandRepository.freeWarehouseStockBatch(nomenclatureIds)
        val reservedTotalByNom = demandRepository.projectReservedTotalsBatch(projectId, nomenclatureIds)
        val lineIds = allActiveLines.map { it.id }
        val orderStatsByLine = demandRepository.demandLineOrderStatsBatch(lineIds)
        val receivedByLine = demandRepository.demandLineReceivedQtyBatch(lineIds)
        val offersByDemand = demandRepository.offersForDemandsBatch(demandIds)

        val lineCountByNom = allActiveLines.groupingBy { it.nomenclatureId }.eachCount()
        val remainingReservedByNom = reservedTotalByNom.toMutableMap()
        val lineDtos = mutableListOf<ProjectSupplyLineDto>()

        activeDemands.forEach { demand ->
            val demandLines = linesByDemand[demand.id].orEmpty().filter { it.status != "CANCELLED" }
            val offers = offersByDemand[demand.id].orEmpty()
            val offersCount = offers.size
            val returnedOffers = offers.filter { it.status == "NEEDS_REVISION" }
            val selectedOffer = offers.firstOrNull { it.status == "SELECTED" }
            val activeReviewOffers = offers.filter { it.status == "RECEIVED" || it.status == "SHORTLISTED" }
            val returnedReason = returnedOffers.firstNotNullOfOrNull {
                it.revisionNote?.trim()?.takeIf { note -> note.isNotEmpty() }
            }

            demandLines.forEachIndexed { idx, line ->
                val nomId = line.nomenclatureId
                val nom = nomenclatureNames[nomId]
                val requiredQty = line.quantity
                val freeStock = freeStockByNom[nomId] ?: BigDecimal.ZERO
                val sameNomLinesLeft = lineCountByNom[nomId] ?: 1
                val reservedPool = remainingReservedByNom[nomId] ?: BigDecimal.ZERO
                val reservedQty = if (sameNomLinesLeft <= 1 || idx == demandLines.lastIndex) {
                    val isLastForNom = activeDemands.flatMap { d ->
                        linesByDemand[d.id].orEmpty().filter { it.status != "CANCELLED" && it.nomenclatureId == nomId }
                    }.lastOrNull()?.id == line.id
                    if (isLastForNom) reservedPool else reservedPool.min(requiredQty)
                } else {
                    reservedPool.min(requiredQty)
                }
                remainingReservedByNom[nomId] = (reservedPool - reservedQty).max(BigDecimal.ZERO)

                val orderStat = orderStatsByLine[line.id]
                val orderedQty = orderStat?.orderedQty ?: BigDecimal.ZERO
                val receivedQty = receivedByLine[line.id] ?: BigDecimal.ZERO
                val awaitingDeliveryQty = (orderedQty - receivedQty).max(BigDecimal.ZERO)
                val unprocuredDeficitQty = (requiredQty - reservedQty - awaitingDeliveryQty).max(BigDecimal.ZERO)
                val remainingToEnsureQty = (requiredQty - reservedQty).max(BigDecimal.ZERO)

                val stallMsg = if (returnedOffers.isNotEmpty()) {
                    if (returnedReason != null) {
                        "Предложение возвращено ведущим на доработку: $returnedReason"
                    } else {
                        "Предложение возвращено ведущим на доработку"
                    }
                } else null

                val (stageLabel, nextActorRole, nextActionHint, isStalled, finalStallReason) = when {
                    remainingToEnsureQty <= BigDecimal.ZERO ->
                        SupplyStageInfo(
                            stageLabel = "Обеспечено",
                            nextActorRole = "Обеспечено",
                            nextActionHint = "Полностью обеспечено на складе под проект",
                            isStalled = false,
                            stallReason = null
                        )
                    awaitingDeliveryQty > BigDecimal.ZERO ->
                        SupplyStageInfo(
                            stageLabel = "В поставке",
                            nextActorRole = "Кладовщик",
                            nextActionHint = "В поставке у ${orderStat?.supplierName ?: "поставщика"} " +
                                "(заказ ${orderStat?.orderNumber ?: "—"}): поступило ${fmtQty(receivedQty)}, " +
                                "ожидается ещё ${fmtQty(awaitingDeliveryQty)} ${line.unit}",
                            isStalled = false,
                            stallReason = null
                        )
                    selectedOffer != null ->
                        SupplyStageInfo(
                            stageLabel = "Ожидает заказа",
                            nextActorRole = "Снабженец",
                            nextActionHint = "Согласовано ведущим, ждёт оформления заказа снабженцем",
                            isStalled = false,
                            stallReason = null
                        )
                    activeReviewOffers.isNotEmpty() ->
                        SupplyStageInfo(
                            stageLabel = "Согласование КП",
                            nextActorRole = "Ведущий инженер",
                            nextActionHint = "Ждёт выбора и согласования ведущим инженером",
                            isStalled = returnedOffers.isNotEmpty(),
                            stallReason = stallMsg
                        )
                    returnedOffers.isNotEmpty() ->
                        SupplyStageInfo(
                            stageLabel = "На доработке",
                            nextActorRole = "Снабженец",
                            nextActionHint = stallMsg!!,
                            isStalled = true,
                            stallReason = stallMsg
                        )
                    else ->
                        SupplyStageInfo(
                            stageLabel = "Ожидает снабжения",
                            nextActorRole = "Снабженец",
                            nextActionHint = "Ждёт проверки склада и предложений от снабженца",
                            isStalled = false,
                            stallReason = null
                        )
                }

                lineDtos += ProjectSupplyLineDto(
                    demandId = demand.id,
                    demandNumber = demand.number,
                    demandLineId = line.id,
                    nomenclatureId = nomId,
                    nomenclatureCode = nom?.first ?: "",
                    nomenclatureName = nom?.second ?: "",
                    unit = line.unit,
                    requiredQuantity = fmtQty(requiredQty),
                    warehouseFreeQuantity = fmtQty(freeStock),
                    reservedQuantity = fmtQty(reservedQty),
                    orderedQuantity = fmtQty(orderedQty),
                    receivedQuantity = fmtQty(receivedQty),
                    awaitingDeliveryQuantity = fmtQty(awaitingDeliveryQty),
                    unprocuredDeficitQuantity = fmtQty(unprocuredDeficitQty),
                    remainingToEnsureQuantity = fmtQty(remainingToEnsureQty),
                    offersCount = offersCount,
                    returnedOffersCount = returnedOffers.size,
                    selectedOfferId = selectedOffer?.id,
                    orderId = orderStat?.orderId,
                    orderNumber = orderStat?.orderNumber,
                    supplierName = orderStat?.supplierName,
                    stageLabel = stageLabel,
                    nextActorRole = nextActorRole,
                    nextActionHint = nextActionHint,
                    isStalled = isStalled,
                    stallReason = finalStallReason
                )
            }
        }

        val demandDetails = activeDemands.map { demand ->
            toDetail(demand, linesByDemand[demand.id].orEmpty())
        }

        return ProjectSupplyOverviewDto(
            projectId = project.id,
            projectNumber = project.number,
            projectName = project.name,
            lines = lineDtos,
            demands = demandDetails
        )
    }

    private data class SupplyStageInfo(
        val stageLabel: String,
        val nextActorRole: String,
        val nextActionHint: String,
        val isStalled: Boolean,
        val stallReason: String?
    )

    private data class DemandDiagnostics(
        val nextActorRole: String,
        val nextActionHint: String,
        val stallReason: String?,
        val hasReturnedOffers: Boolean
    )

    private fun computeDemandDiagnostics(
        demandStatus: String,
        sourceType: String,
        lines: List<DemandLinesRecord>,
        offers: List<org.example.project.generated.jooq.tables.records.SupplierOffersRecord>,
        orderStatsByLine: Map<Long, DemandRepository.DemandLineOrderStats>,
        receivedByLine: Map<Long, BigDecimal>
    ): DemandDiagnostics {
        val returnedOffers = offers.filter { it.status == "NEEDS_REVISION" }
        val hasReturnedOffers = returnedOffers.isNotEmpty()
        val returnedReason = returnedOffers.firstNotNullOfOrNull {
            it.revisionNote?.trim()?.takeIf { note -> note.isNotEmpty() }
        }
        val stallMsg = if (hasReturnedOffers) {
            if (returnedReason != null) {
                "Предложение возвращено ведущим на доработку: $returnedReason"
            } else {
                "Предложение возвращено ведущим на доработку"
            }
        } else null

        if (demandStatus == "CANCELLED") {
            return DemandDiagnostics("Отменена", "Заявка отменена", null, hasReturnedOffers)
        }
        if (demandStatus == "COVERED") {
            val hint = if (sourceType == "PROJECT") {
                "Полностью обеспечено на складе под проект"
            } else {
                "Полностью обеспечено на складе"
            }
            return DemandDiagnostics("Обеспечено", hint, null, hasReturnedOffers)
        }

        val activeLines = lines.filter { it.status != "CANCELLED" }
        val totalOrdered = activeLines.fold(BigDecimal.ZERO) { acc, l ->
            acc + (orderStatsByLine[l.id]?.orderedQty ?: BigDecimal.ZERO)
        }
        val totalReceived = activeLines.fold(BigDecimal.ZERO) { acc, l ->
            acc + (receivedByLine[l.id] ?: BigDecimal.ZERO)
        }
        val awaitingDelivery = (totalOrdered - totalReceived).max(BigDecimal.ZERO)
        val firstAwaitingLine = activeLines.firstOrNull { line ->
            val ordered = orderStatsByLine[line.id]?.orderedQty ?: BigDecimal.ZERO
            val received = receivedByLine[line.id] ?: BigDecimal.ZERO
            ordered > received
        }
        val selectedOffer = offers.firstOrNull { it.status == "SELECTED" }
        val activeReviewOffers = offers.filter { it.status == "RECEIVED" || it.status == "SHORTLISTED" }

        return when {
            awaitingDelivery > BigDecimal.ZERO -> {
                val stat = firstAwaitingLine?.let { orderStatsByLine[it.id] }
                val unitSuffix = firstAwaitingLine?.let { " ${it.unit}" } ?: ""
                DemandDiagnostics(
                    nextActorRole = "Кладовщик",
                    nextActionHint = "В поставке у ${stat?.supplierName ?: "поставщика"} " +
                        "(заказ ${stat?.orderNumber ?: "—"}): поступило ${fmtQty(totalReceived)}, " +
                        "ожидается ещё ${fmtQty(awaitingDelivery)}$unitSuffix",
                    stallReason = stallMsg,
                    hasReturnedOffers = hasReturnedOffers
                )
            }
            selectedOffer != null -> DemandDiagnostics(
                nextActorRole = "Снабженец",
                nextActionHint = "Согласовано ведущим, ждёт оформления заказа снабженцем",
                stallReason = stallMsg,
                hasReturnedOffers = hasReturnedOffers
            )
            activeReviewOffers.isNotEmpty() -> DemandDiagnostics(
                nextActorRole = "Ведущий инженер",
                nextActionHint = "Ждёт выбора и согласования ведущим инженером",
                stallReason = stallMsg,
                hasReturnedOffers = hasReturnedOffers
            )
            hasReturnedOffers -> DemandDiagnostics(
                nextActorRole = "Снабженец",
                nextActionHint = stallMsg!!,
                stallReason = stallMsg,
                hasReturnedOffers = true
            )
            else -> DemandDiagnostics(
                nextActorRole = "Снабженец",
                nextActionHint = "Ждёт проверки склада и предложений от снабженца",
                stallReason = null,
                hasReturnedOffers = false
            )
        }
    }

    private fun fmtQty(value: BigDecimal): String =
        if (value.compareTo(BigDecimal.ZERO) == 0) "0"
        else value.stripTrailingZeros().toPlainString()

    private fun getDetail(id: Long, currentUser: CurrentUser): DemandDetailDto {
        val demand = demandRepository.findHeaderById(id) ?: throw NotFoundException("demand", id)
        ensureAccess(demand, currentUser)
        val lines = demandRepository.listLines(id)
        return toDetail(demand, lines)
    }

    private fun hasDemandReadAccess(currentUser: CurrentUser): Boolean =
        currentUser.hasPermission("demands", "read_any") ||
            currentUser.hasPermission("suppliers", "read") ||
            currentUser.hasPermission("suppliers", "offer") ||
            currentUser.hasPermission("suppliers", "order")

    private fun ensureAccess(demand: DemandsRecord, currentUser: CurrentUser) {
        if (hasDemandReadAccess(currentUser)) return
        if (demand.initiatorId == currentUser.userId || demand.createdBy == currentUser.userId) return
        val projectId = demand.projectId
        if (projectId != null && projectRepository.isMember(projectId, currentUser.userId)) return
        throw ForbiddenException("Access denied")
    }

    private fun ensureAccess(demand: DemandRepository.DemandHeaderProjection, currentUser: CurrentUser) {
        if (hasDemandReadAccess(currentUser)) return
        if (demand.initiatorId == currentUser.userId || demand.createdBy == currentUser.userId) return
        val projectId = demand.projectId
        if (projectId != null && projectRepository.isMember(projectId, currentUser.userId)) return
        throw ForbiddenException("Access denied")
    }

    private fun validateSourceSpecifics(request: CreateDemandRequest, currentUser: CurrentUser) {
        val projectId = request.projectId
        when (request.sourceType) {
            DemandSourceType.PROJECT -> {
                if (projectId == null) {
                    throw ValidationException("projectId is required for PROJECT demand", mapOf("projectId" to "required"))
                }
                val project = projectRepository.findById(projectId)
                    ?: throw NotFoundException("project", projectId)
                val hasProjectAccess = projectRepository.isMember(projectId, currentUser.userId) ||
                    project.ownerId == currentUser.userId ||
                    currentUser.hasPermission("projects", "read_any")
                if (!hasProjectAccess) {
                    throw ForbiddenException("You are not a member of this project")
                }
            }
            DemandSourceType.STOCKPILE -> {
                if (request.projectId != null) {
                    throw ValidationException("projectId must be null for STOCKPILE demand", mapOf("projectId" to "must_be_null"))
                }
                if (request.stockpileNote.isNullOrBlank()) {
                    throw ValidationException("stockpileNote is required for STOCKPILE demand", mapOf("stockpileNote" to "required"))
                }
            }
            DemandSourceType.MANUAL -> {
                if (request.projectId != null) {
                    throw ValidationException("projectId must be null for MANUAL demand", mapOf("projectId" to "must_be_null"))
                }
            }
        }
    }

    private fun validateManualLines(lines: List<DemandLineInput>) {
        if (lines.isEmpty()) {
            throw ValidationException("At least one line is required", mapOf("lines" to "empty"))
        }
        val lineNos = mutableSetOf<Int>()
        lines.forEachIndexed { index, line ->
            if (!lineNos.add(line.lineNo)) {
                throw ValidationException("Duplicate line number: ${line.lineNo}", mapOf("lines[$index].lineNo" to "duplicate"))
            }
            if (!bomRepository.nomenclatureExists(line.nomenclatureId)) {
                throw NotFoundException("nomenclature", line.nomenclatureId)
            }
            parseQuantity(line.quantity, "lines[$index].quantity")
            if (line.unit.isBlank()) {
                throw ValidationException("Unit is required", mapOf("lines[$index].unit" to "required"))
            }
        }
    }

    private fun toDetail(header: DemandRepository.DemandHeaderProjection, lines: List<DemandLinesRecord>): DemandDetailDto {
        return buildDetailDto(header.toHeaderDto(), lines)
    }

    private fun toDetail(header: DemandsRecord, lines: List<DemandLinesRecord>): DemandDetailDto {
        return buildDetailDto(header.toHeaderDto(), lines)
    }

    private fun buildDetailDto(headerDto: DemandHeaderDto, lines: List<DemandLinesRecord>): DemandDetailDto {
        val enrichments = demandRepository.headerEnrichments(
            demandIds = listOf(headerDto.id),
            initiatorIds = listOf(headerDto.initiatorId)
        )
        val info = enrichments.byDemand[headerDto.id]
        val offers = supplierService.listOffersForDemandInternal(headerDto.id)
        val orders = purchaseOrderService.listOrdersForDemandInternal(headerDto.id)
        val receipts = demandRepository.listLinkedReceipts(orders.map { it.id })
        val nomenclatureIds = lines.map { it.nomenclatureId }.toSet()
        val nomenclatureNames = demandRepository.nomenclatureNamesBatch(nomenclatureIds)
        val stock = bomRepository.stockSummary(nomenclatureIds, null)
        val stockByNomenclature = nomenclatureIds.associateWith { nomId ->
            (stock[nomId]?.availableQty ?: BigDecimal.ZERO).stripTrailingZeros().toPlainString()
        }
        val projectInfo = headerDto.projectId?.let { demandRepository.projectNamesBatch(listOf(it))[it] }
        val lineIds = lines.map { it.id }
        val orderStatsByLine = demandRepository.demandLineOrderStatsBatch(lineIds)
        val receivedByLine = demandRepository.demandLineReceivedQtyBatch(lineIds)
        val offerRecords = demandRepository.offersForDemandsBatch(listOf(headerDto.id))[headerDto.id].orEmpty()
        val diag = computeDemandDiagnostics(
            demandStatus = headerDto.status.name,
            sourceType = headerDto.sourceType.name,
            lines = lines,
            offers = offerRecords,
            orderStatsByLine = orderStatsByLine,
            receivedByLine = receivedByLine
        )
        val enrichedHeader = headerDto.copy(
            initiatorName = enrichments.initiatorNames[headerDto.initiatorId],
            selectedSupplierName = info?.selectedSupplierName,
            selectedGrossTotal = info?.selectedGrossTotal,
            isPaid = info?.isPaid ?: false,
            paidAt = info?.paidAt,
            projectNumber = projectInfo?.first,
            projectName = projectInfo?.second,
            nextActorRole = diag.nextActorRole,
            nextActionHint = diag.nextActionHint,
            stallReason = diag.stallReason,
            hasReturnedOffers = diag.hasReturnedOffers
        )
        return DemandDetailDto(
            header = enrichedHeader,
            lines = lines.map { it.toLineDto(nomenclatureNames[it.nomenclatureId]) },
            offersCount = offers.size,
            ordersCount = orders.size,
            offers = offers,
            orders = orders,
            receipts = receipts,
            stockByNomenclature = stockByNomenclature
        )
    }

    private inline fun <reified T> fingerprint(value: T): String {
        val bytes = Json { encodeDefaults = true }.encodeToString(value).encodeToByteArray()
        return MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }

    private fun parseQuantity(value: String, field: String): BigDecimal {
        val parsed = value.trim().replace(" ", "").replace(',', '.').toBigDecimalOrNull()
            ?: throw ValidationException("Invalid quantity: $value", mapOf(field to "invalid"))
        if (parsed.signum() <= 0) {
            throw ValidationException("Quantity must be positive", mapOf(field to "not_positive"))
        }
        return parsed
    }

    private fun parseDate(value: String): LocalDate {
        return try {
            LocalDate.parse(value.trim())
        } catch (e: Exception) {
            throw ValidationException("Invalid date: $value", mapOf("requiredDate" to "invalid"))
        }
    }

    private fun DemandRepository.DemandHeaderProjection.toHeaderDto(): DemandHeaderDto = DemandHeaderDto(
        id = this.id,
        number = this.number,
        sourceType = DemandSourceType.valueOf(this.sourceType),
        projectId = this.projectId,
        stockpileNote = this.stockpileNote,
        initiatorId = this.initiatorId,
        requiredDate = this.requiredDate?.toString(),
        priority = DemandPriority.valueOf(this.priority),
        status = DemandStatus.valueOf(this.status),
        approvedBy = this.approvedBy,
        approvedAt = this.approvedAt?.toString(),
        createdAt = this.createdAt?.toString(),
        createdBy = this.createdBy,
        version = this.version,
        sourceBomRevisionId = this.sourceBomRevisionId,
        bomReleaseQuantity = this.bomReleaseQuantity?.stripTrailingZeros()?.toPlainString(),
        bomRevisionVersion = this.bomRevisionVersion,
        idempotencyKey = this.idempotencyKey
    )

    private fun DemandsRecord.toHeaderDto(): DemandHeaderDto = DemandHeaderDto(
        id = this.id,
        number = this.number,
        sourceType = DemandSourceType.valueOf(this.sourceType),
        projectId = this.projectId,
        stockpileNote = this.stockpileNote,
        initiatorId = this.initiatorId,
        requiredDate = this.requiredDate?.toString(),
        priority = DemandPriority.valueOf(this.priority),
        status = DemandStatus.valueOf(this.status),
        approvedBy = this.approvedBy,
        approvedAt = this.approvedAt?.toString(),
        createdAt = this.createdAt?.toString(),
        createdBy = this.createdBy,
        version = this.version ?: 0,
        sourceBomRevisionId = this.sourceBomRevisionId,
        bomReleaseQuantity = this.bomReleaseQuantity?.stripTrailingZeros()?.toPlainString(),
        bomRevisionVersion = this.bomRevisionVersion,
        idempotencyKey = this.idempotencyKey
    )

    private fun DemandLinesRecord.toLineDto(
        nomenclature: Pair<String, String>? = null
    ): DemandLineDto = DemandLineDto(
        id = this.id,
        demandId = this.demandId,
        lineNo = this.lineNo,
        nomenclatureId = this.nomenclatureId,
        nomenclatureCode = nomenclature?.first,
        nomenclatureName = nomenclature?.second,
        quantity = this.quantity.stripTrailingZeros().toPlainString(),
        unit = this.unit,
        coveredQuantity = this.coveredQuantity.stripTrailingZeros().toPlainString(),
        stockReservedQuantity = fmtQty(this.stockReservedQuantity),
        deficitQuantity = this.deficitQuantity?.stripTrailingZeros()?.toPlainString(),
        sourceBomRevisionId = this.sourceBomRevisionId,
        status = DemandLineStatus.valueOf(this.status),
        createdAt = this.createdAt?.toString(),
        createdBy = this.createdBy,
        version = this.version ?: 0
    )
}
