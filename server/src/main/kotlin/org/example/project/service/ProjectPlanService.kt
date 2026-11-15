package org.example.project.service

import org.example.project.dto.*
import org.example.project.exception.ConflictException
import org.example.project.exception.ForbiddenException
import org.example.project.exception.NotFoundException
import org.example.project.exception.ValidationException
import org.example.project.generated.jooq.tables.records.ProjectMilestonesRecord
import org.example.project.generated.jooq.tables.records.ProjectRisksRecord
import org.example.project.generated.jooq.tables.records.ProjectStagesRecord
import org.example.project.generated.jooq.tables.records.ProjectsRecord
import org.example.project.model.CurrentUser
import org.example.project.repository.ProjectPlanRepository
import org.example.project.repository.ProjectRepository
import org.jooq.DSLContext
import org.jooq.TransactionalCallable
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * План проекта: этапы, контрольные точки и риски (5.3, 5.5 ТЗ;
 * PRJ-08, PRJ-09, PRJ-10).
 *
 * Правила, зафиксированные в D-51:
 *  - читать план может участник проекта или держатель `projects:read_any`;
 *  - создавать, менять и удалять элементы плана — владелец проекта или
 *    держатель `projects:manage_plan`;
 *  - всё версионируется, устаревшая версия даёт 409, а не «последняя запись
 *    побеждает»;
 *  - проценты выполнения не вычисляются: показывается статус этапа;
 *  - критичность риска задаётся явно, матрица «вероятность × влияние» не
 *    изобретается;
 *  - закрытие проекта с незавершёнными этапами или открытыми критичными
 *    рисками не блокируется: по 5.6 это отдельное правило, которое здесь не
 *    вводится без решения.
 */
class ProjectPlanService(
    private val planRepository: ProjectPlanRepository,
    private val projectRepository: ProjectRepository,
    private val auditService: AuditService,
    private val dsl: DSLContext
) {

    // --- Чтение ------------------------------------------------------------

    fun listStages(projectId: Long, status: ProjectStageStatus?, currentUser: CurrentUser): List<ProjectStageDto> {
        requireReadableProject(projectId, currentUser)
        val stages = planRepository.listStages(projectId, status?.name)
        if (stages.isEmpty()) return emptyList()

        // Контрольные точки подтягиваются одним запросом и раскладываются по
        // этапам: отдельный запрос на каждый этап дал бы N+1.
        val milestonesByStage = planRepository.listMilestones(projectId)
            .groupBy { it.stageId }
        return stages.map { stage ->
            stage.toDto(milestonesByStage[stage.id].orEmpty().map { it.toDto() })
        }
    }

    fun getStage(stageId: Long, currentUser: CurrentUser): ProjectStageDto {
        val stage = planRepository.findStageById(stageId)
            ?: throw NotFoundException("project_stage", stageId)
        requireReadableProject(stage.projectId, currentUser)
        val milestones = planRepository.listMilestones(stage.projectId, stageId = stage.id)
            .map { it.toDto() }
        return stage.toDto(milestones)
    }

    fun listMilestones(
        projectId: Long,
        stageId: Long?,
        status: ProjectMilestoneStatus?,
        currentUser: CurrentUser
    ): List<ProjectMilestoneDto> {
        requireReadableProject(projectId, currentUser)
        if (stageId != null) requireStageOfProject(stageId, projectId)
        return planRepository.listMilestones(projectId, stageId, status?.name).map { it.toDto() }
    }

    fun listRisks(projectId: Long, status: ProjectRiskStatus?, currentUser: CurrentUser): List<ProjectRiskDto> {
        requireReadableProject(projectId, currentUser)
        return planRepository.listRisks(projectId, status?.name).map { it.toDto() }
    }

    /** Счётчики плана для вкладки «Обзор». Только реальные значения. */
    fun planSummary(projectId: Long, currentUser: CurrentUser): ProjectPlanSummaryDto {
        requireReadableProject(projectId, currentUser)
        val current = planRepository.currentStage(projectId)
        val next = planRepository.nextMilestone(projectId)
        return ProjectPlanSummaryDto(
            projectId = projectId,
            stageCount = planRepository.countStages(projectId),
            completedStageCount = planRepository.countStagesByStatus(projectId, "COMPLETED"),
            currentStageId = current?.id,
            currentStageName = current?.name,
            milestoneCount = planRepository.countMilestones(projectId),
            reachedMilestoneCount = planRepository.countMilestonesByStatus(projectId, "REACHED"),
            nextMilestoneId = next?.id,
            nextMilestoneName = next?.name,
            nextMilestonePlannedAt = next?.plannedAt?.toString(),
            openRiskCount = planRepository.countOpenRisks(projectId),
            criticalRiskCount = planRepository.countCriticalRisks(projectId)
        )
    }

    // --- Этапы: создание и изменение --------------------------------------

    fun createStage(
        projectId: Long,
        request: CreateProjectStageRequest,
        currentUser: CurrentUser
    ): ProjectStageDto {
        val project = requireManageableProject(projectId, currentUser)
        requireNotClosed(project, "stage")
        val name = requireName(request.name, "name")
        val (plannedStartAt, plannedEndAt) = parsePeriod(request.plannedStartAt, request.plannedEndAt)
        request.responsibleId?.let { requireUser(it) }

        return dsl.transactionResult(TransactionalCallable {
            val sequence = request.sequence ?: (planRepository.nextStageSequence(projectId) + 1)
            if (sequence <= 0) {
                throw ValidationException("Stage sequence must be positive", mapOf("sequence" to "not_positive"))
            }
            if (planRepository.listStages(projectId).any { it.sequence == sequence }) {
                throw ConflictException("Stage with sequence $sequence already exists in this project")
            }
            val stage = planRepository.insertStage(
                projectId = projectId,
                name = name,
                description = request.description?.trim(),
                sequence = sequence,
                plannedStartAt = plannedStartAt,
                plannedEndAt = plannedEndAt,
                responsibleId = request.responsibleId,
                completionCriteria = request.completionCriteria?.trim(),
                createdBy = currentUser.userId
            )
            auditService.log(
                currentUser.userId,
                "CREATE",
                "PROJECT_STAGE",
                stage.id,
                mapOf("projectId" to projectId, "name" to name, "sequence" to sequence)
            )
            stage.toDto(emptyList())
        })
    }

    fun updateStage(
        stageId: Long,
        request: UpdateProjectStageRequest,
        currentUser: CurrentUser
    ): ProjectStageDto {
        return dsl.transactionResult(TransactionalCallable {
            val stage = planRepository.lockStageById(stageId)
                ?: throw NotFoundException("project_stage", stageId)
            val project = requireManageableProject(stage.projectId, currentUser)
            requireNotClosed(project, "stage")
            if (stage.status == "COMPLETED" || stage.status == "CANCELLED") {
                throw ConflictException("Completed or cancelled stage cannot be edited")
            }

            val name = request.name?.let { requireName(it, "name") } ?: stage.name
            val sequence = request.sequence ?: stage.sequence
            if (sequence <= 0) {
                throw ValidationException("Stage sequence must be positive", mapOf("sequence" to "not_positive"))
            }
            if (sequence != stage.sequence &&
                planRepository.listStages(stage.projectId).any { it.id != stageId && it.sequence == sequence }
            ) {
                throw ConflictException("Stage with sequence $sequence already exists in this project")
            }
            // Незаданное поле не стирается: null здесь означает «оставить как было»,
            // а очистка значения делается явной пустой строкой.
            val plannedStartAt = if (request.plannedStartAt == null) stage.plannedStartAt
            else parseDateTime(request.plannedStartAt, "plannedStartAt")
            val plannedEndAt = if (request.plannedEndAt == null) stage.plannedEndAt
            else parseDateTime(request.plannedEndAt, "plannedEndAt")
            requirePeriod(plannedStartAt, plannedEndAt)
            request.responsibleId?.let { requireUser(it) }

            val updated = planRepository.updateStage(
                id = stageId,
                name = name,
                description = if (request.description == null) stage.description
                else request.description?.trim()?.ifBlank { null },
                sequence = sequence,
                plannedStartAt = plannedStartAt,
                plannedEndAt = plannedEndAt,
                responsibleId = request.responsibleId ?: stage.responsibleId,
                completionCriteria = if (request.completionCriteria == null) stage.completionCriteria
                else request.completionCriteria?.trim()?.ifBlank { null },
                expectedVersion = request.version
            ) ?: throw ConflictException("Stage was modified concurrently")

            auditService.log(
                currentUser.userId,
                "UPDATE",
                "PROJECT_STAGE",
                updated.id,
                mapOf("projectId" to updated.projectId, "version" to updated.version)
            )
            val milestones = planRepository.listMilestones(updated.projectId, stageId = updated.id)
                .map { it.toDto() }
            updated.toDto(milestones)
        })
    }

    /**
     * Команды этапа: `START` -> IN_PROGRESS, `COMPLETE` -> COMPLETED,
     * `CANCEL` -> CANCELLED (только с причиной). Обратных переходов нет:
     * отменить завершённый этап нельзя, новый этап заводится заново.
     */
    fun commandStage(
        stageId: Long,
        command: String,
        request: ProjectStageCommandRequest,
        currentUser: CurrentUser
    ): ProjectStageDto {
        return dsl.transactionResult(TransactionalCallable {
            val stage = planRepository.lockStageById(stageId)
                ?: throw NotFoundException("project_stage", stageId)
            val project = requireManageableProject(stage.projectId, currentUser)
            requireNotClosed(project, "stage")

            val now = OffsetDateTime.now()
            val (status, actualStart, actualEnd, cancelReason) = when (command) {
                "START" -> {
                    if (stage.status != "PLANNED") {
                        throw ConflictException("Only planned stage can be started, current status is ${stage.status}")
                    }
                    Quad("IN_PROGRESS", stage.actualStartAt ?: now, null, null)
                }
                "COMPLETE" -> {
                    if (stage.status != "IN_PROGRESS") {
                        throw ConflictException("Only stage in progress can be completed, current status is ${stage.status}")
                    }
                    Quad("COMPLETED", stage.actualStartAt ?: now, now, null)
                }
                "CANCEL" -> {
                    if (stage.status == "COMPLETED" || stage.status == "CANCELLED") {
                        throw ConflictException("Completed or cancelled stage cannot be cancelled")
                    }
                    val reason = requireName(request.note ?: "", "note")
                    Quad("CANCELLED", stage.actualStartAt, null, reason)
                }
                else -> throw ValidationException("Unknown stage command", mapOf("command" to "unknown"))
            }

            val updated = planRepository.updateStageStatus(
                id = stageId,
                status = status,
                actualStartAt = actualStart,
                actualEndAt = actualEnd,
                cancelReason = cancelReason,
                expectedVersion = request.version
            ) ?: throw ConflictException("Stage was modified concurrently or the transition is invalid")

            auditService.log(
                currentUser.userId,
                "STAGE_$command",
                "PROJECT_STAGE",
                updated.id,
                mapOf("projectId" to updated.projectId, "status" to status)
            )
            val milestones = planRepository.listMilestones(updated.projectId, stageId = updated.id)
                .map { it.toDto() }
            updated.toDto(milestones)
        })
    }

    /**
     * Удаление этапа — только пока он в плане: работающий, завершённый или
     * отменённый этап остаётся в истории проекта. Контрольные точки этапа
     * удаляются каскадом схемы. Этап, на который ссылаются риски, удалить
     * нельзя: молча обнулять ссылку значило бы менять карточку риска без
     * решения ответственного, поэтому сначала риски перепривязываются вручную.
     */
    fun deleteStage(stageId: Long, version: Long, currentUser: CurrentUser) {
        dsl.transactionResult(TransactionalCallable {
            val stage = planRepository.lockStageById(stageId)
                ?: throw NotFoundException("project_stage", stageId)
            requireManageableProject(stage.projectId, currentUser)
            if (stage.status != "PLANNED") {
                throw ConflictException("Only planned stage can be deleted")
            }
            if (planRepository.countRisksByStage(stageId) > 0) {
                throw ConflictException("Stage is referenced by project risks; detach them first")
            }
            val deleted = planRepository.deleteStage(stageId, version)
            if (deleted == 0) throw ConflictException("Stage was modified concurrently")
            auditService.log(
                currentUser.userId,
                "DELETE",
                "PROJECT_STAGE",
                stageId,
                mapOf("projectId" to stage.projectId, "name" to stage.name)
            )
        })
    }

    // --- Контрольные точки -------------------------------------------------

    fun createMilestone(
        projectId: Long,
        request: CreateProjectMilestoneRequest,
        currentUser: CurrentUser
    ): ProjectMilestoneDto {
        val project = requireManageableProject(projectId, currentUser)
        requireNotClosed(project, "milestone")
        val name = requireName(request.name, "name")
        request.stageId?.let { requireStageOfProject(it, projectId) }
        request.responsibleId?.let { requireUser(it) }
        val plannedAt = parseDateTime(request.plannedAt, "plannedAt")

        return dsl.transactionResult(TransactionalCallable {
            val milestone = planRepository.insertMilestone(
                projectId = projectId,
                stageId = request.stageId,
                name = name,
                description = request.description?.trim(),
                plannedAt = plannedAt,
                responsibleId = request.responsibleId,
                createdBy = currentUser.userId
            )
            auditService.log(
                currentUser.userId,
                "CREATE",
                "PROJECT_MILESTONE",
                milestone.id,
                mapOf("projectId" to projectId, "name" to name)
            )
            milestone.toDto()
        })
    }

    fun updateMilestone(
        milestoneId: Long,
        request: UpdateProjectMilestoneRequest,
        currentUser: CurrentUser
    ): ProjectMilestoneDto {
        return dsl.transactionResult(TransactionalCallable {
            val milestone = planRepository.findMilestoneById(milestoneId)
                ?: throw NotFoundException("project_milestone", milestoneId)
            val project = requireManageableProject(milestone.projectId, currentUser)
            requireNotClosed(project, "milestone")
            if (milestone.status != "PLANNED") {
                throw ConflictException("Reached, missed or cancelled milestone cannot be edited")
            }
            request.stageId?.let { requireStageOfProject(it, milestone.projectId) }
            request.responsibleId?.let { requireUser(it) }

            val updated = planRepository.updateMilestone(
                id = milestoneId,
                name = request.name?.let { requireName(it, "name") } ?: milestone.name,
                stageId = request.stageId ?: milestone.stageId,
                description = if (request.description == null) milestone.description
                else request.description?.trim()?.ifBlank { null },
                plannedAt = if (request.plannedAt == null) milestone.plannedAt
                else parseDateTime(request.plannedAt, "plannedAt"),
                responsibleId = request.responsibleId ?: milestone.responsibleId,
                note = if (request.note == null) milestone.note
                else request.note?.trim()?.ifBlank { null },
                expectedVersion = request.version
            ) ?: throw ConflictException("Milestone was modified concurrently")

            auditService.log(
                currentUser.userId,
                "UPDATE",
                "PROJECT_MILESTONE",
                updated.id,
                mapOf("projectId" to updated.projectId)
            )
            updated.toDto()
        })
    }

    /**
     * Отметка контрольной точки: `REACH` фиксирует фактическую дату,
     * `MISS` — что точка не достигнута в срок. Обе команды требуют версию.
     */
    fun commandMilestone(
        milestoneId: Long,
        command: String,
        request: ProjectMilestoneCommandRequest,
        currentUser: CurrentUser
    ): ProjectMilestoneDto {
        return dsl.transactionResult(TransactionalCallable {
            val milestone = planRepository.findMilestoneById(milestoneId)
                ?: throw NotFoundException("project_milestone", milestoneId)
            requireManageableProject(milestone.projectId, currentUser)
            if (milestone.status != "PLANNED") {
                throw ConflictException("Milestone is already ${milestone.status}")
            }
            val status = when (command) {
                "REACH" -> "REACHED"
                "MISS" -> "MISSED"
                else -> throw ValidationException("Unknown milestone command", mapOf("command" to "unknown"))
            }
            val updated = planRepository.updateMilestoneStatus(
                id = milestoneId,
                status = status,
                actualAt = OffsetDateTime.now(),
                note = request.note?.trim()?.ifBlank { null } ?: milestone.note,
                expectedVersion = request.version
            ) ?: throw ConflictException("Milestone was modified concurrently")

            auditService.log(
                currentUser.userId,
                "MILESTONE_$command",
                "PROJECT_MILESTONE",
                updated.id,
                mapOf("projectId" to updated.projectId, "status" to status)
            )
            updated.toDto()
        })
    }

    /** Удаляется только плановая контрольная точка: факт остаётся в истории. */
    fun deleteMilestone(milestoneId: Long, version: Long, currentUser: CurrentUser) {
        dsl.transactionResult(TransactionalCallable {
            val milestone = planRepository.findMilestoneById(milestoneId)
                ?: throw NotFoundException("project_milestone", milestoneId)
            requireManageableProject(milestone.projectId, currentUser)
            if (milestone.status != "PLANNED") {
                throw ConflictException("Reached, missed or cancelled milestone cannot be deleted")
            }
            if (planRepository.deleteMilestone(milestoneId, version) == 0) {
                throw ConflictException("Milestone was modified concurrently")
            }
            auditService.log(
                currentUser.userId,
                "DELETE",
                "PROJECT_MILESTONE",
                milestoneId,
                mapOf("projectId" to milestone.projectId, "name" to milestone.name)
            )
        })
    }

    // --- Риски -------------------------------------------------------------

    fun createRisk(
        projectId: Long,
        request: CreateProjectRiskRequest,
        currentUser: CurrentUser
    ): ProjectRiskDto {
        val project = requireManageableProject(projectId, currentUser)
        requireNotClosed(project, "risk")
        validateRisk(
            title = request.title,
            responsePlan = request.responsePlan,
            stageId = request.stageId,
            taskId = request.taskId,
            projectId = projectId
        )

        return dsl.transactionResult(TransactionalCallable {
            val risk = planRepository.insertRisk(
                projectId = projectId,
                title = request.title.trim(),
                description = request.description?.trim(),
                category = request.category.name,
                probability = request.probability.name,
                impact = request.impact.name,
                criticality = request.criticality.name,
                responsibleId = request.responsibleId,
                responsePlan = request.responsePlan?.trim(),
                dueDate = parseDate(request.dueDate, "dueDate"),
                stageId = request.stageId,
                taskId = request.taskId,
                createdBy = currentUser.userId
            )
            auditService.log(
                currentUser.userId,
                "CREATE",
                "PROJECT_RISK",
                risk.id,
                mapOf(
                    "projectId" to projectId,
                    "criticality" to request.criticality.name,
                    "title" to risk.title
                )
            )
            risk.toDto()
        })
    }

    fun updateRisk(
        riskId: Long,
        request: UpdateProjectRiskRequest,
        currentUser: CurrentUser
    ): ProjectRiskDto {
        return dsl.transactionResult(TransactionalCallable {
            val risk = planRepository.findRiskById(riskId) ?: throw NotFoundException("project_risk", riskId)
            val project = requireManageableProject(risk.projectId, currentUser)
            requireNotClosed(project, "risk")
            if (risk.status == "CLOSED" || risk.status == "ACCEPTED") {
                throw ConflictException("Closed risk cannot be edited")
            }
            request.responsibleId?.let { requireUser(it) }

            val title = request.title?.let { requireName(it, "title") } ?: risk.title
            val stageId = request.stageId ?: risk.stageId
            val taskId = request.taskId ?: risk.taskId
            val responsePlan = if (request.responsePlan == null) risk.responsePlan
            else request.responsePlan?.trim()?.ifBlank { null }
            validateRisk(title, responsePlan, stageId, taskId, risk.projectId)

            val updated = planRepository.updateRisk(
                id = riskId,
                title = title,
                description = if (request.description == null) risk.description
                else request.description?.trim()?.ifBlank { null },
                category = request.category?.name ?: risk.category,
                probability = request.probability?.name ?: risk.probability,
                impact = request.impact?.name ?: risk.impact,
                criticality = request.criticality?.name ?: risk.criticality,
                responsibleId = request.responsibleId ?: risk.responsibleId,
                responsePlan = responsePlan,
                dueDate = if (request.dueDate == null) risk.dueDate
                else parseDate(request.dueDate, "dueDate"),
                stageId = stageId,
                taskId = taskId,
                expectedVersion = request.version
            ) ?: throw ConflictException("Risk was modified concurrently")

            auditService.log(
                currentUser.userId,
                "UPDATE",
                "PROJECT_RISK",
                updated.id,
                mapOf("projectId" to updated.projectId, "criticality" to updated.criticality)
            )
            updated.toDto()
        })
    }

    /**
     * Перевод риска в работу: `MITIGATE` -> MITIGATING, `REOPEN` -> OPEN.
     * Закрытие и принятие — отдельной командой, потому что требуют исхода.
     */
    fun commandRisk(
        riskId: Long,
        command: String,
        request: ProjectMilestoneCommandRequest,
        currentUser: CurrentUser
    ): ProjectRiskDto {
        return dsl.transactionResult(TransactionalCallable {
            val risk = planRepository.findRiskById(riskId) ?: throw NotFoundException("project_risk", riskId)
            requireManageableProject(risk.projectId, currentUser)
            val status = when (command) {
                "MITIGATE" -> if (risk.status == "OPEN" || risk.status == "MITIGATING") "MITIGATING" else null
                "REOPEN" -> if (risk.status == "CLOSED" || risk.status == "ACCEPTED") "OPEN" else null
                else -> throw ValidationException("Unknown risk command", mapOf("command" to "unknown"))
            } ?: throw ConflictException("Risk status ${risk.status} does not allow this command")

            val updated = planRepository.updateRiskStatus(
                id = riskId,
                status = status,
                outcome = if (status == "OPEN") null else risk.outcome,
                closedAt = if (status == "OPEN") null else risk.closedAt,
                closedBy = if (status == "OPEN") null else risk.closedBy,
                expectedVersion = request.version
            ) ?: throw ConflictException("Risk was modified concurrently")

            auditService.log(
                currentUser.userId,
                "RISK_$command",
                "PROJECT_RISK",
                updated.id,
                mapOf("projectId" to updated.projectId, "status" to status)
            )
            updated.toDto()
        })
    }

    /**
     * Закрытие риска: обязателен исход («риск снят» и «риск реализовался» —
     * разные ситуации, и без текста их не различить). Принятый риск закрывается
     * со статусом ACCEPTED.
     */
    fun closeRisk(
        riskId: Long,
        request: CloseProjectRiskRequest,
        currentUser: CurrentUser
    ): ProjectRiskDto {
        if (request.status != ProjectRiskStatus.CLOSED && request.status != ProjectRiskStatus.ACCEPTED) {
            throw ValidationException(
                "Risk can only be closed or accepted",
                mapOf("status" to "must_be_closed_or_accepted")
            )
        }
        val outcome = requireName(request.outcome, "outcome")
        return dsl.transactionResult(TransactionalCallable {
            val risk = planRepository.findRiskById(riskId) ?: throw NotFoundException("project_risk", riskId)
            requireManageableProject(risk.projectId, currentUser)
            if (risk.status == "CLOSED" || risk.status == "ACCEPTED") {
                throw ConflictException("Risk is already ${risk.status}")
            }
            val updated = planRepository.updateRiskStatus(
                id = riskId,
                status = request.status.name,
                outcome = outcome,
                closedAt = OffsetDateTime.now(),
                closedBy = currentUser.userId,
                expectedVersion = request.version
            ) ?: throw ConflictException("Risk was modified concurrently")

            auditService.log(
                currentUser.userId,
                "RISK_${request.status.name}",
                "PROJECT_RISK",
                updated.id,
                mapOf("projectId" to updated.projectId, "outcome" to outcome)
            )
            updated.toDto()
        })
    }

    /**
     * Удаление риска — только пока он открыт и не имеет исхода: закрытый риск
     * остаётся в истории проекта.
     */
    fun deleteRisk(riskId: Long, version: Long, currentUser: CurrentUser) {
        dsl.transactionResult(TransactionalCallable {
            val risk = planRepository.findRiskById(riskId) ?: throw NotFoundException("project_risk", riskId)
            requireManageableProject(risk.projectId, currentUser)
            if (risk.status == "CLOSED" || risk.status == "ACCEPTED") {
                throw ConflictException("Closed risk cannot be deleted")
            }
            if (planRepository.deleteRisk(riskId, version) == 0) {
                throw ConflictException("Risk was modified concurrently")
            }
            auditService.log(
                currentUser.userId,
                "DELETE",
                "PROJECT_RISK",
                riskId,
                mapOf("projectId" to risk.projectId)
            )
        })
    }

    // --- Проверки ----------------------------------------------------------

    private fun validateRisk(
        title: String,
        responsePlan: String?,
        stageId: Long?,
        taskId: Long?,
        projectId: Long
    ) {
        requireName(title, "title")
        stageId?.let { requireStageOfProject(it, projectId) }
        if (taskId != null && !planRepository.taskBelongsToProject(taskId, projectId)) {
            throw ValidationException(
                "Task does not belong to this project",
                mapOf("taskId" to "not_in_project")
            )
        }
        // Критичный риск без плана реагирования остаётся допустимым: план может
        // появиться позже, а запрет заставлял бы писать формальный текст.
        if (responsePlan != null && responsePlan.isBlank() && responsePlan.isNotEmpty()) {
            throw ValidationException("Response plan must not be blank", mapOf("responsePlan" to "blank"))
        }
    }

    private fun requireReadableProject(projectId: Long, currentUser: CurrentUser): ProjectsRecord {
        val project = projectRepository.findById(projectId) ?: throw NotFoundException("project", projectId)
        val allowed = currentUser.hasPermission("projects", "read_any") ||
            project.ownerId == currentUser.userId ||
            projectRepository.isMember(projectId, currentUser.userId)
        if (!allowed) throw ForbiddenException("Access to project plan denied")
        return project
    }

    private fun requireManageableProject(projectId: Long, currentUser: CurrentUser): ProjectsRecord {
        val project = requireReadableProject(projectId, currentUser)
        val allowed = project.ownerId == currentUser.userId ||
            currentUser.hasPermission("projects", "manage_plan")
        if (!allowed) throw ForbiddenException("Only project owner or authorized user can manage the plan")
        return project
    }

    /**
     * Закрытый или отменённый проект не принимает новых элементов плана.
     * Отдельного правила «нельзя закрыть проект с открытыми этапами» здесь нет:
     * по 5.6 оно требует решения, а придумывать его нельзя.
     */
    private fun requireNotClosed(project: ProjectsRecord, entity: String) {
        if (project.status == "CLOSED" || project.status == "CANCELLED") {
            throw ConflictException("Cannot change project $entity of a closed project")
        }
    }

    private fun requireStageOfProject(stageId: Long, projectId: Long) {
        val stage = planRepository.findStageById(stageId)
            ?: throw NotFoundException("project_stage", stageId)
        if (stage.projectId != projectId) {
            throw ValidationException(
                "Stage belongs to another project",
                mapOf("stageId" to "another_project")
            )
        }
    }

    private fun requireUser(userId: Long) {
        if (!projectRepository.userExists(userId)) throw NotFoundException("user", userId)
    }

    private fun requireName(value: String, field: String): String {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) {
            throw ValidationException("Field must not be blank", mapOf(field to "required"))
        }
        if (trimmed.length > 255) {
            throw ValidationException("Field is too long", mapOf(field to "too_long"))
        }
        return trimmed
    }

    private fun parseDateTime(value: String?, field: String): OffsetDateTime? {
        if (value.isNullOrBlank()) return null
        return try {
            OffsetDateTime.parse(value.trim())
        } catch (_: DateTimeParseException) {
            throw ValidationException("Invalid date-time", mapOf(field to "invalid"))
        }
    }

    private fun parsePeriod(start: String?, end: String?): Pair<OffsetDateTime?, OffsetDateTime?> {
        val parsedStart = parseDateTime(start, "plannedStartAt")
        val parsedEnd = parseDateTime(end, "plannedEndAt")
        requirePeriod(parsedStart, parsedEnd)
        return parsedStart to parsedEnd
    }

    private fun requirePeriod(start: OffsetDateTime?, end: OffsetDateTime?) {
        if (start != null && end != null && start.isAfter(end)) {
            throw ValidationException(
                "Planned start must not be after planned end",
                mapOf("plannedEndAt" to "before_start")
            )
        }
    }

    private fun parseDate(value: String?, field: String): LocalDate? {
        if (value.isNullOrBlank()) return null
        return try {
            LocalDate.parse(value.trim())
        } catch (_: DateTimeParseException) {
            throw ValidationException("Invalid date", mapOf(field to "invalid"))
        }
    }

    /** Локальный носитель четырёх значений перехода статуса этапа. */
    private data class Quad(
        val status: String,
        val actualStartAt: OffsetDateTime?,
        val actualEndAt: OffsetDateTime?,
        val cancelReason: String?
    )

    private fun ProjectStagesRecord.toDto(milestones: List<ProjectMilestoneDto>) = ProjectStageDto(
        id = id,
        projectId = projectId,
        name = name,
        description = description,
        sequence = sequence,
        status = ProjectStageStatus.valueOf(status),
        plannedStartAt = plannedStartAt?.toString(),
        plannedEndAt = plannedEndAt?.toString(),
        actualStartAt = actualStartAt?.toString(),
        actualEndAt = actualEndAt?.toString(),
        responsibleId = responsibleId,
        completionCriteria = completionCriteria,
        cancelReason = cancelReason,
        createdAt = createdAt.toString(),
        createdBy = createdBy,
        version = version,
        milestones = milestones
    )

    private fun ProjectMilestonesRecord.toDto() = ProjectMilestoneDto(
        id = id,
        projectId = projectId,
        stageId = stageId,
        name = name,
        description = description,
        plannedAt = plannedAt?.toString(),
        actualAt = actualAt?.toString(),
        status = ProjectMilestoneStatus.valueOf(status),
        responsibleId = responsibleId,
        note = note,
        createdAt = createdAt.toString(),
        createdBy = createdBy,
        version = version
    )

    private fun ProjectRisksRecord.toDto() = ProjectRiskDto(
        id = id,
        projectId = projectId,
        title = title,
        description = description,
        category = ProjectRiskCategory.valueOf(category),
        probability = ProjectRiskLevel.valueOf(probability),
        impact = ProjectRiskLevel.valueOf(impact),
        criticality = ProjectRiskCriticality.valueOf(criticality),
        status = ProjectRiskStatus.valueOf(status),
        responsibleId = responsibleId,
        responsePlan = responsePlan,
        dueDate = dueDate?.toString(),
        stageId = stageId,
        taskId = taskId,
        outcome = outcome,
        closedAt = closedAt?.toString(),
        closedBy = closedBy,
        createdAt = createdAt.toString(),
        createdBy = createdBy,
        version = version
    )
}
