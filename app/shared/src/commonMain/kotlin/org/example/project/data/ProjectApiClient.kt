package org.example.project.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.http.ContentType
import io.ktor.http.contentType
import org.example.project.dto.AddProjectMemberRequest
import org.example.project.dto.ApiError
import org.example.project.dto.ApiResponse
import org.example.project.dto.CloseProjectRequest
import org.example.project.dto.CreateProjectRequest
import org.example.project.dto.OpenProjectRequest
import org.example.project.dto.DemandHeaderDto
import org.example.project.dto.DocumentDto
import org.example.project.dto.ProjectDto
import org.example.project.dto.ProjectHistoryEntryDto
import org.example.project.dto.ProjectSummaryDto
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

    override suspend fun documents(id: Long): ApiResult<List<DocumentDto>> =
        get("/api/projects/$id/documents")

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
