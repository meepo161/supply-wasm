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
    private val auditService: AuditService,
    private val dsl: DSLContext,
    private val approvalGuard: ApprovalGuard
) {

    private val calculator = BomExplosionCalculator(bomRepository)

    fun create(request: CreateDemandRequest, currentUser: CurrentUser): DemandDetailDto {
        currentUser.requirePermission("demands", "create")
        if (request.sourceType == DemandSourceType.PROJECT) {
            throw ValidationException(
                "PROJECT demand must be created from a BOM revision",
                mapOf("sourceType" to "use_from_bom_endpoint")
            )
        }
        validateSourceSpecifics(request)
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
                idempotencyRequestHash = requestHash
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
                mapOf("number" to created.number, "sourceType" to created.sourceType)
            )
            toDetail(created, lines)
        })
    }

    fun createFromBom(request: CreateDemandFromBomRequest, currentUser: CurrentUser): DemandDetailDto {
        currentUser.requirePermission("demands", "create")
        val project = projectRepository.findById(request.projectId)
            ?: throw NotFoundException("project", request.projectId)
        if (!projectRepository.isMember(project.id, currentUser.userId)) {
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

        return dsl.transactionResult(TransactionalCallable {
            val number = demandRepository.generateNumber()
            val header = demandRepository.createDemand(
                number = number,
                sourceType = "PROJECT",
                projectId = project.id,
                stockpileNote = null,
                initiatorId = currentUser.userId,
                requiredDate = request.requiredDate?.let { parseDate(it) },
                priority = (request.priority ?: DemandPriority.NORMAL).name,
                createdBy = currentUser.userId,
                sourceBomRevisionId = revision.id,
                bomReleaseQuantity = releaseQuantity,
                bomRevisionVersion = revision.version,
                idempotencyKey = idempotencyKey,
                idempotencyRequestHash = requestHash
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
        return demandRepository.listByProject(projectId).map { it.toHeaderDto() }
    }

    fun list(currentUser: CurrentUser): List<DemandHeaderDto> {
        val readAny = currentUser.hasPermission("demands", "read_any")
        val records = demandRepository.listAccessible(currentUser.userId, readAny)
        val summaries = demandRepository.lineSummaries(records.map { it.id })
        return records.map { record ->
            val lines = summaries[record.id].orEmpty()
            record.toHeaderDto().copy(
                lineCount = lines.size,
                itemsSummary = itemsSummary(lines),
                quantitySummary = quantitySummary(lines)
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

    private fun getDetail(id: Long, currentUser: CurrentUser): DemandDetailDto {
        val demand = demandRepository.findHeaderById(id) ?: throw NotFoundException("demand", id)
        ensureAccess(demand, currentUser)
        val lines = demandRepository.listLines(id)
        return toDetail(demand, lines)
    }

    private fun ensureAccess(demand: DemandsRecord, currentUser: CurrentUser) {
        if (currentUser.hasPermission("demands", "read_any")) return
        if (demand.initiatorId == currentUser.userId || demand.createdBy == currentUser.userId) return
        val projectId = demand.projectId
        if (projectId != null && projectRepository.isMember(projectId, currentUser.userId)) return
        throw ForbiddenException("Access denied")
    }

    private fun ensureAccess(demand: DemandRepository.DemandHeaderProjection, currentUser: CurrentUser) {
        if (currentUser.hasPermission("demands", "read_any")) return
        if (demand.initiatorId == currentUser.userId || demand.createdBy == currentUser.userId) return
        val projectId = demand.projectId
        if (projectId != null && projectRepository.isMember(projectId, currentUser.userId)) return
        throw ForbiddenException("Access denied")
    }

    private fun validateSourceSpecifics(request: CreateDemandRequest) {
        val projectId = request.projectId
        when (request.sourceType) {
            DemandSourceType.PROJECT -> {
                if (projectId == null) {
                    throw ValidationException("projectId is required for PROJECT demand", mapOf("projectId" to "required"))
                }
                if (projectRepository.findById(projectId) == null) {
                    throw NotFoundException("project", projectId)
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
        return DemandDetailDto(
            header = header.toHeaderDto(),
            lines = lines.map { it.toLineDto() },
            offersCount = demandRepository.countOffers(header.id),
            ordersCount = demandRepository.countOrders(header.id)
        )
    }

    private fun toDetail(header: DemandsRecord, lines: List<DemandLinesRecord>): DemandDetailDto {
        return DemandDetailDto(
            header = header.toHeaderDto(),
            lines = lines.map { it.toLineDto() },
            offersCount = demandRepository.countOffers(header.id),
            ordersCount = demandRepository.countOrders(header.id)
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

    private fun DemandLinesRecord.toLineDto(): DemandLineDto = DemandLineDto(
        id = this.id,
        demandId = this.demandId,
        lineNo = this.lineNo,
        nomenclatureId = this.nomenclatureId,
        quantity = this.quantity.stripTrailingZeros().toPlainString(),
        unit = this.unit,
        coveredQuantity = this.coveredQuantity.stripTrailingZeros().toPlainString(),
        deficitQuantity = this.deficitQuantity?.stripTrailingZeros()?.toPlainString(),
        sourceBomRevisionId = this.sourceBomRevisionId,
        status = DemandLineStatus.valueOf(this.status),
        createdAt = this.createdAt?.toString(),
        createdBy = this.createdBy,
        version = this.version ?: 0
    )
}
