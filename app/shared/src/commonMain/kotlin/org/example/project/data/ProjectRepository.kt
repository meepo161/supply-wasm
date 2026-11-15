package org.example.project.data

import org.example.project.dto.AddProjectMemberRequest
import org.example.project.dto.CloseProjectRiskRequest
import org.example.project.dto.CreateProjectMilestoneRequest
import org.example.project.dto.CreateProjectRiskRequest
import org.example.project.dto.CreateProjectStageRequest
import org.example.project.dto.ProjectMilestoneCommandRequest
import org.example.project.dto.ProjectMilestoneDto
import org.example.project.dto.ProjectMilestoneStatus
import org.example.project.dto.ProjectPlanSummaryDto
import org.example.project.dto.ProjectRiskDto
import org.example.project.dto.ProjectRiskStatus
import org.example.project.dto.ProjectStageCommandRequest
import org.example.project.dto.ProjectStageDto
import org.example.project.dto.ProjectStageStatus
import org.example.project.dto.UpdateProjectMilestoneRequest
import org.example.project.dto.UpdateProjectRiskRequest
import org.example.project.dto.UpdateProjectStageRequest
import org.example.project.dto.DemandHeaderDto
import org.example.project.dto.CloseProjectRequest
import org.example.project.dto.CreateProjectRequest
import org.example.project.dto.OpenProjectRequest
import org.example.project.dto.DocumentDto
import org.example.project.dto.ProjectDto
import org.example.project.dto.ProjectHistoryEntryDto
import org.example.project.dto.ProjectSummaryDto
import org.example.project.dto.TaskDto
import org.example.project.dto.TaskStatus

interface ProjectRepository {
    suspend fun list(): ApiResult<List<ProjectDto>>
    suspend fun get(id: Long): ApiResult<ProjectDto>
    suspend fun create(request: CreateProjectRequest): ApiResult<ProjectDto>
    suspend fun open(id: Long, request: OpenProjectRequest): ApiResult<ProjectDto>
    suspend fun close(id: Long, request: CloseProjectRequest): ApiResult<ProjectDto>
    suspend fun addMember(id: Long, request: AddProjectMemberRequest): ApiResult<ProjectDto>
    suspend fun removeMember(id: Long, userId: Long): ApiResult<ProjectDto>

    // Вкладки карточки проекта (PRJ-13/PRJ-14): фильтрация на сервере.
    suspend fun summary(id: Long): ApiResult<ProjectSummaryDto>
    suspend fun history(id: Long, limit: Int? = null): ApiResult<List<ProjectHistoryEntryDto>>
    suspend fun tasks(id: Long, status: TaskStatus? = null): ApiResult<List<TaskDto>>
    suspend fun demands(id: Long): ApiResult<List<DemandHeaderDto>>
    suspend fun documents(id: Long): ApiResult<List<DocumentDto>>

    // План проекта: этапы, контрольные точки и риски (PRJ-08/09/10).
    suspend fun stages(projectId: Long, status: ProjectStageStatus? = null): ApiResult<List<ProjectStageDto>>
    suspend fun createStage(projectId: Long, request: CreateProjectStageRequest): ApiResult<ProjectStageDto>
    suspend fun updateStage(stageId: Long, request: UpdateProjectStageRequest): ApiResult<ProjectStageDto>
    suspend fun stageCommand(
        stageId: Long,
        command: ProjectStageCommand,
        request: ProjectStageCommandRequest
    ): ApiResult<ProjectStageDto>
    suspend fun deleteStage(stageId: Long, version: Long): ApiResult<Unit>

    suspend fun milestones(
        projectId: Long,
        stageId: Long? = null,
        status: ProjectMilestoneStatus? = null
    ): ApiResult<List<ProjectMilestoneDto>>
    suspend fun createMilestone(
        projectId: Long,
        request: CreateProjectMilestoneRequest
    ): ApiResult<ProjectMilestoneDto>
    suspend fun updateMilestone(
        milestoneId: Long,
        request: UpdateProjectMilestoneRequest
    ): ApiResult<ProjectMilestoneDto>
    suspend fun milestoneCommand(
        milestoneId: Long,
        command: ProjectMilestoneCommand,
        request: ProjectMilestoneCommandRequest
    ): ApiResult<ProjectMilestoneDto>
    suspend fun deleteMilestone(milestoneId: Long, version: Long): ApiResult<Unit>

    suspend fun risks(projectId: Long, status: ProjectRiskStatus? = null): ApiResult<List<ProjectRiskDto>>
    suspend fun createRisk(projectId: Long, request: CreateProjectRiskRequest): ApiResult<ProjectRiskDto>
    suspend fun updateRisk(riskId: Long, request: UpdateProjectRiskRequest): ApiResult<ProjectRiskDto>
    suspend fun mitigateRisk(riskId: Long, version: Long): ApiResult<ProjectRiskDto>
    suspend fun reopenRisk(riskId: Long, version: Long): ApiResult<ProjectRiskDto>
    suspend fun closeRisk(riskId: Long, request: CloseProjectRiskRequest): ApiResult<ProjectRiskDto>
    suspend fun deleteRisk(riskId: Long, version: Long): ApiResult<Unit>

    suspend fun planSummary(projectId: Long): ApiResult<ProjectPlanSummaryDto>
}

/** Команды этапа: сервер принимает только эти три перехода. */
enum class ProjectStageCommand(val path: String) {
    START("start"),
    COMPLETE("complete"),
    CANCEL("cancel")
}

/** Команды контрольной точки. */
enum class ProjectMilestoneCommand(val path: String) {
    REACH("reach"),
    MISS("miss")
}
