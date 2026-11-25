package org.example.project.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.http.ContentType
import io.ktor.http.contentType
import org.example.project.dto.AddProjectMemberRequest
import org.example.project.dto.ApiError
import org.example.project.dto.ApiResponse
import org.example.project.dto.CloseProjectRequest
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
import org.example.project.dto.CreateProjectRequest
import org.example.project.dto.OpenProjectRequest
import org.example.project.dto.DemandDetailDto
import org.example.project.dto.DemandHeaderDto
import org.example.project.dto.DocumentDto
import org.example.project.dto.HandoffProjectSpecRequest
import org.example.project.dto.ProjectDto
import org.example.project.dto.ProjectHistoryEntryDto
import org.example.project.dto.ProjectSummaryDto
import org.example.project.dto.ProjectSupplyOverviewDto
import org.example.project.dto.TaskDto
import org.example.project.dto.TaskStatus

class ProjectApiClient(private val client: HttpClient) : ProjectRepository {

    override suspend fun list(): ApiResult<List<ProjectDto>> = get("/api/projects")

    override suspend fun get(id: Long): ApiResult<ProjectDto> = get("/api/projects/$id")

    override suspend fun create(request: CreateProjectRequest): ApiResult<ProjectDto> =
        post("/api/projects", request)

    override suspend fun open(id: Long, request: OpenProjectRequest): ApiResult<ProjectDto> =
        post("/api/projects/$id/open", request)

    override suspend fun close(id: Long, request: CloseProjectRequest): ApiResult<ProjectDto> =
        post("/api/projects/$id/close", request)

    override suspend fun addMember(id: Long, request: AddProjectMemberRequest): ApiResult<ProjectDto> =
        post("/api/projects/$id/members", request)

    override suspend fun removeMember(id: Long, userId: Long): ApiResult<ProjectDto> =
        delete("/api/projects/$id/members/$userId")

    override suspend fun summary(id: Long): ApiResult<ProjectSummaryDto> =
        get("/api/projects/$id/summary")

    override suspend fun history(id: Long, limit: Int?): ApiResult<List<ProjectHistoryEntryDto>> =
        getWithParams("/api/projects/$id/history", buildMap { limit?.let { put("limit", it.toString()) } })

    override suspend fun tasks(id: Long, status: TaskStatus?): ApiResult<List<TaskDto>> =
        getWithParams("/api/projects/$id/tasks", buildMap { status?.let { put("status", it.name) } })

    override suspend fun demands(id: Long): ApiResult<List<DemandHeaderDto>> =
        get("/api/projects/$id/demands")

    override suspend fun getSupplyOverview(projectId: Long): ApiResult<ProjectSupplyOverviewDto> =
        get("/api/projects/$projectId/supply-overview")

    override suspend fun handoffSpecification(
        projectId: Long,
        request: HandoffProjectSpecRequest
    ): ApiResult<DemandDetailDto> = post("/api/projects/$projectId/handoff-specification", request)

    override suspend fun documents(id: Long): ApiResult<List<DocumentDto>> =
        get("/api/projects/$id/documents")

    // --- План проекта (PRJ-08/09/10) --------------------------------------

    override suspend fun stages(
        projectId: Long,
        status: ProjectStageStatus?
    ): ApiResult<List<ProjectStageDto>> =
        getWithParams("/api/projects/$projectId/stages", statusParams(status?.name))

    override suspend fun createStage(
        projectId: Long,
        request: CreateProjectStageRequest
    ): ApiResult<ProjectStageDto> = post("/api/projects/$projectId/stages", request)

    override suspend fun updateStage(
        stageId: Long,
        request: UpdateProjectStageRequest
    ): ApiResult<ProjectStageDto> = put("/api/project-stages/$stageId", request)

    override suspend fun stageCommand(
        stageId: Long,
        command: ProjectStageCommand,
        request: ProjectStageCommandRequest
    ): ApiResult<ProjectStageDto> = post("/api/project-stages/$stageId/${command.path}", request)

    override suspend fun deleteStage(stageId: Long, version: Long): ApiResult<Unit> =
        deleteWithParams("/api/project-stages/$stageId", mapOf("version" to version.toString()))

    override suspend fun milestones(
        projectId: Long,
        stageId: Long?,
        status: ProjectMilestoneStatus?
    ): ApiResult<List<ProjectMilestoneDto>> = getWithParams(
        "/api/projects/$projectId/milestones",
        buildMap {
            stageId?.let { put("stageId", it.toString()) }
            status?.let { put("status", it.name) }
        }
    )

    override suspend fun createMilestone(
        projectId: Long,
        request: CreateProjectMilestoneRequest
    ): ApiResult<ProjectMilestoneDto> = post("/api/projects/$projectId/milestones", request)

    override suspend fun updateMilestone(
        milestoneId: Long,
        request: UpdateProjectMilestoneRequest
    ): ApiResult<ProjectMilestoneDto> = put("/api/project-milestones/$milestoneId", request)

    override suspend fun milestoneCommand(
        milestoneId: Long,
        command: ProjectMilestoneCommand,
        request: ProjectMilestoneCommandRequest
    ): ApiResult<ProjectMilestoneDto> =
        post("/api/project-milestones/$milestoneId/${command.path}", request)

    override suspend fun deleteMilestone(milestoneId: Long, version: Long): ApiResult<Unit> =
        deleteWithParams("/api/project-milestones/$milestoneId", mapOf("version" to version.toString()))

    override suspend fun risks(
        projectId: Long,
        status: ProjectRiskStatus?
    ): ApiResult<List<ProjectRiskDto>> =
        getWithParams("/api/projects/$projectId/risks", statusParams(status?.name))

    override suspend fun createRisk(
        projectId: Long,
        request: CreateProjectRiskRequest
    ): ApiResult<ProjectRiskDto> = post("/api/projects/$projectId/risks", request)

    override suspend fun updateRisk(
        riskId: Long,
        request: UpdateProjectRiskRequest
    ): ApiResult<ProjectRiskDto> = put("/api/project-risks/$riskId", request)

    override suspend fun mitigateRisk(riskId: Long, version: Long): ApiResult<ProjectRiskDto> =
        post("/api/project-risks/$riskId/mitigate", ProjectMilestoneCommandRequest(version))

    override suspend fun reopenRisk(riskId: Long, version: Long): ApiResult<ProjectRiskDto> =
        post("/api/project-risks/$riskId/reopen", ProjectMilestoneCommandRequest(version))

    override suspend fun closeRisk(
        riskId: Long,
        request: CloseProjectRiskRequest
    ): ApiResult<ProjectRiskDto> = post("/api/project-risks/$riskId/close", request)

    override suspend fun deleteRisk(riskId: Long, version: Long): ApiResult<Unit> =
        deleteWithParams("/api/project-risks/$riskId", mapOf("version" to version.toString()))

    override suspend fun planSummary(projectId: Long): ApiResult<ProjectPlanSummaryDto> =
        get("/api/projects/$projectId/plan-summary")

    private fun statusParams(value: String?): Map<String, String> =
        value?.let { mapOf("status" to it) } ?: emptyMap()

    private suspend inline fun <reified T> put(path: String, body: Any): ApiResult<T> =
        execute {
            client.put(path) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }

    /**
     * DELETE отвечает 204 без тела, поэтому разбор ответа отличается от общего
     * `execute`: успех определяется по статусу, а текст ошибки берётся из тела,
     * если сервер его прислал.
     */
    private suspend fun deleteWithParams(path: String, params: Map<String, String>): ApiResult<Unit> =
        try {
            val response = client.delete(path) {
                url { params.forEach { (key, value) -> parameters.append(key, value) } }
            }
            if (response.status.value in 200..299) {
                ApiResult.Success(Unit)
            } else {
                val error = try {
                    response.body<ApiResponse<Unit>>().error
                } catch (_: Exception) {
                    null
                }
                ApiResult.Error(
                    error ?: ApiError("UNKNOWN_ERROR", "Unexpected server response"),
                    response.status.value
                )
            }
        } catch (e: HttpRequestTimeoutException) {
            ApiResult.Error(networkError(e.message ?: "Request timeout"))
        } catch (e: ClientRequestException) {
            ApiResult.Error(networkError(e.message))
        } catch (e: Exception) {
            ApiResult.Error(networkError(e.message ?: "Unexpected error"))
        }

    private suspend inline fun <reified T> getWithParams(
        path: String,
        params: Map<String, String>
    ): ApiResult<T> = execute {
        client.get(path) {
            url { params.forEach { (key, value) -> parameters.append(key, value) } }
        }
    }

    private suspend inline fun <reified T> get(path: String): ApiResult<T> =
        execute { client.get(path) }

    private suspend inline fun <reified T> post(path: String, body: Any): ApiResult<T> =
        execute {
            client.post(path) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }

    private suspend inline fun <reified T> delete(path: String): ApiResult<T> =
        execute { client.delete(path) }

    private suspend inline fun <reified T> execute(
        block: () -> io.ktor.client.statement.HttpResponse
    ): ApiResult<T> =
        try {
            val response = block()
            parseResponse(response.status.value, response.body())
        } catch (e: HttpRequestTimeoutException) {
            ApiResult.Error(networkError(e.message ?: "Request timeout"))
        } catch (e: ClientRequestException) {
            ApiResult.Error(networkError(e.message))
        } catch (e: Exception) {
            ApiResult.Error(networkError(e.message ?: "Unexpected error"))
        }

    private inline fun <reified T> parseResponse(statusCode: Int, body: ApiResponse<T>): ApiResult<T> {
        val data = body.data
        val error = body.error
        return when {
            body.success && data != null -> ApiResult.Success(data)
            error != null -> ApiResult.Error(error, statusCode)
            statusCode in 200..299 -> ApiResult.Error(
                ApiError("EMPTY_RESPONSE", "Empty successful response"),
                statusCode
            )
            else -> ApiResult.Error(
                ApiError("UNKNOWN_ERROR", "Unexpected server response"),
                statusCode
            )
        }
    }

    private fun networkError(message: String): ApiError =
        ApiError("NETWORK_ERROR", message)
}
