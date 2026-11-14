package org.example.project.data

import org.example.project.dto.AddProjectMemberRequest
import org.example.project.dto.CloseProjectRequest
import org.example.project.dto.CreateProjectRequest
import org.example.project.dto.OpenProjectRequest
import org.example.project.dto.ProjectDto

interface ProjectRepository {
    suspend fun list(): ApiResult<List<ProjectDto>>
    suspend fun get(id: Long): ApiResult<ProjectDto>
    suspend fun create(request: CreateProjectRequest): ApiResult<ProjectDto>
    suspend fun open(id: Long, request: OpenProjectRequest): ApiResult<ProjectDto>
    suspend fun close(id: Long, request: CloseProjectRequest): ApiResult<ProjectDto>
    suspend fun addMember(id: Long, request: AddProjectMemberRequest): ApiResult<ProjectDto>
    suspend fun removeMember(id: Long, userId: Long): ApiResult<ProjectDto>
}
