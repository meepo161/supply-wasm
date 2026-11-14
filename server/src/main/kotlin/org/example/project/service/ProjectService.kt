package org.example.project.service

import org.example.project.dto.*
import org.example.project.exception.ForbiddenException
import org.example.project.exception.NotFoundException
import org.example.project.exception.ValidationException
import org.example.project.generated.jooq.tables.records.ProjectsRecord
import org.example.project.model.CurrentUser
import org.example.project.repository.ProjectRepository
import org.example.project.repository.UserRepository
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

class ProjectService(
    private val projectRepository: ProjectRepository,
    private val userRepository: UserRepository,
    private val auditService: AuditService,
    private val dsl: org.jooq.DSLContext
) {

    fun create(request: CreateProjectRequest, currentUser: CurrentUser): ProjectDto {
        currentUser.requirePermission("projects", "create")
        validateCreate(request)

        return dsl.transactionResult(org.jooq.TransactionalCallable {
            val number = projectRepository.generateNumber()
            val project = projectRepository.create(
                number = number,
                name = request.name.trim(),
                fullName = request.fullName?.trim(),
                description = request.description?.trim(),
                projectType = request.projectType?.name,
                customer = request.customer?.trim(),
                ownerId = request.ownerId,
                departmentId = request.departmentId,
                priority = (request.priority ?: ProjectPriority.NORMAL).name,
                plannedStartAt = parseDateTime(request.plannedStartAt),
                plannedEndAt = parseDateTime(request.plannedEndAt),
                createdBy = currentUser.userId
            )
            projectRepository.addMember(project.id, currentUser.userId, currentUser.userId)
            projectRepository.addMember(project.id, request.ownerId, currentUser.userId)

            auditService.log(
                currentUser.userId,
                "CREATE",
                "PROJECT",
                project.id,
                mapOf("number" to project.number, "name" to project.name, "ownerId" to request.ownerId)
            )
            project.toDto(loadMembers(project.id))
        })
    }

    fun open(id: Long, request: OpenProjectRequest, currentUser: CurrentUser): ProjectDto {
        currentUser.requirePermission("projects", "open")
        val project = projectRepository.findById(id) ?: throw NotFoundException("project", id)
        if (!canOpen(project, currentUser)) {
            throw ForbiddenException("Only project owner or authorized user can open this project")
        }
        validateOpen(project, request.openingReason)

        return dsl.transactionResult(org.jooq.TransactionalCallable {
            val opened = projectRepository.open(
                id = id,
                openingReason = request.openingReason.trim(),
                openedBy = currentUser.userId,
                expectedVersion = request.version
            )
            auditService.log(
                currentUser.userId,
                "OPEN",
                "PROJECT",
                id,
                mapOf("number" to opened.number, "openingReason" to request.openingReason)
            )
            opened.toDto(loadMembers(id))
        })
    }

    fun close(id: Long, request: CloseProjectRequest, currentUser: CurrentUser): ProjectDto {
        currentUser.requirePermission("projects", "close")
        val project = projectRepository.findById(id) ?: throw NotFoundException("project", id)
        if (project.ownerId != currentUser.userId && !currentUser.hasPermission("projects", "close_any")) {
            throw ForbiddenException("Only project owner or authorized user can close this project")
        }

        return dsl.transactionResult(org.jooq.TransactionalCallable {
            val locked = projectRepository.lockById(id) ?: throw NotFoundException("project", id)
            if (locked.version != request.version) {
                throw org.example.project.exception.ConflictException("Project was modified concurrently")
            }
            if (locked.status !in CLOSEABLE_STATUSES) {
                throw org.example.project.exception.ConflictException("Project cannot be closed from status ${locked.status}")
            }
            val blockers = projectRepository.listUnfinishedProcesses(id)
            if (blockers.isNotEmpty()) {
                val summary = blockers.joinToString(", ") { "${it.type}:${it.id}(${it.status})" }
                throw org.example.project.exception.ConflictException("Project has unfinished related processes: $summary")
            }
            val closed = projectRepository.close(id, request.version)
            auditService.log(
                currentUser.userId,
                "CLOSE",
                "PROJECT",
                id,
                mapOf("number" to closed.number, "reason" to request.reason?.trim())
            )
            closed.toDto(loadMembers(id))
        })
    }

    fun get(id: Long, currentUser: CurrentUser): ProjectDto {
        val project = projectRepository.findById(id) ?: throw NotFoundException("project", id)
        if (!canRead(project, currentUser)) {
            throw ForbiddenException("Access to project denied")
        }
        return project.toDto(loadMembers(id))
    }

    fun list(currentUser: CurrentUser): List<ProjectDto> {
        val records = if (currentUser.hasPermission("projects", "read_any")) {
            projectRepository.listAll()
        } else {
            projectRepository.listForUser(currentUser.userId)
        }
        return records.map { it.toDto() }
    }

    fun addMember(id: Long, request: AddProjectMemberRequest, currentUser: CurrentUser): ProjectDto {
        val project = projectRepository.findById(id) ?: throw NotFoundException("project", id)
        if (!canManageMembers(project, currentUser)) {
            throw ForbiddenException("Only project owner or authorized user can manage members")
        }
        if (!userRepository.exists(request.userId)) {
            throw NotFoundException("user", request.userId)
        }
        return dsl.transactionResult(org.jooq.TransactionalCallable {
            projectRepository.addMember(id, request.userId, currentUser.userId)
            auditService.log(
                currentUser.userId,
                "ADD_MEMBER",
                "PROJECT",
                id,
                mapOf("addedUserId" to request.userId)
            )
            projectRepository.findById(id)!!.toDto(loadMembers(id))
        })
    }

    fun removeMember(id: Long, userId: Long, currentUser: CurrentUser): ProjectDto {
        val project = projectRepository.findById(id) ?: throw NotFoundException("project", id)
        if (!canManageMembers(project, currentUser)) {
            throw ForbiddenException("Only project owner or authorized user can manage members")
        }
        if (userId == project.ownerId || userId == project.createdBy) {
            throw ForbiddenException("Project owner and creator cannot be removed")
        }
        if (!projectRepository.isMember(id, userId)) {
            throw NotFoundException("project_member", userId)
        }
        return dsl.transactionResult(org.jooq.TransactionalCallable {
            projectRepository.removeMember(id, userId)
            auditService.log(
                currentUser.userId,
                "REMOVE_MEMBER",
                "PROJECT",
                id,
                mapOf("removedUserId" to userId)
            )
            projectRepository.findById(id)!!.toDto(loadMembers(id))
        })
    }

    private fun validateCreate(request: CreateProjectRequest) {
        if (request.name.isBlank()) {
            throw ValidationException("Project name is required", mapOf("name" to "required"))
        }
        if (request.projectType == null) {
            throw ValidationException("Project type is required", mapOf("projectType" to "required"))
        }
        val owner = userRepository.findById(request.ownerId)
            ?: throw NotFoundException("user", request.ownerId)
        if (owner.active != true) {
            throw ValidationException("Project owner must be active", mapOf("ownerId" to "inactive"))
        }
        if (request.plannedStartAt != null && parseDateTime(request.plannedStartAt) == null) {
            throw ValidationException("Invalid planned start date", mapOf("plannedStartAt" to "invalid"))
        }
        if (request.plannedEndAt != null && parseDateTime(request.plannedEndAt) == null) {
            throw ValidationException("Invalid planned end date", mapOf("plannedEndAt" to "invalid"))
        }
        val plannedStartAt = parseDateTime(request.plannedStartAt)
        val plannedEndAt = parseDateTime(request.plannedEndAt)
        if (plannedStartAt != null && plannedEndAt != null && plannedEndAt.isBefore(plannedStartAt)) {
            throw ValidationException("Planned end date must be after or equal to planned start date", mapOf("plannedEndAt" to "before_start"))
        }
    }

    private fun validateOpen(project: ProjectsRecord, openingReason: String) {
        if (openingReason.isBlank()) {
            throw ValidationException("Opening reason is required", mapOf("openingReason" to "required"))
        }
        if (project.projectType == null) {
            throw ValidationException("Project type is required for opening", mapOf("projectType" to "required"))
        }
        val owner = userRepository.findById(project.ownerId)
            ?: throw ValidationException("Project owner not found", mapOf("ownerId" to "not_found"))
        if (owner.active != true) {
            throw ValidationException("Project owner must be active", mapOf("ownerId" to "inactive"))
        }
        if (project.plannedStartAt == null) {
            throw ValidationException("Planned start date is required for opening", mapOf("plannedStartAt" to "required"))
        }
        if (project.plannedEndAt == null) {
            throw ValidationException("Planned end date is required for opening", mapOf("plannedEndAt" to "required"))
        }
        if (project.plannedEndAt!!.isBefore(project.plannedStartAt)) {
            throw ValidationException("Planned end date must be after or equal to planned start date", mapOf("plannedEndAt" to "before_start"))
        }
    }

    private fun canRead(project: ProjectsRecord, currentUser: CurrentUser): Boolean {
        return currentUser.hasPermission("projects", "read_any") ||
            projectRepository.isMember(project.id, currentUser.userId) ||
            project.ownerId == currentUser.userId
    }

    private fun canOpen(project: ProjectsRecord, currentUser: CurrentUser): Boolean {
        return project.ownerId == currentUser.userId ||
            currentUser.hasPermission("projects", "open_any")
    }

    private fun canManageMembers(project: ProjectsRecord, currentUser: CurrentUser): Boolean {
        return project.ownerId == currentUser.userId ||
            currentUser.hasPermission("projects", "manage_members")
    }

    private fun loadMembers(projectId: Long): List<ProjectMemberDto> {
        return projectRepository.listMembers(projectId).map { it.toDto() }
    }

    private fun parseDateTime(value: String?): OffsetDateTime? {
        if (value.isNullOrBlank()) return null
        return try {
            OffsetDateTime.parse(value)
        } catch (_: DateTimeParseException) {
            null
        }
    }

    companion object {
        private val CLOSEABLE_STATUSES = setOf("PLANNED", "IN_PROGRESS", "ON_HOLD", "COMPLETED")
    }
}
