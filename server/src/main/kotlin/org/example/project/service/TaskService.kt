package org.example.project.service

import org.example.project.dto.*
import org.example.project.exception.ConflictException
import org.example.project.exception.ForbiddenException
import org.example.project.exception.NotFoundException
import org.example.project.exception.ValidationException
import org.example.project.generated.jooq.tables.records.TasksRecord
import org.example.project.model.CurrentUser
import org.example.project.repository.DepartmentRepository
import org.example.project.repository.ProjectRepository
import org.example.project.repository.TaskRepository
import org.example.project.repository.UserRepository
import org.jooq.DSLContext
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

class TaskService(
    private val taskRepository: TaskRepository,
    private val projectRepository: ProjectRepository,
    private val userRepository: UserRepository,
    private val departmentRepository: DepartmentRepository,
    private val auditService: AuditService,
    private val dsl: DSLContext
) {

    fun create(request: CreateTaskRequest, currentUser: CurrentUser): TaskDto {
        currentUser.requirePermission("tasks", "create")
        validateCreate(request, currentUser)

        return dsl.transactionResult(org.jooq.TransactionalCallable {
            if (request.area == TaskArea.PROJECT) {
                projectRepository.requireOpenForRelatedWork(request.projectId!!)
            }
            val number = taskRepository.generateNumber()
            val assigneeId = request.assigneeId
                ?: if (request.area == TaskArea.PERSONAL) currentUser.userId else null
            val status = if (assigneeId != null) "ASSIGNED" else "NEW"

            val task = taskRepository.create(
                number = number,
                area = request.area.name,
                status = status,
                type = request.type.name,
                title = request.title.trim(),
                description = request.description?.trim(),
                acceptanceCriteria = request.acceptanceCriteria?.trim(),
                initiatorId = currentUser.userId,
                assigneeId = assigneeId,
                reviewerId = request.reviewerId,
                projectId = request.projectId,
                departmentId = request.departmentId,
                dueDate = parseDateTime(request.dueDate),
                plannedStartAt = parseDateTime(request.plannedStartAt),
                priority = (request.priority ?: TaskPriority.NORMAL).name,
                createdBy = currentUser.userId
            )

            taskRepository.addParticipant(task.id, currentUser.userId, TaskParticipantRole.INITIATOR.name)
            assigneeId?.let { taskRepository.addParticipant(task.id, it, TaskParticipantRole.ASSIGNEE.name) }
            request.reviewerId?.let { taskRepository.addParticipant(task.id, it, TaskParticipantRole.REVIEWER.name) }
            request.coexecutorIds.forEach { taskRepository.addParticipant(task.id, it, TaskParticipantRole.COEXECUTOR.name) }
            request.observerIds.forEach { taskRepository.addParticipant(task.id, it, TaskParticipantRole.OBSERVER.name) }

            taskRepository.addStatusHistory(task.id, status, currentUser.userId, "CREATED")

            auditService.log(
                currentUser.userId,
                "CREATE",
                "TASK",
                task.id,
                mapOf(
                    "number" to task.number,
                    "area" to task.area,
                    "type" to task.type,
                    "title" to task.title,
                    "assigneeId" to assigneeId
                )
            )
            task.toDto(loadParticipants(task.id))
        })
    }

    fun get(id: Long, currentUser: CurrentUser): TaskDto {
        val task = taskRepository.findById(id) ?: throw NotFoundException("task", id)
        if (!canRead(task, currentUser)) {
            throw ForbiddenException("Access to task denied")
        }
        return task.toDto(loadParticipants(id))
    }

    fun list(currentUser: CurrentUser): List<TaskDto> {
        val user = userRepository.findById(currentUser.userId)
        val records = if (currentUser.hasPermission("tasks", "read_any")) {
            taskRepository.listAll()
        } else {
            taskRepository.listVisible(currentUser.userId, user?.departmentId)
        }
        return records.map { it.toDto(loadParticipants(it.id)) }
    }

    fun assign(id: Long, request: AssignTaskRequest, currentUser: CurrentUser): TaskDto {
        val task = taskRepository.findById(id) ?: throw NotFoundException("task", id)
        if (!canManage(task, currentUser)) {
            throw ForbiddenException("Only initiator or authorized user can assign the task")
        }
        validateAssign(task, request.assigneeId, currentUser)

        return dsl.transactionResult(org.jooq.TransactionalCallable {
            val updated = taskRepository.assign(id, request.assigneeId, request.version)
                ?: throw ConflictException("Task was modified concurrently or invalid status")
            taskRepository.removeParticipantsByRole(id, TaskParticipantRole.ASSIGNEE.name)
            taskRepository.addParticipant(id, request.assigneeId, TaskParticipantRole.ASSIGNEE.name)
            taskRepository.addStatusHistory(id, "ASSIGNED", currentUser.userId, "ASSIGNED")
            auditService.log(
                currentUser.userId,
                "ASSIGN",
                "TASK",
                id,
                mapOf("assigneeId" to request.assigneeId)
            )
            updated.toDto(loadParticipants(id))
        })
    }

    fun accept(id: Long, request: TaskCommandRequest, currentUser: CurrentUser): TaskDto {
        val task = taskRepository.findById(id) ?: throw NotFoundException("task", id)
        checkVersion(task, request.version)
        if (!isAssignee(task, currentUser) && !currentUser.hasPermission("tasks", "assign_any")) {
            throw ForbiddenException("Only assignee can accept the task")
        }
        if (task.status != "ASSIGNED") {
            throw ConflictException("Task can only be accepted from ASSIGNED status")
        }
        return dsl.transactionResult(org.jooq.TransactionalCallable {
            val updated = taskRepository.accept(id, request.version)
                ?: throw ConflictException("Task was modified concurrently or invalid status")
            taskRepository.addStatusHistory(id, "IN_PROGRESS", currentUser.userId, "ACCEPTED")
            auditService.log(currentUser.userId, "ACCEPT", "TASK", id, emptyMap())
            updated.toDto(loadParticipants(id))
        })
    }

    fun start(id: Long, request: TaskCommandRequest, currentUser: CurrentUser): TaskDto {
        val task = taskRepository.findById(id) ?: throw NotFoundException("task", id)
        if (!isAssignee(task, currentUser) && !currentUser.hasPermission("tasks", "assign_any")) {
            throw ForbiddenException("Only assignee can start the task")
        }
        if (task.dueDate == null) {
            throw ValidationException("Due date is required before starting work", mapOf("dueDate" to "required"))
        }

        if (task.status == "IN_PROGRESS") {
            checkVersion(task, request.version)
            return task.toDto(loadParticipants(id))
        }
        return dsl.transactionResult(org.jooq.TransactionalCallable {
            val updated = taskRepository.accept(id, request.version)
                ?: throw ConflictException("Task was modified concurrently or invalid status")
            taskRepository.addStatusHistory(id, "IN_PROGRESS", currentUser.userId, "STARTED")
            auditService.log(currentUser.userId, "START", "TASK", id, emptyMap())
            updated.toDto(loadParticipants(id))
        })
    }

    fun submitResult(id: Long, request: SubmitResultRequest, currentUser: CurrentUser): TaskDto {
        val task = taskRepository.findById(id) ?: throw NotFoundException("task", id)
        if (!isAssignee(task, currentUser) && !currentUser.hasPermission("tasks", "assign_any")) {
            throw ForbiddenException("Only assignee can submit result")
        }
        if (request.result.isBlank()) {
            throw ValidationException("Result is required", mapOf("result" to "required"))
        }
        return dsl.transactionResult(org.jooq.TransactionalCallable {
            val updated = taskRepository.submitResult(id, request.result.trim(), request.version)
                ?: throw ConflictException("Task was modified concurrently or invalid status")
            taskRepository.addStatusHistory(id, updated.status!!, currentUser.userId, "RESULT_SUBMITTED")
            auditService.log(currentUser.userId, "SUBMIT_RESULT", "TASK", id, emptyMap())
            updated.toDto(loadParticipants(id))
        })
    }

    fun sendToReview(id: Long, request: TaskCommandRequest, currentUser: CurrentUser): TaskDto {
        val task = taskRepository.findById(id) ?: throw NotFoundException("task", id)
        if (!isAssignee(task, currentUser) && !currentUser.hasPermission("tasks", "assign_any")) {
            throw ForbiddenException("Only assignee can send task to review")
        }
        if (task.type != "REVIEWED") {
            throw ConflictException("Only reviewed tasks can be sent to review")
        }
        if (task.result.isNullOrBlank()) {
            throw ValidationException("Result is required before review", mapOf("result" to "required"))
        }
        if (task.reviewerId == null) {
            throw ValidationException("Reviewer is required for reviewed task", mapOf("reviewerId" to "required"))
        }
        if (task.acceptanceCriteria.isNullOrBlank()) {
            throw ValidationException("Acceptance criteria are required", mapOf("acceptanceCriteria" to "required"))
        }
        return dsl.transactionResult(org.jooq.TransactionalCallable {
            val updated = taskRepository.sendToReview(id, request.version)
                ?: throw ConflictException("Task was modified concurrently or invalid status")
            taskRepository.addStatusHistory(id, "ON_REVIEW", currentUser.userId, "SENT_TO_REVIEW")
            auditService.log(currentUser.userId, "SEND_TO_REVIEW", "TASK", id, emptyMap())
            updated.toDto(loadParticipants(id))
        })
    }

    fun review(id: Long, request: ReviewTaskRequest, currentUser: CurrentUser): TaskDto {
        val task = taskRepository.findById(id) ?: throw NotFoundException("task", id)
        if (!isReviewer(task, currentUser) && !currentUser.hasPermission("tasks", "review_any")) {
            throw ForbiddenException("Only reviewer can make review decision")
        }
        if (task.status != "ON_REVIEW") {
            throw ConflictException("Task can only be reviewed from ON_REVIEW status")
        }
        if (request.decision == ReviewDecision.RETURN && request.reason.isNullOrBlank()) {
            throw ValidationException("Return reason is required", mapOf("reason" to "required"))
        }

        return dsl.transactionResult(org.jooq.TransactionalCallable {
            val updated = taskRepository.reviewDecision(id, request.decision.name, request.reason?.trim(), request.version)
                ?: throw ConflictException("Task was modified concurrently or invalid status")
            val newStatus = updated.status!!
            taskRepository.addStatusHistory(id, newStatus, currentUser.userId, request.decision.name)
            auditService.log(
                currentUser.userId,
                "REVIEW_${request.decision.name}",
                "TASK",
                id,
                mapOf("reason" to request.reason)
            )
            updated.toDto(loadParticipants(id))
        })
    }

    fun closeWithoutReview(id: Long, request: TaskCommandRequest, currentUser: CurrentUser): TaskDto {
        val task = taskRepository.findById(id) ?: throw NotFoundException("task", id)
        if (!isAssignee(task, currentUser) && !isInitiator(task, currentUser) && !currentUser.hasPermission("tasks", "review_any")) {
            throw ForbiddenException("Only assignee, initiator or authorized user can close the task")
        }
        if (task.type == "REVIEWED") {
            throw ConflictException("Reviewed tasks cannot be closed without acceptance")
        }
        return dsl.transactionResult(org.jooq.TransactionalCallable {
            val updated = taskRepository.closeWithoutReview(id, request.version)
                ?: throw ConflictException("Task was modified concurrently or invalid status")
            taskRepository.addStatusHistory(id, "CLOSED", currentUser.userId, "CLOSED_WITHOUT_REVIEW")
            auditService.log(currentUser.userId, "CLOSE", "TASK", id, emptyMap())
            updated.toDto(loadParticipants(id))
        })
    }

    fun block(id: Long, request: BlockTaskRequest, currentUser: CurrentUser): TaskDto {
        val task = taskRepository.findById(id) ?: throw NotFoundException("task", id)
        if (!canBlock(task, currentUser)) {
            throw ForbiddenException("Only initiator, assignee, reviewer or authorized user can block the task")
        }
        if (request.reason.isBlank()) {
            throw ValidationException("Block reason is required", mapOf("reason" to "required"))
        }
        val unblockResponsibleId = request.unblockResponsibleId
            ?: throw ValidationException("Unblock responsible is required", mapOf("unblockResponsibleId" to "required"))
        if (!userRepository.exists(unblockResponsibleId)) {
            throw NotFoundException("user", unblockResponsibleId)
        }
        return dsl.transactionResult(org.jooq.TransactionalCallable {
            val updated = taskRepository.block(id, request.reason.trim(), unblockResponsibleId, currentUser.userId, request.version)
                ?: throw ConflictException("Task was modified concurrently or invalid status")
            taskRepository.addStatusHistory(id, "BLOCKED", currentUser.userId, request.reason.trim())
            auditService.log(
                currentUser.userId,
                "BLOCK",
                "TASK",
                id,
                mapOf("reason" to request.reason.trim(), "unblockResponsibleId" to unblockResponsibleId)
            )
            updated.toDto(loadParticipants(id))
        })
    }

    fun unblock(id: Long, request: TaskCommandRequest, currentUser: CurrentUser): TaskDto {
        val task = taskRepository.findById(id) ?: throw NotFoundException("task", id)
        if (!canUnblock(task, currentUser)) {
            throw ForbiddenException("Only authorized user can unblock the task")
        }
        return dsl.transactionResult(org.jooq.TransactionalCallable {
            val restoredStatus = taskRepository.findStatusBeforeLastBlock(id) ?: "IN_PROGRESS"
            val updated = taskRepository.unblock(id, restoredStatus, request.version)
                ?: throw ConflictException("Task was modified concurrently or invalid status")
            taskRepository.addStatusHistory(id, restoredStatus, currentUser.userId, "UNBLOCKED")
            auditService.log(currentUser.userId, "UNBLOCK", "TASK", id, mapOf("restoredStatus" to restoredStatus))
            updated.toDto(loadParticipants(id))
        })
    }

    fun reopen(id: Long, request: ReopenTaskRequest, currentUser: CurrentUser): TaskDto {
        val task = taskRepository.findById(id) ?: throw NotFoundException("task", id)
        if (!isInitiator(task, currentUser) && !currentUser.hasPermission("tasks", "reopen_any")) {
            throw ForbiddenException("Only initiator or authorized user can reopen the task")
        }
        if (request.reason.isBlank()) {
            throw ValidationException("Reopen reason is required", mapOf("reason" to "required"))
        }
        return dsl.transactionResult(org.jooq.TransactionalCallable {
            val updated = taskRepository.reopen(id, request.version)
                ?: throw ConflictException("Task was modified concurrently or invalid status")
            taskRepository.addStatusHistory(id, "NEW", currentUser.userId, request.reason.trim())
            auditService.log(currentUser.userId, "REOPEN", "TASK", id, mapOf("reason" to request.reason.trim()))
            updated.toDto(loadParticipants(id))
        })
    }

    fun cancel(id: Long, request: CancelTaskRequest, currentUser: CurrentUser): TaskDto {
        val task = taskRepository.findById(id) ?: throw NotFoundException("task", id)
        if (!isInitiator(task, currentUser) && !currentUser.hasPermission("tasks", "cancel_any")) {
            throw ForbiddenException("Only initiator or authorized user can cancel the task")
        }
        if (request.reason.isBlank()) {
            throw ValidationException("Cancel reason is required", mapOf("reason" to "required"))
        }
        return dsl.transactionResult(org.jooq.TransactionalCallable {
            val updated = taskRepository.cancel(id, request.version)
                ?: throw ConflictException("Task was modified concurrently or invalid status")
            taskRepository.addStatusHistory(id, "CANCELLED", currentUser.userId, request.reason.trim())
            auditService.log(currentUser.userId, "CANCEL", "TASK", id, mapOf("reason" to request.reason.trim()))
            updated.toDto(loadParticipants(id))
        })
    }

    private fun validateCreate(request: CreateTaskRequest, currentUser: CurrentUser) {
        if (request.title.isBlank()) {
            throw ValidationException("Title is required", mapOf("title" to "required"))
        }
        when (request.area) {
            TaskArea.PROJECT -> {
                val projectId = request.projectId
                    ?: throw ValidationException("Project is required for project task", mapOf("projectId" to "required"))
                val project = projectRepository.findById(projectId)
                    ?: throw NotFoundException("project", projectId)
                if (!projectRepository.isMember(projectId, currentUser.userId) && project.ownerId != currentUser.userId) {
                    throw ForbiddenException("Only project member or owner can create project task")
                }
                if (request.departmentId != null) {
                    throw ValidationException("Department must not be set for project task", mapOf("departmentId" to "forbidden"))
                }
            }
            TaskArea.DEPARTMENT -> {
                val departmentId = request.departmentId
                    ?: throw ValidationException("Department is required for department task", mapOf("departmentId" to "required"))
                val department = departmentRepository.findById(departmentId)
                    ?: throw NotFoundException("department", departmentId)
                val user = userRepository.findById(currentUser.userId)
                if (user?.departmentId != departmentId && department.headId != currentUser.userId && !currentUser.hasPermission("tasks", "assign_any")) {
                    throw ForbiddenException("Only department employee or head can create department task")
                }
                if (request.projectId != null) {
                    throw ValidationException("Project must not be set for department task", mapOf("projectId" to "forbidden"))
                }
            }
            TaskArea.PERSONAL -> {
                if (request.projectId != null || request.departmentId != null) {
                    throw ValidationException("Personal task must not have project or department", mapOf("projectId" to "forbidden", "departmentId" to "forbidden"))
                }
            }
        }

        val assigneeId = request.assigneeId
            ?: if (request.area == TaskArea.PERSONAL) currentUser.userId else null
        if (request.area != TaskArea.PERSONAL && assigneeId == null) {
            throw ValidationException("Assignee is required", mapOf("assigneeId" to "required"))
        }
        if (request.area == TaskArea.PERSONAL && assigneeId != null && assigneeId != currentUser.userId && !currentUser.hasPermission("tasks", "assign_any")) {
            throw ForbiddenException("Only authorized user can create personal task for another")
        }

        assigneeId?.let { validateUserActive(it, "assigneeId") }

        if (assigneeId != null && assigneeId != currentUser.userId && request.description.isNullOrBlank()) {
            throw ValidationException("Description is required when assigning to another user", mapOf("description" to "required"))
        }

        if (request.type == TaskWorkType.REVIEWED) {
            val reviewerId = request.reviewerId
                ?: throw ValidationException("Reviewer is required for reviewed task", mapOf("reviewerId" to "required"))
            if (request.acceptanceCriteria.isNullOrBlank()) {
                throw ValidationException("Acceptance criteria are required for reviewed task", mapOf("acceptanceCriteria" to "required"))
            }
            if (reviewerId == assigneeId) {
                throw ValidationException("Reviewer must differ from assignee", mapOf("reviewerId" to "same_as_assignee"))
            }
            validateUserActive(reviewerId, "reviewerId")
        }

        request.dueDate?.let { parseDateTime(it) ?: throw ValidationException("Invalid due date", mapOf("dueDate" to "invalid")) }
        request.plannedStartAt?.let { parseDateTime(it) ?: throw ValidationException("Invalid planned start date", mapOf("plannedStartAt" to "invalid")) }

        val coexecAndObservers = request.coexecutorIds + request.observerIds
        coexecAndObservers.forEach { validateUserActive(it, "participantId") }
    }

    private fun validateAssign(task: TasksRecord, assigneeId: Long, currentUser: CurrentUser) {
        if (task.status != "NEW") {
            throw ConflictException("Task can only be assigned from NEW status")
        }
        validateUserActive(assigneeId, "assigneeId")
        if (task.type == "REVIEWED" && assigneeId == task.reviewerId) {
            throw ValidationException("Reviewer must differ from assignee", mapOf("assigneeId" to "same_as_reviewer"))
        }
        if (assigneeId != currentUser.userId && task.description.isNullOrBlank()) {
            throw ValidationException("Description is required when assigning to another user", mapOf("description" to "required"))
        }
    }

    private fun validateUserActive(userId: Long, field: String) {
        val user = userRepository.findById(userId)
            ?: throw NotFoundException("user", userId)
        if (user.active != true) {
            throw ValidationException("User must be active", mapOf(field to "inactive"))
        }
    }

    private fun canRead(task: TasksRecord, currentUser: CurrentUser): Boolean {
        if (currentUser.hasPermission("tasks", "read_any")) return true
        if (isInitiator(task, currentUser) || isAssignee(task, currentUser) || isReviewer(task, currentUser)) return true
        if (isParticipant(task.id, currentUser.userId, listOf(TaskParticipantRole.COEXECUTOR, TaskParticipantRole.OBSERVER))) return true

        if (task.area == "PROJECT" && task.projectId != null) {
            val project = projectRepository.findById(task.projectId)
            if (project != null && (projectRepository.isMember(task.projectId, currentUser.userId) || project.ownerId == currentUser.userId)) {
                return true
            }
        }
        if (task.area == "DEPARTMENT" && task.departmentId != null) {
            val user = userRepository.findById(currentUser.userId)
            if (user?.departmentId == task.departmentId) return true
        }
        return false
    }

    private fun canManage(task: TasksRecord, currentUser: CurrentUser): Boolean {
        return isInitiator(task, currentUser) || currentUser.hasPermission("tasks", "assign_any")
    }

    private fun canBlock(task: TasksRecord, currentUser: CurrentUser): Boolean {
        return isInitiator(task, currentUser) || isAssignee(task, currentUser) || isReviewer(task, currentUser)
            || currentUser.hasPermission("tasks", "block_any")
    }

    private fun canUnblock(task: TasksRecord, currentUser: CurrentUser): Boolean {
        if (currentUser.hasPermission("tasks", "block_any")) return true
        if (isInitiator(task, currentUser) || isAssignee(task, currentUser)) return true
        if (task.blockedBy == currentUser.userId) return true
        if (task.unblockResponsibleId == currentUser.userId) return true
        return false
    }

    private fun isInitiator(task: TasksRecord, currentUser: CurrentUser): Boolean = task.initiatorId == currentUser.userId
    private fun isAssignee(task: TasksRecord, currentUser: CurrentUser): Boolean = task.assigneeId == currentUser.userId
    private fun isReviewer(task: TasksRecord, currentUser: CurrentUser): Boolean = task.reviewerId == currentUser.userId

    private fun isParticipant(taskId: Long, userId: Long, roles: List<TaskParticipantRole>): Boolean {
        val roleNames = roles.map { it.name }
        return taskRepository.listParticipants(taskId).any { participant ->
            participant.userId == userId && roleNames.contains(participant.role)
        }
    }

    private fun checkVersion(task: TasksRecord, expectedVersion: Long) {
        if ((task.version ?: 0) != expectedVersion) {
            throw ConflictException("Task was modified concurrently")
        }
    }

    private fun loadParticipants(taskId: Long): TaskParticipants {
        val participants = taskRepository.listParticipants(taskId)
        return TaskParticipants(
            coexecutors = participants.filter { it.role == TaskParticipantRole.COEXECUTOR.name }.map { it.userId },
            observers = participants.filter { it.role == TaskParticipantRole.OBSERVER.name }.map { it.userId }
        )
    }

    private fun parseDateTime(value: String?): OffsetDateTime? {
        if (value.isNullOrBlank()) return null
        return try {
            OffsetDateTime.parse(value)
        } catch (_: DateTimeParseException) {
            null
        }
    }

    private data class TaskParticipants(
        val coexecutors: List<Long>,
        val observers: List<Long>
    )

    private fun TasksRecord.toDto(participants: TaskParticipants): TaskDto = TaskDto(
        id = this.id,
        number = this.number,
        area = TaskArea.valueOf(this.area!!),
        status = TaskStatus.valueOf(this.status!!),
        type = TaskWorkType.valueOf(this.type!!),
        title = this.title!!,
        description = this.description,
        acceptanceCriteria = this.acceptanceCriteria,
        result = this.result,
        initiatorId = this.initiatorId!!,
        assigneeId = this.assigneeId,
        reviewerId = this.reviewerId,
        coexecutorIds = participants.coexecutors,
        observerIds = participants.observers,
        projectId = this.projectId,
        departmentId = this.departmentId,
        dueDate = this.dueDate?.toString(),
        plannedStartAt = this.plannedStartAt?.toString(),
        priority = this.priority?.let { TaskPriority.valueOf(it) } ?: TaskPriority.NORMAL,
        blockedReason = this.blockedReason,
        blockedBy = this.blockedBy,
        blockedAt = this.blockedAt?.toString(),
        unblockResponsibleId = this.unblockResponsibleId,
        closedAt = this.closedAt?.toString(),
        createdAt = this.createdAt?.toString(),
        createdBy = this.createdBy!!,
        version = this.version ?: 0
    )
}
