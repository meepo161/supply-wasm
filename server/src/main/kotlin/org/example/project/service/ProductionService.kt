package org.example.project.service

import org.example.project.dto.AddInstanceSoftwareRequest
import org.example.project.dto.CancelProductionOrderRequest
import org.example.project.dto.CompleteProductionOrderRequest
import org.example.project.dto.CreateProductionOrderRequest
import org.example.project.dto.CreateProductionStageInput
import org.example.project.dto.ProductInstanceDetailDto
import org.example.project.dto.ProductInstanceDto
import org.example.project.dto.RecordInstanceRemarkRequest
import org.example.project.dto.RecordInstanceTestRequest
import org.example.project.dto.ProductionOrderActionRequest
import org.example.project.dto.ProductionOrderDto
import org.example.project.dto.ProductionOrderStageDto
import org.example.project.dto.ProductionOrderStatus
import org.example.project.dto.ProductionStageActionRequest
import org.example.project.dto.ProductionStageAssigneeDto
import org.example.project.dto.ProductionStageAssigneeRole
import org.example.project.dto.ProductionStageStatus
import org.example.project.dto.ProductionStageType
import org.example.project.exception.ConflictException
import org.example.project.exception.NotFoundException
import org.example.project.exception.ValidationException
import org.example.project.generated.jooq.tables.records.ProductInstancesRecord
import org.example.project.generated.jooq.tables.records.ProductionOrderStagesRecord
import org.example.project.generated.jooq.tables.records.ProductionOrdersRecord
import org.example.project.model.CurrentUser
import org.example.project.repository.ProductionRepository
import org.jooq.DSLContext
import org.jooq.TransactionalCallable
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * Производственные заказы (§15 ТЗ, PROD-02).
 *
 * Правила:
 * - заказ ссылается только на APPROVED/ACTIVE ревизию BOM своего изделия
 *   (проверка сервера + триггер `trg_production_orders_bom_revision_guard`);
 * - жизненный цикл: DRAFT -> PLANNED -> IN_PROGRESS -> (ON_HOLD) -> COMPLETED -> CLOSED,
 *   CANCELLED из DRAFT/PLANNED/ON_HOLD; все переходы условные по `version`;
 * - карточки экземпляров изделия создаются только при завершении выпуска,
 *   серийный номер уникален;
 * - этапы (PREPARATION…COMPLETION) ведутся по последовательности, каждый
 *   переход фиксирует фактическое время.
 *
 * Проведение складских операций и резервирование материалов — вне объёма
 * (PROD-07/PROD-08).
 */
class ProductionService(
    private val productionRepository: ProductionRepository,
    private val auditService: AuditService,
    private val dsl: DSLContext
) {

    fun create(request: CreateProductionOrderRequest, currentUser: CurrentUser): ProductionOrderDto {
        currentUser.requirePermission("production", "manage")
        if (request.plannedQuantity <= 0) {
            throw ValidationException("Planned quantity must be positive", mapOf("plannedQuantity" to "not_positive"))
        }
        if (!productionRepository.projectExists(request.projectId)) {
            throw NotFoundException("project", request.projectId)
        }
        if (!productionRepository.productModelExists(request.productModelId)) {
            throw NotFoundException("product_model", request.productModelId)
        }
        val revision = productionRepository.revisionStatus(request.bomRevisionId)
            ?: throw NotFoundException("product_revision", request.bomRevisionId)
        val (revisionStatus, revisionModelId) = revision
        if (revisionModelId != request.productModelId) {
            throw ValidationException(
                "BOM revision belongs to another product model",
                mapOf("bomRevisionId" to "mismatch")
            )
        }
        if (revisionStatus !in setOf("APPROVED", "ACTIVE")) {
            throw ConflictException(
                "Production order requires an APPROVED or ACTIVE BOM revision, got $revisionStatus"
            )
        }
        if (!productionRepository.userExists(request.responsibleId)) {
            throw NotFoundException("user", request.responsibleId)
        }
        val departmentId = request.departmentId
        if (departmentId != null && !productionRepository.departmentExists(departmentId)) {
            throw NotFoundException("department", departmentId)
        }
        val stages = request.stages
        if (stages.map { it.sequence }.size != stages.map { it.sequence }.distinct().size) {
            throw ValidationException("Stage sequences must be unique", mapOf("sequence" to "duplicate"))
        }
        val plannedStartAt = parseDateTime(request.plannedStartAt, "plannedStartAt")
        val plannedEndAt = parseDateTime(request.plannedEndAt, "plannedEndAt")
        if (plannedStartAt != null && plannedEndAt != null && plannedEndAt < plannedStartAt) {
            throw ValidationException("Planned end must not be before start", mapOf("plannedEndAt" to "before_start"))
        }
        return dsl.transactionResult(TransactionalCallable {
            val number = productionRepository.generateNumber()
            val order = productionRepository.insertOrder(
                number = number,
                projectId = request.projectId,
                productModelId = request.productModelId,
                bomRevisionId = request.bomRevisionId,
                plannedQuantity = request.plannedQuantity,
                plannedStartAt = plannedStartAt,
                plannedEndAt = plannedEndAt,
                responsibleId = request.responsibleId,
                departmentId = departmentId,
                priority = request.priority.trim().ifEmpty { "NORMAL" },
                createdBy = currentUser.userId
            )
            insertStages(order.id, stages, currentUser)
            auditService.log(
                currentUser.userId, "CREATE", "PRODUCTION_ORDER", order.id,
                mapOf("number" to number, "plannedQuantity" to request.plannedQuantity)
            )
            loadDto(order)
        })
    }

    fun list(
        projectId: Long?,
        productModelId: Long?,
        status: ProductionOrderStatus?,
        currentUser: CurrentUser
    ): List<ProductionOrderDto> {
        currentUser.requirePermission("production", "read")
        return productionRepository.listOrders(projectId, productModelId, status?.name).map { order ->
            loadDto(order, withStages = false)
        }
    }

    fun get(id: Long, currentUser: CurrentUser): ProductionOrderDto {
        currentUser.requirePermission("production", "read")
        val order = productionRepository.findOrderById(id) ?: throw NotFoundException("production_order", id)
        return loadDto(order)
    }

    fun plan(id: Long, request: ProductionOrderActionRequest, currentUser: CurrentUser): ProductionOrderDto =
        transition(id, request, currentUser, "PLAN", listOf("DRAFT"), "PLANNED")

    fun start(id: Long, request: ProductionOrderActionRequest, currentUser: CurrentUser): ProductionOrderDto {
        return dsl.transactionResult(TransactionalCallable {
            val order = productionRepository.lockOrderById(id) ?: throw NotFoundException("production_order", id)
            if (order.version != request.version) {
                throw ConflictException("Production order was modified concurrently")
            }
            val updated = productionRepository.updateOrderStatus(
                id = id,
                expectedVersion = request.version,
                fromStatuses = listOf("PLANNED"),
                newStatus = "IN_PROGRESS",
                actualStartAt = OffsetDateTime.now()
            ) ?: throw ConflictException("Production order can be started only from PLANNED status")
            // Первый ожидающий этап начинается вместе с заказом.
            productionRepository.listStages(id)
                .filter { it.status == "PENDING" }
                .minByOrNull { it.sequence }
                ?.let { stage ->
                    productionRepository.updateStageStatus(
                        id = stage.id,
                        expectedVersion = stage.version ?: 0,
                        fromStatuses = listOf("PENDING"),
                        newStatus = "IN_PROGRESS",
                        actualStartAt = OffsetDateTime.now()
                    )
                }
            auditService.log(
                currentUser.userId, "START", "PRODUCTION_ORDER", id,
                mapOf("number" to updated.number)
            )
            loadDto(updated)
        })
    }

    fun hold(id: Long, request: ProductionOrderActionRequest, currentUser: CurrentUser): ProductionOrderDto =
        transition(id, request, currentUser, "HOLD", listOf("IN_PROGRESS"), "ON_HOLD")

    fun resume(id: Long, request: ProductionOrderActionRequest, currentUser: CurrentUser): ProductionOrderDto =
        transition(id, request, currentUser, "RESUME", listOf("ON_HOLD"), "IN_PROGRESS")

    // Завершение выпуска фиксирует фактическое количество и создаёт карточки
    // экземпляров; количество не может превысить плановое (CHECK схемы).
    fun complete(
        id: Long,
        request: CompleteProductionOrderRequest,
        currentUser: CurrentUser
    ): ProductionOrderDto {
        currentUser.requirePermission("production", "manage")
        return dsl.transactionResult(TransactionalCallable {
            val order = productionRepository.lockOrderById(id) ?: throw NotFoundException("production_order", id)
            if (order.version != request.version) {
                throw ConflictException("Production order was modified concurrently")
            }
            if (order.status != "IN_PROGRESS") {
                throw ConflictException("Production order can be completed only from IN_PROGRESS status")
            }
            if (request.actualQuantity <= 0 || request.actualQuantity > order.plannedQuantity) {
                throw ValidationException(
                    "Actual quantity must be between 1 and planned quantity ${order.plannedQuantity}",
                    mapOf("actualQuantity" to "out_of_range")
                )
            }
            if (request.serialNumbers.size != request.actualQuantity) {
                throw ValidationException(
                    "Exactly one serial number per manufactured unit is required",
                    mapOf("serialNumbers" to "count_mismatch")
                )
            }
            val normalized = request.serialNumbers.map { it.trim() }
            if (normalized.any { it.isEmpty() }) {
                throw ValidationException("Serial number must not be blank", mapOf("serialNumbers" to "blank"))
            }
            if (normalized.size != normalized.distinct().size) {
                throw ValidationException("Serial numbers must be unique", mapOf("serialNumbers" to "duplicate"))
            }
            val duplicates = normalized.filter { productionRepository.serialNumberExists(it) }
            if (duplicates.isNotEmpty()) {
                throw ConflictException("Serial number is already used: ${duplicates.first()}")
            }
            if (productionRepository.countOpenStages(id) > 0) {
                throw ConflictException(
                    "All production stages must be completed or skipped before completion"
                )
            }
            normalized.forEach { serialNumber ->
                productionRepository.insertInstance(
                    orderId = id,
                    projectId = order.projectId,
                    productModelId = order.productModelId,
                    bomRevisionId = order.bomRevisionId,
                    serialNumber = serialNumber,
                    createdBy = currentUser.userId
                )
            }
            val completed = productionRepository.updateOrderStatus(
                id = id,
                expectedVersion = request.version,
                fromStatuses = listOf("IN_PROGRESS"),
                newStatus = "COMPLETED",
                actualEndAt = OffsetDateTime.now(),
                actualQuantity = request.actualQuantity
            ) ?: throw ConflictException("Production order was modified concurrently")
            auditService.log(
                currentUser.userId, "COMPLETE", "PRODUCTION_ORDER", id,
                mapOf("number" to completed.number, "actualQuantity" to request.actualQuantity)
            )
            loadDto(completed)
        })
    }

    fun close(id: Long, request: ProductionOrderActionRequest, currentUser: CurrentUser): ProductionOrderDto =
        transition(id, request, currentUser, "CLOSE", listOf("COMPLETED"), "CLOSED")

    fun cancel(
        id: Long,
        request: CancelProductionOrderRequest,
        currentUser: CurrentUser
    ): ProductionOrderDto {
        currentUser.requirePermission("production", "manage")
        if (request.reason.isBlank()) {
            throw ValidationException("Cancel reason is required", mapOf("reason" to "required"))
        }
        return dsl.transactionResult(TransactionalCallable {
            val updated = productionRepository.updateOrderStatus(
                id = id,
                expectedVersion = request.version,
                fromStatuses = listOf("DRAFT", "PLANNED", "ON_HOLD"),
                newStatus = "CANCELLED"
            ) ?: throw ConflictException(
                "Production order can be cancelled only from DRAFT, PLANNED or ON_HOLD status"
            )
            auditService.log(
                currentUser.userId, "CANCEL", "PRODUCTION_ORDER", id,
                mapOf("number" to updated.number, "reason" to request.reason.trim())
            )
            loadDto(updated)
        })
    }

    fun startStage(
        orderId: Long,
        stageId: Long,
        request: ProductionStageActionRequest,
        currentUser: CurrentUser
    ): ProductionOrderDto = stageTransition(
        orderId, stageId, request, currentUser,
        fromStatuses = listOf("PENDING"), newStatus = "IN_PROGRESS", actualStartAt = OffsetDateTime.now()
    )

    fun completeStage(
        orderId: Long,
        stageId: Long,
        request: ProductionStageActionRequest,
        currentUser: CurrentUser
    ): ProductionOrderDto = stageTransition(
        orderId, stageId, request, currentUser,
        fromStatuses = listOf("IN_PROGRESS"), newStatus = "COMPLETED", actualEndAt = OffsetDateTime.now()
    )

    // Пропуск этапа допустим только с основанием.
    fun skipStage(
        orderId: Long,
        stageId: Long,
        request: ProductionStageActionRequest,
        currentUser: CurrentUser
    ): ProductionOrderDto {
        val reason = request.reason?.trim()
        if (reason.isNullOrBlank()) {
            throw ValidationException("Skip reason is required", mapOf("reason" to "required"))
        }
        return stageTransition(
            orderId, stageId, request, currentUser,
            fromStatuses = listOf("PENDING"), newStatus = "SKIPPED",
            actualEndAt = OffsetDateTime.now(), skipReason = reason
        )
    }

    fun listInstances(orderId: Long, currentUser: CurrentUser): List<ProductInstanceDto> {
        currentUser.requirePermission("production", "read")
        productionRepository.findOrderById(orderId) ?: throw NotFoundException("production_order", orderId)
        return productionRepository.listInstances(orderId).map { it.toDto() }
    }

    fun getInstance(id: Long, currentUser: CurrentUser): ProductInstanceDetailDto {
        currentUser.requirePermission("production", "read")
        return productionRepository.findInstanceDetailById(id)
            ?: throw NotFoundException("product_instance", id)
    }

    fun recordInstanceTest(
        id: Long,
        request: RecordInstanceTestRequest,
        currentUser: CurrentUser
    ): ProductInstanceDetailDto {
        currentUser.requirePermission("production", "manage")
        val normalizedResult = request.result.trim().uppercase()
        if (normalizedResult !in setOf("GOOD", "NOT_GOOD")) {
            throw ValidationException("Test result must be GOOD or NOT_GOOD")
        }
        val normalizedDecision = request.technicalDecision.trim().uppercase()
        if (normalizedDecision !in setOf("PENDING", "GOOD", "NOT_GOOD")) {
            throw ValidationException("Technical decision must be PENDING, GOOD, or NOT_GOOD")
        }
        return dsl.transactionResult(TransactionalCallable {
            val orderId = productionRepository.findInstanceOrderId(id)
                ?: throw NotFoundException("product_instance", id)
            val testId = productionRepository.insertInstanceTest(
                instanceId = id,
                orderId = orderId,
                result = normalizedResult,
                notes = request.notes?.trim()?.takeIf { it.isNotEmpty() },
                protocolDocumentVersionId = request.protocolDocumentVersionId,
                technicalDecision = normalizedDecision,
                userId = currentUser.userId
            )
            auditService.log(
                currentUser.userId,
                "RECORD_TEST",
                "PRODUCT_INSTANCE",
                id,
                mapOf("testId" to testId, "result" to normalizedResult, "technicalDecision" to normalizedDecision)
            )
            productionRepository.findInstanceDetailById(id)
                ?: throw NotFoundException("product_instance", id)
        })
    }



    fun recordInstanceRemark(
        id: Long,
        request: RecordInstanceRemarkRequest,
        currentUser: CurrentUser
    ): ProductInstanceDetailDto {
        currentUser.requirePermission("production", "manage")
        val description = request.description.trim()
        if (description.isEmpty()) {
            throw ValidationException("Remark description must not be blank")
        }
        val normalizedSeverity = request.severity.trim().uppercase()
        if (normalizedSeverity !in setOf("MINOR", "CRITICAL")) {
            throw ValidationException("Severity must be MINOR or CRITICAL")
        }
        return dsl.transactionResult(TransactionalCallable {
            productionRepository.findInstanceOrderId(id)
                ?: throw NotFoundException("product_instance", id)
            val remarkId = productionRepository.insertInstanceRemark(
                instanceId = id,
                testId = request.testId,
                description = description,
                severity = normalizedSeverity,
                blocksShipment = request.blocksShipment,
                userId = currentUser.userId
            )
            auditService.log(
                currentUser.userId,
                "RECORD_REMARK",
                "PRODUCT_INSTANCE",
                id,
                mapOf("remarkId" to remarkId, "severity" to normalizedSeverity, "blocksShipment" to request.blocksShipment)
            )
            productionRepository.findInstanceDetailById(id)
                ?: throw NotFoundException("product_instance", id)
        })
    }



    fun addInstanceSoftware(
        id: Long,
        request: AddInstanceSoftwareRequest,
        currentUser: CurrentUser
    ): ProductInstanceDetailDto {
        currentUser.requirePermission("production", "manage")
        val name = request.name.trim()
        val version = request.version.trim()
        if (name.isEmpty() || version.isEmpty()) {
            throw ValidationException("Software name and version must not be blank")
        }
        return dsl.transactionResult(TransactionalCallable {
            productionRepository.findInstanceOrderId(id)
                ?: throw NotFoundException("product_instance", id)
            val swId = productionRepository.insertInstanceSoftware(
                instanceId = id,
                name = name,
                version = version,
                build = request.build?.trim()?.takeIf { it.isNotEmpty() },
                userId = currentUser.userId
            )
            auditService.log(
                currentUser.userId,
                "ADD_SOFTWARE",
                "PRODUCT_INSTANCE",
                id,
                mapOf("softwareId" to swId, "name" to name, "version" to version)
            )
            productionRepository.findInstanceDetailById(id)
                ?: throw NotFoundException("product_instance", id)
        })
    }

    fun resolveInstanceRemark(
        id: Long,
        remarkId: Long,
        currentUser: CurrentUser
    ): ProductInstanceDetailDto {
        currentUser.requirePermission("production", "manage")
        return dsl.transactionResult(TransactionalCallable {
            productionRepository.findInstanceOrderId(id)
                ?: throw NotFoundException("product_instance", id)
            val updated = productionRepository.resolveInstanceRemark(id, remarkId, "RESOLVED", currentUser.userId)
            if (updated == 0) {
                throw ConflictException("Remark is already resolved or does not exist")
            }
            auditService.log(
                currentUser.userId,
                "RESOLVE_REMARK",
                "PRODUCT_INSTANCE",
                id,
                mapOf("remarkId" to remarkId)
            )
            productionRepository.findInstanceDetailById(id)
                ?: throw NotFoundException("product_instance", id)
        })
    }

    fun acceptInstance(id: Long, currentUser: CurrentUser): ProductInstanceDetailDto {
        currentUser.requirePermission("production", "manage")
        return dsl.transactionResult(TransactionalCallable {
            val detail = productionRepository.findInstanceDetailById(id)
                ?: throw NotFoundException("product_instance", id)
            if (detail.status == "SHIPPED") {
                throw ConflictException("Shipped instance cannot be accepted again")
            }
            if (productionRepository.hasBlockingOpenRemarks(id)) {
                throw ConflictException("Cannot accept instance with open shipment-blocking remarks")
            }
            if (productionRepository.hasUnresolvedFailedTests(id)) {
                throw ConflictException("Cannot accept instance with unresolved failed tests")
            }
            productionRepository.updateInstanceStatus(id, "ACCEPTED", acceptedAt = OffsetDateTime.now())
            auditService.log(currentUser.userId, "ACCEPT_INSTANCE", "PRODUCT_INSTANCE", id, emptyMap())
            productionRepository.findInstanceDetailById(id)
                ?: throw NotFoundException("product_instance", id)
        })
    }

    fun shipInstance(id: Long, currentUser: CurrentUser): ProductInstanceDetailDto {
        currentUser.requirePermission("production", "manage")
        return dsl.transactionResult(TransactionalCallable {
            val detail = productionRepository.findInstanceDetailById(id)
                ?: throw NotFoundException("product_instance", id)
            if (detail.status != "ACCEPTED") {
                throw ConflictException("Only ACCEPTED instance can be shipped")
            }
            if (productionRepository.hasBlockingOpenRemarks(id)) {
                throw ConflictException("Shipment blocked by open critical/blocking remarks")
            }
            productionRepository.updateInstanceStatus(id, "SHIPPED", shippedAt = OffsetDateTime.now())
            auditService.log(currentUser.userId, "SHIP_INSTANCE", "PRODUCT_INSTANCE", id, emptyMap())
            productionRepository.findInstanceDetailById(id)
                ?: throw NotFoundException("product_instance", id)
        })
    }



    // --- Реализация ---

    private fun transition(
        id: Long,
        request: ProductionOrderActionRequest,
        currentUser: CurrentUser,
        action: String,
        fromStatuses: List<String>,
        newStatus: String
    ): ProductionOrderDto {
        currentUser.requirePermission("production", "manage")
        return dsl.transactionResult(TransactionalCallable {
            val updated = productionRepository.updateOrderStatus(
                id = id,
                expectedVersion = request.version,
                fromStatuses = fromStatuses,
                newStatus = newStatus
            ) ?: throw ConflictException(
                "Production order cannot be moved to $newStatus from its current status"
            )
            auditService.log(
                currentUser.userId, action, "PRODUCTION_ORDER", id,
                buildMap {
                    put("number", updated.number)
                    request.note?.takeIf { it.isNotBlank() }?.let { put("note", it.trim()) }
                }
            )
            loadDto(updated)
        })
    }

    private fun stageTransition(
        orderId: Long,
        stageId: Long,
        request: ProductionStageActionRequest,
        currentUser: CurrentUser,
        fromStatuses: List<String>,
        newStatus: String,
        actualStartAt: OffsetDateTime? = null,
        actualEndAt: OffsetDateTime? = null,
        skipReason: String? = null
    ): ProductionOrderDto {
        currentUser.requirePermission("production", "manage")
        return dsl.transactionResult(TransactionalCallable {
            val order = productionRepository.lockOrderById(orderId)
                ?: throw NotFoundException("production_order", orderId)
            if (order.status !in setOf("IN_PROGRESS", "ON_HOLD")) {
                throw ConflictException("Stages can be changed only while the order is in progress")
            }
            val stage = productionRepository.findStageById(stageId)
                ?: throw NotFoundException("production_stage", stageId)
            if (stage.orderId != orderId) {
                throw ValidationException("Stage belongs to another order", mapOf("stageId" to "mismatch"))
            }
            val updated = productionRepository.updateStageStatus(
                id = stageId,
                expectedVersion = request.version,
                fromStatuses = fromStatuses,
                newStatus = newStatus,
                actualStartAt = actualStartAt,
                actualEndAt = actualEndAt,
                skipReason = skipReason
            ) ?: throw ConflictException("Stage cannot be moved to $newStatus from its current status")
            auditService.log(
                currentUser.userId, newStatus, "PRODUCTION_STAGE", stageId,
                mapOf("orderId" to orderId, "stageType" to updated.stageType)
            )
            loadDto(order)
        })
    }

    private fun insertStages(
        orderId: Long,
        stages: List<CreateProductionStageInput>,
        currentUser: CurrentUser
    ) {
        stages.forEach { stage ->
            if (stage.name.isBlank()) {
                throw ValidationException("Stage name is required", mapOf("name" to "required"))
            }
            stage.responsibleId?.let { responsible ->
                if (!productionRepository.userExists(responsible)) {
                    throw NotFoundException("user", responsible)
                }
            }
            productionRepository.insertStage(
                orderId = orderId,
                stageType = stage.stageType.name,
                name = stage.name.trim(),
                sequence = stage.sequence,
                responsibleId = stage.responsibleId,
                plannedStartAt = parseDateTime(stage.plannedStartAt, "plannedStartAt"),
                plannedEndAt = parseDateTime(stage.plannedEndAt, "plannedEndAt"),
                createdBy = currentUser.userId
            )
        }
    }

    private fun loadDto(order: ProductionOrdersRecord, withStages: Boolean = true): ProductionOrderDto {
        val stages = if (!withStages) emptyList() else productionRepository.listStages(order.id)
        val assignees = if (stages.isEmpty()) {
            emptyMap()
        } else {
            productionRepository.listAssignees(stages.map { it.id }).groupBy { it.stageId }
        }
        return order.toDto(stages, assignees)
    }

    private fun parseDateTime(value: String?, field: String): OffsetDateTime? {
        if (value.isNullOrBlank()) return null
        return try {
            OffsetDateTime.parse(value.trim())
        } catch (_: DateTimeParseException) {
            throw ValidationException(
                "Invalid ISO-8601 date-time: $value",
                mapOf(field to "invalid")
            )
        }
    }
}

private fun ProductionOrderStagesRecord.toDto(
    assignees: List<ProductionStageAssigneeDto>
): ProductionOrderStageDto = ProductionOrderStageDto(
    id = this.id,
    orderId = this.orderId,
    stageType = ProductionStageType.valueOf(this.stageType),
    name = this.name,
    sequence = this.sequence,
    status = ProductionStageStatus.valueOf(this.status),
    plannedStartAt = this.plannedStartAt?.toString(),
    plannedEndAt = this.plannedEndAt?.toString(),
    actualStartAt = this.actualStartAt?.toString(),
    actualEndAt = this.actualEndAt?.toString(),
    responsibleId = this.responsibleId,
    requiredMaterialsOk = this.requiredMaterialsOk,
    requiredDocumentsOk = this.requiredDocumentsOk,
    skipReason = this.skipReason,
    notes = this.notes,
    version = this.version ?: 0,
    assignees = assignees
)

private fun ProductionOrdersRecord.toDto(
    stages: List<ProductionOrderStagesRecord>,
    assignees: Map<Long, List<org.example.project.generated.jooq.tables.records.ProductionStageAssigneesRecord>>
): ProductionOrderDto = ProductionOrderDto(
    id = this.id,
    number = this.number,
    projectId = this.projectId,
    productModelId = this.productModelId,
    bomRevisionId = this.bomRevisionId,
    status = ProductionOrderStatus.valueOf(this.status),
    plannedQuantity = this.plannedQuantity,
    actualQuantity = this.actualQuantity,
    plannedStartAt = this.plannedStartAt?.toString(),
    plannedEndAt = this.plannedEndAt?.toString(),
    actualStartAt = this.actualStartAt?.toString(),
    actualEndAt = this.actualEndAt?.toString(),
    responsibleId = this.responsibleId,
    departmentId = this.departmentId,
    priority = this.priority,
    createdAt = this.createdAt?.toString(),
    createdBy = this.createdBy,
    version = this.version ?: 0,
    stages = stages.map { stage ->
        stage.toDto(
            assignees[stage.id].orEmpty().map { row ->
                ProductionStageAssigneeDto(
                    userId = row.userId,
                    role = ProductionStageAssigneeRole.valueOf(row.role)
                )
            }
        )
    }
)

private fun ProductInstancesRecord.toDto(): ProductInstanceDto = ProductInstanceDto(
    id = this.id,
    orderId = this.orderId,
    projectId = this.projectId,
    productModelId = this.productModelId,
    bomRevisionId = this.bomRevisionId,
    serialNumber = this.serialNumber,
    status = this.status,
    manufacturedAt = this.manufacturedAt?.toString()
)
