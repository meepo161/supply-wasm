package org.example.project.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import org.example.project.dto.ApiError
import org.example.project.dto.ApiResponse
import org.example.project.dto.AssignTaskRequest
import org.example.project.dto.BlockTaskRequest
import org.example.project.dto.CancelTaskRequest
import org.example.project.dto.CreateTaskRequest
import org.example.project.dto.ReopenTaskRequest
import org.example.project.dto.ReviewTaskRequest
import org.example.project.dto.SubmitResultRequest
import org.example.project.dto.TaskCommandRequest
import org.example.project.dto.TaskDto

class TaskApiClient(private val client: HttpClient) : TaskRepository {

    override suspend fun list(): ApiResult<List<TaskDto>> = get("/api/tasks")

    override suspend fun get(id: Long): ApiResult<TaskDto> = get("/api/tasks/$id")

    override suspend fun create(request: CreateTaskRequest): ApiResult<TaskDto> =
        post("/api/tasks", request)

    override suspend fun assign(id: Long, request: AssignTaskRequest): ApiResult<TaskDto> =
        post("/api/tasks/$id/assign", request)

    override suspend fun accept(id: Long, request: TaskCommandRequest): ApiResult<TaskDto> =
        post("/api/tasks/$id/accept", request)

    override suspend fun submitResult(id: Long, request: SubmitResultRequest): ApiResult<TaskDto> =
        post("/api/tasks/$id/result", request)

    override suspend fun sendToReview(id: Long, request: TaskCommandRequest): ApiResult<TaskDto> =
        post("/api/tasks/$id/send-to-review", request)

    override suspend fun review(id: Long, request: ReviewTaskRequest): ApiResult<TaskDto> =
        post("/api/tasks/$id/review", request)

    override suspend fun close(id: Long, request: TaskCommandRequest): ApiResult<TaskDto> =
        post("/api/tasks/$id/close", request)

    override suspend fun block(id: Long, request: BlockTaskRequest): ApiResult<TaskDto> =
        post("/api/tasks/$id/block", request)

    override suspend fun unblock(id: Long, request: TaskCommandRequest): ApiResult<TaskDto> =
        post("/api/tasks/$id/unblock", request)

    override suspend fun reopen(id: Long, request: ReopenTaskRequest): ApiResult<TaskDto> =
        post("/api/tasks/$id/reopen", request)

    override suspend fun cancel(id: Long, request: CancelTaskRequest): ApiResult<TaskDto> =
        post("/api/tasks/$id/cancel", request)

    private suspend inline fun <reified T> get(path: String): ApiResult<T> =
        execute { client.get(path) }

    private suspend inline fun <reified T> post(path: String, body: Any): ApiResult<T> =
        execute {
            client.post(path) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }

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
