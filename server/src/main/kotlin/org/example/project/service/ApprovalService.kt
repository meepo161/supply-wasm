package org.example.project.service

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.example.project.dto.*
import org.example.project.exception.ConflictException
import org.example.project.exception.ForbiddenException
import org.example.project.exception.NotFoundException
import org.example.project.exception.ValidationException
import org.example.project.model.CurrentUser
import org.example.project.repository.ApprovalRepository
import org.example.project.repository.RoleRepository
import org.example.project.repository.UserRepository
import org.jooq.DSLContext
import org.jooq.TransactionalCallable
import org.jooq.exception.DataAccessException
import java.security.MessageDigest

class ApprovalService(
    private val approvalRepository: ApprovalRepository,
    private val ports: List<ApprovalTargetPort>,
    private val userRepository: UserRepository,
    private val roleRepository: RoleRepository,
    private val auditService: AuditService,
    private val dsl: DSLContext
) {

    fun createTemplate(request: CreateApprovalTemplateRequest, currentUser: CurrentUser): ApprovalTemplateDto {
        currentUser.requirePermission("approvals", "manage_templates")
        validateTemplate(request)
        val targetType = normalizeTargetType(request.targetType)
        port(targetType)

        return dsl.transactionResult(TransactionalCallable {
            val templateId = approvalRepository.insertTemplate(
                code = request.code.trim(),
                name = request.name.trim(),
                targetType = targetType,
                createdBy = currentUser.userId
            )
            request.stages.sortedBy { it.stageNo }.forEach { stage ->
                val stageId = approvalRepository.insertTemplateStage(
                    templateId = templateId,
                    stageNo = stage.stageNo,
                    name = stage.name.trim(),
                    decisionPolicy = stage.decisionPolicy!!.name
                )
                stage.assignees.forEach { assignee ->
                    approvalRepository.insertTemplateAssignee(
                        stageId = stageId,
                        assigneeType = assignee.type.name,
                        userId = assignee.userId,
                        roleId = assignee.roleId
                    )
                }
            }
            auditService.log(
                currentUser.userId, "CREATE", "APPROVAL_TEMPLATE", templateId,
                mapOf("code" to request.code.trim(), "targetType" to targetType)
            )
            toTemplateDto(approvalRepository.findTemplateById(templateId)!!)
        })
    }

    fun listTemplates(targetType: String?, currentUser: CurrentUser): List<ApprovalTemplateDto> {
        requireTemplateRead(currentUser)
        return approvalRepository.listTemplates(targetType?.let { normalizeTargetType(it) })
            .map { toTemplateDto(it) }
    }

    fun getTemplate(id: Long, currentUser: CurrentUser): ApprovalTemplateDto {
        requireTemplateRead(currentUser)
        val template = approvalRepository.findTemplateById(id)
            ?: throw NotFoundException("approval_template", id)
        return toTemplateDto(template)
    }

    fun startRoute(request: StartApprovalRouteRequest, currentUser: CurrentUser): ApprovalRouteDto {
        currentUser.requirePermission("approvals", "start")
        val targetType = normalizeTargetType(request.targetType)
        val targetPort = port(targetType)
        val template = approvalRepository.findTemplateById(request.templateId)
            ?: throw NotFoundException("approval_template", request.templateId)
        if (!template.active) {
            throw ValidationException("Approval template is not active", mapOf("templateId" to "inactive"))
        }
        if (template.targetType != targetType) {
            throw ValidationException(
                "Template target type does not match the request target type",
                mapOf("targetType" to "mismatch")
            )
        }
        val templateStages = approvalRepository.listTemplateStages(template.id)
        if (templateStages.isEmpty()) {
            throw ValidationException("Approval template has no stages", mapOf("templateId" to "empty"))
        }
        val templateAssignees = approvalRepository.listTemplateAssignees(template.id).groupBy { it.stageId }

        val targetVersion = targetPort.ensureStartAllowed(request.targetId, currentUser)

        val idempotencyKey = request.idempotencyKey?.trim()?.takeIf { it.isNotEmpty() }
        val requestHash = idempotencyKey?.let { fingerprint(request.copy(idempotencyKey = it)) }
        if (idempotencyKey != null) {
            val existing = approvalRepository.findRouteByIdempotencyKey(idempotencyKey, currentUser.userId)
            if (existing != null) {
                if (existing.idempotencyRequestHash != requestHash) {
                    throw ConflictException("Idempotency key was already used for another request")
                }
                return getRoute(existing.id, currentUser)
            }
        }
        if (approvalRepository.hasActiveRoute(targetType, request.targetId)) {
            throw ConflictException("Target already has an active approval route")
        }

        val firstStageNo = templateStages.first().stageNo
        return try {
            dsl.transactionResult(TransactionalCallable {
                val routeId = approvalRepository.insertRoute(
                    templateId = template.id,
                    targetType = targetType,
                    targetId = request.targetId,
                    targetVersion = targetVersion,
                    currentStageNo = firstStageNo,
                    idempotencyKey = idempotencyKey,
                    idempotencyRequestHash = requestHash,
                    startedBy = currentUser.userId
                )
                templateStages.forEach { stage ->
                    val stageId = approvalRepository.insertRouteStage(
                        routeId = routeId,
                        stageNo = stage.stageNo,
                        name = stage.name,
                        decisionPolicy = stage.decisionPolicy,
                        status = if (stage.stageNo == firstStageNo) "ACTIVE" else "PENDING"
                    )
                    templateAssignees[stage.id].orEmpty().forEach { assignee ->
                        approvalRepository.insertRouteAssignee(
                            routeStageId = stageId,
                            assigneeType = assignee.assigneeType,
                            userId = assignee.userId,
                            roleId = assignee.roleId,
                            createdBy = currentUser.userId
                        )
                    }
                }
                auditService.log(
                    currentUser.userId, "START", "APPROVAL_ROUTE", routeId,
                    mapOf(
                        "templateId" to template.id,
                        "targetType" to targetType,
                        "targetId" to request.targetId,
                        "targetVersion" to targetVersion
                    )
                )
                toRouteDto(approvalRepository.findRouteById(routeId)!!)
            })
        } catch (e: DataAccessException) {
            if (e.sqlState() != "23505") throw e
            if (idempotencyKey != null) {
                val existing = approvalRepository.findRouteByIdempotencyKey(idempotencyKey, currentUser.userId)
                if (existing != null) {
                    if (existing.idempotencyRequestHash != requestHash) {
                        throw ConflictException("Idempotency key was already used for another request")
                    }
                    return getRoute(existing.id, currentUser)
                }
            }
            throw ConflictException("Target already has an active approval route")
        }
    }

    fun getRoute(id: Long, currentUser: CurrentUser): ApprovalRouteDto {
        val route = approvalRepository.findRouteById(id)
            ?: throw NotFoundException("approval_route", id)
        port(route.targetType).ensureReadAllowed(route.targetId, currentUser)
        return toRouteDto(route)
    }

    fun listRoutesForTarget(targetType: String, targetId: Long, currentUser: CurrentUser): List<ApprovalRouteDto> {
        val normalized = normalizeTargetType(targetType)
        port(normalized).ensureReadAllowed(targetId, currentUser)
        return approvalRepository.listRoutesByTarget(normalized, targetId).map { toRouteDto(it) }
    }

    fun decide(routeId: Long, request: SubmitApprovalDecisionRequest, currentUser: CurrentUser): ApprovalRouteDto {
        if (request.decision != ApprovalDecision.APPROVE && request.comment.isNullOrBlank()) {
            throw ValidationException(
                "Comment is required for REJECT or RETURN decision",
                mapOf("comment" to "required")
            )
        }

        return dsl.transactionResult(TransactionalCallable {
            val route = approvalRepository.lockRouteById(routeId)
                ?: throw NotFoundException("approval_route", routeId)
            if (route.status != "ACTIVE") {
                throw ConflictException("Approval route is not active")
            }
            val targetPort = port(route.targetType)
            if (targetPort.currentTargetVersion(route.targetId) != route.targetVersion) {
                throw ConflictException(
                    "Target version changed since the route started; restart the approval"
                )
            }
            val stage = approvalRepository.findRouteStage(route.id, route.currentStageNo)
                ?: throw ConflictException("Current stage not found")
            if (stage.status != "ACTIVE") {
                throw ConflictException("Current stage is not active")
            }
            if (approvalRepository.existsDecisionByUser(stage.id, currentUser.userId)) {
                throw ConflictException("Current user already decided on this stage")
            }
            val assignee = findAssignableRecord(stage.id, currentUser.userId)
                ?: throw ForbiddenException("Current user is not an approver of the current stage")

            try {
                approvalRepository.insertDecision(
                    routeId = route.id,
                    routeStageId = stage.id,
                    assigneeId = assignee.id,
                    decidedBy = currentUser.userId,
                    decision = request.decision.name,
                    comment = request.comment?.trim()
                )
            } catch (e: DataAccessException) {
                if (e.sqlState() != "23505") throw e
                throw ConflictException("Current user already decided on this stage")
            }
            auditService.log(
                currentUser.userId, "DECIDE", "APPROVAL_ROUTE", route.id,
                mapOf(
                    "stageNo" to stage.stageNo,
                    "decision" to request.decision.name,
                    "comment" to request.comment?.trim()
                )
            )

            when (request.decision) {
                ApprovalDecision.APPROVE -> {
                    val stageComplete = stage.decisionPolicy == "ANY" ||
                        approvalRepository.countStageAssigneesWithoutApproval(stage.id) == 0L
                    if (stageComplete) {
                        completeStageAndAdvance(route, stage, currentUser.userId)
                    }
                }
                ApprovalDecision.REJECT -> terminateRoute(route, stage, "REJECTED", currentUser.userId)
                ApprovalDecision.RETURN -> terminateRoute(route, stage, "RETURNED", currentUser.userId)
            }
            toRouteDto(approvalRepository.findRouteById(route.id)!!)
        })
    }

    fun cancelRoute(routeId: Long, request: CancelApprovalRouteRequest, currentUser: CurrentUser): ApprovalRouteDto {
        currentUser.requirePermission("approvals", "cancel")
        val reason = request.reason.trim()
        if (reason.isEmpty()) {
            throw ValidationException("Cancellation reason is required", mapOf("reason" to "required"))
        }
        return dsl.transactionResult(TransactionalCallable {
            val route = approvalRepository.lockRouteById(routeId)
                ?: throw NotFoundException("approval_route", routeId)
            if (route.status != "ACTIVE") {
                throw ConflictException("Approval route is not active")
            }
            approvalRepository.cancelOpenStages(route.id)
            if (!approvalRepository.updateRouteState(route.id, route.version, "CANCELLED", route.currentStageNo, completed = true)) {
                throw ConflictException("Approval route was modified concurrently")
            }
            auditService.log(
                currentUser.userId, "CANCEL", "APPROVAL_ROUTE", route.id,
                mapOf("reason" to reason)
            )
            toRouteDto(approvalRepository.findRouteById(route.id)!!)
        })
    }

    fun addAssignee(
        routeId: Long,
        stageId: Long,
        request: AddApprovalAssigneeRequest,
        currentUser: CurrentUser
    ): ApprovalRouteDto {
        currentUser.requirePermission("approvals", "assign")
        validateAssignee(request.type, request.userId, request.roleId)
        return dsl.transactionResult(TransactionalCallable {
            val route = approvalRepository.lockRouteById(routeId)
                ?: throw NotFoundException("approval_route", routeId)
            if (route.status != "ACTIVE") {
                throw ConflictException("Approval route is not active")
            }
            val stage = approvalRepository.findRouteStageById(stageId)
                ?: throw NotFoundException("approval_route_stage", stageId)
            if (stage.routeId != route.id) {
                throw ValidationException("Stage does not belong to the route", mapOf("stageId" to "mismatch"))
            }
            if (stage.status != "PENDING" && stage.status != "ACTIVE") {
                throw ConflictException("Assignees of a finished stage cannot be changed")
            }
            approvalRepository.insertRouteAssignee(
                routeStageId = stage.id,
                assigneeType = request.type.name,
                userId = request.userId,
                roleId = request.roleId,
                createdBy = currentUser.userId
            )
            auditService.log(
                currentUser.userId, "ASSIGNEE_ADD", "APPROVAL_ROUTE", route.id,
                mapOf(
                    "stageNo" to stage.stageNo,
                    "assigneeType" to request.type.name,
                    "userId" to request.userId,
                    "roleId" to request.roleId
                )
            )
            toRouteDto(approvalRepository.findRouteById(route.id)!!)
        })
    }

    fun removeAssignee(
        routeId: Long,
        stageId: Long,
        assigneeId: Long,
        currentUser: CurrentUser
    ): ApprovalRouteDto {
        currentUser.requirePermission("approvals", "assign")
        return dsl.transactionResult(TransactionalCallable {
            val route = approvalRepository.lockRouteById(routeId)
                ?: throw NotFoundException("approval_route", routeId)
            if (route.status != "ACTIVE") {
                throw ConflictException("Approval route is not active")
            }
            val stage = approvalRepository.findRouteStageById(stageId)
                ?: throw NotFoundException("approval_route_stage", stageId)
            if (stage.routeId != route.id) {
                throw ValidationException("Stage does not belong to the route", mapOf("stageId" to "mismatch"))
            }
            if (stage.status != "PENDING" && stage.status != "ACTIVE") {
                throw ConflictException("Assignees of a finished stage cannot be changed")
            }
            val assignee = approvalRepository.findRouteAssignee(assigneeId)
                ?: throw NotFoundException("approval_route_assignee", assigneeId)
            if (assignee.stageId != stage.id) {
                throw ValidationException("Assignee does not belong to the stage", mapOf("assigneeId" to "mismatch"))
            }
            if (approvalRepository.existsDecisionForAssignee(assigneeId)) {
                throw ConflictException("Assignee already has a decision on this stage")
            }
            if (approvalRepository.listStageAssignees(stage.id).size <= 1) {
                throw ValidationException(
                    "Stage must retain at least one assignee",
                    mapOf("stageId" to "last_assignee")
                )
            }
            approvalRepository.deleteRouteAssignee(assigneeId)
            auditService.log(
                currentUser.userId, "ASSIGNEE_REMOVE", "APPROVAL_ROUTE", route.id,
                mapOf("stageNo" to stage.stageNo, "assigneeId" to assigneeId)
            )
            if (stage.status == "ACTIVE" && stage.decisionPolicy == "ALL" &&
                approvalRepository.countStageAssigneesWithoutApproval(stage.id) == 0L
            ) {
                completeStageAndAdvance(route, stage, currentUser.userId)
            }
            toRouteDto(approvalRepository.findRouteById(route.id)!!)
        })
    }

    private fun completeStageAndAdvance(
        route: ApprovalRepository.RouteRow,
        stage: ApprovalRepository.RouteStageRow,
        actingUserId: Long
    ) {
        if (!approvalRepository.completeRouteStage(stage.id, "APPROVED")) {
            throw ConflictException("Stage was modified concurrently")
        }
        val next = approvalRepository.listRouteStages(route.id)
            .filter { it.stageNo > stage.stageNo }
            .minByOrNull { it.stageNo }
        if (next != null) {
            if (!approvalRepository.activateRouteStage(next.id)) {
                throw ConflictException("Stage was modified concurrently")
            }
            if (!approvalRepository.updateRouteState(route.id, route.version, "ACTIVE", next.stageNo, completed = false)) {
                throw ConflictException("Approval route was modified concurrently")
            }
        } else {
            if (!approvalRepository.updateRouteState(route.id, route.version, "COMPLETED", stage.stageNo, completed = true)) {
                throw ConflictException("Approval route was modified concurrently")
            }
            auditService.log(
                actingUserId, "COMPLETE", "APPROVAL_ROUTE", route.id,
                mapOf("targetType" to route.targetType, "targetId" to route.targetId)
            )
            port(route.targetType).completeApproval(route.targetId, route.targetVersion, actingUserId)
        }
    }

    private fun terminateRoute(
        route: ApprovalRepository.RouteRow,
        stage: ApprovalRepository.RouteStageRow,
        status: String,
        actingUserId: Long
    ) {
        if (!approvalRepository.completeRouteStage(stage.id, status)) {
            throw ConflictException("Stage was modified concurrently")
        }
        if (!approvalRepository.updateRouteState(route.id, route.version, status, route.currentStageNo, completed = true)) {
            throw ConflictException("Approval route was modified concurrently")
        }
        auditService.log(
            actingUserId, if (status == "REJECTED") "REJECT" else "RETURN",
            "APPROVAL_ROUTE", route.id,
            mapOf("stageNo" to stage.stageNo)
        )
    }

    private fun findAssignableRecord(stageId: Long, userId: Long): ApprovalRepository.AssigneeRow? {
        val assignees = approvalRepository.listStageAssignees(stageId)
        assignees.firstOrNull { it.assigneeType == "USER" && it.userId == userId }?.let { return it }
        return assignees.firstOrNull { assignee ->
            assignee.assigneeType == "ROLE" &&
                assignee.roleId != null &&
                approvalRepository.userHasRole(userId, assignee.roleId) &&
                !approvalRepository.existsDecisionForAssignee(assignee.id)
        }
    }

    private fun port(targetType: String): ApprovalTargetPort {
        return ports.firstOrNull { it.targetType == targetType }
            ?: throw ValidationException(
                "Approval target type is not supported yet: $targetType",
                mapOf("targetType" to "no_adapter")
            )
    }

    private fun normalizeTargetType(targetType: String): String {
        val normalized = targetType.trim().uppercase()
        if (normalized.isEmpty()) {
            throw ValidationException("Target type is required", mapOf("targetType" to "required"))
        }
        return normalized
    }

    private fun requireTemplateRead(currentUser: CurrentUser) {
        if (!currentUser.hasPermission("approvals", "manage_templates") &&
            !currentUser.hasPermission("approvals", "start")
        ) {
            throw ForbiddenException("Missing permission approvals:manage_templates or approvals:start")
        }
    }

    private fun validateTemplate(request: CreateApprovalTemplateRequest) {
        if (request.code.isBlank()) {
            throw ValidationException("Template code is required", mapOf("code" to "required"))
        }
        if (request.name.isBlank()) {
            throw ValidationException("Template name is required", mapOf("name" to "required"))
        }
        if (request.stages.isEmpty()) {
            throw ValidationException("Template must have at least one stage", mapOf("stages" to "empty"))
        }
        val stageNos = mutableSetOf<Int>()
        request.stages.forEachIndexed { index, stage ->
            if (stage.stageNo <= 0) {
                throw ValidationException("Stage number must be positive", mapOf("stages[$index].stageNo" to "not_positive"))
            }
            if (!stageNos.add(stage.stageNo)) {
                throw ValidationException("Duplicate stage number: ${stage.stageNo}", mapOf("stages[$index].stageNo" to "duplicate"))
            }
            if (stage.name.isBlank()) {
                throw ValidationException("Stage name is required", mapOf("stages[$index].name" to "required"))
            }
            if (stage.decisionPolicy == null) {
                throw ValidationException(
                    "Decision policy is required explicitly (ALL or ANY)",
                    mapOf("stages[$index].decisionPolicy" to "required")
                )
            }
            if (stage.assignees.isEmpty()) {
                throw ValidationException("Stage must have at least one assignee", mapOf("stages[$index].assignees" to "empty"))
            }
            stage.assignees.forEach { assignee ->
                validateAssignee(assignee.type, assignee.userId, assignee.roleId)
            }
        }
    }

    private fun validateAssignee(type: ApprovalAssigneeType, userId: Long?, roleId: Long?) {
        when (type) {
            ApprovalAssigneeType.USER -> {
                if (userId == null || roleId != null) {
                    throw ValidationException("USER assignee requires userId and no roleId", mapOf("userId" to "required"))
                }
                val user = userRepository.findById(userId) ?: throw NotFoundException("user", userId)
                if (user.active != true) {
                    throw ValidationException("Assignee user must be active", mapOf("userId" to "inactive"))
                }
            }
            ApprovalAssigneeType.ROLE -> {
                if (roleId == null || userId != null) {
                    throw ValidationException("ROLE assignee requires roleId and no userId", mapOf("roleId" to "required"))
                }
                roleRepository.findById(roleId) ?: throw NotFoundException("role", roleId)
            }
        }
    }

    private fun toTemplateDto(template: ApprovalRepository.TemplateRow): ApprovalTemplateDto {
        val stages = approvalRepository.listTemplateStages(template.id)
        val assignees = approvalRepository.listTemplateAssignees(template.id).groupBy { it.stageId }
        return ApprovalTemplateDto(
            id = template.id,
            code = template.code,
            name = template.name,
            targetType = template.targetType,
            active = template.active,
            stages = stages.map { stage ->
                ApprovalTemplateStageDto(
                    id = stage.id,
                    stageNo = stage.stageNo,
                    name = stage.name,
                    decisionPolicy = ApprovalDecisionPolicy.valueOf(stage.decisionPolicy),
                    assignees = assignees[stage.id].orEmpty().map { it.toDto() }
                )
            },
            createdAt = template.createdAt?.toString(),
            createdBy = template.createdBy,
            version = template.version
        )
    }

    private fun toRouteDto(route: ApprovalRepository.RouteRow): ApprovalRouteDto {
        val stages = approvalRepository.listRouteStages(route.id)
        val assignees = approvalRepository.listRouteAssignees(route.id).groupBy { it.stageId }
        val decisions = approvalRepository.listDecisions(route.id)
        val stageNoById = stages.associate { it.id to it.stageNo }
        return ApprovalRouteDto(
            id = route.id,
            templateId = route.templateId,
            targetType = route.targetType,
            targetId = route.targetId,
            targetVersion = route.targetVersion,
            status = ApprovalRouteStatus.valueOf(route.status),
            currentStageNo = route.currentStageNo,
            stages = stages.map { stage ->
                ApprovalStageDto(
                    id = stage.id,
                    stageNo = stage.stageNo,
                    name = stage.name,
                    decisionPolicy = ApprovalDecisionPolicy.valueOf(stage.decisionPolicy),
                    status = ApprovalStageStatus.valueOf(stage.status),
                    assignees = assignees[stage.id].orEmpty().map { it.toDto() },
                    startedAt = stage.startedAt?.toString(),
                    completedAt = stage.completedAt?.toString()
                )
            },
            decisions = decisions.map { decision ->
                ApprovalDecisionDto(
                    id = decision.id,
                    stageId = decision.routeStageId,
                    stageNo = stageNoById[decision.routeStageId] ?: 0,
                    decidedBy = decision.decidedBy,
                    decidedByFullName = decision.decidedByFullName,
                    decision = ApprovalDecision.valueOf(decision.decision),
                    comment = decision.comment,
                    createdAt = decision.createdAt?.toString()
                )
            },
            startedBy = route.startedBy,
            startedAt = route.startedAt?.toString(),
            completedAt = route.completedAt?.toString(),
            version = route.version
        )
    }

    private fun ApprovalRepository.AssigneeRow.toDto(): ApprovalAssigneeDto = ApprovalAssigneeDto(
        id = this.id,
        type = ApprovalAssigneeType.valueOf(this.assigneeType),
        userId = this.userId,
        userFullName = this.userFullName,
        roleId = this.roleId,
        roleName = this.roleName
    )

    private inline fun <reified T> fingerprint(value: T): String {
        val bytes = Json { encodeDefaults = true }.encodeToString(value).encodeToByteArray()
        return MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }
}
