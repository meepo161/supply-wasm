package org.example.project.data

import org.example.project.dto.AddProjectMemberRequest
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
}
