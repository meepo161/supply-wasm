package org.example.project.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.readReason
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import org.example.project.dto.ApiError
import org.example.project.dto.ApiResponse
import org.example.project.dto.NotificationDto
import org.example.project.dto.NotificationPageDto

class NotificationApiClient(private val client: HttpClient) : NotificationRepository {

    private val realtimeJson = Json { ignoreUnknownKeys = true }

    override fun observeNotifications(after: Long?): Flow<NotificationDto> = flow {
        client.webSocket(
            urlString = "/api/notifications/ws",
            request = { after?.let { parameter("after", it) } }
        ) {
            for (frame in incoming) {
                when (frame) {
                    is Frame.Text ->
                        parseNotificationRealtimeMessage(realtimeJson, frame.data.decodeToString())?.let { emit(it) }
                    is Frame.Close -> {
                        if (frame.readReason()?.code == CloseReason.Codes.VIOLATED_POLICY.code) {
                            throw NotificationStreamEndedException()
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    override suspend fun listNotifications(
        limit: Int,
        after: Long?,
        before: Long?
    ): ApiResult<NotificationPageDto> = getWithParams(
        path = "/api/notifications",
        params = buildMap {
            put("limit", limit.coerceIn(1, 200).toString())
            after?.let { put("after", it.toString()) }
            before?.let { put("before", it.toString()) }
        }
    )

    override suspend fun markRead(id: Long): ApiResult<NotificationDto> =
        post("/api/notifications/$id/read")

    override suspend fun markAllRead(): ApiResult<Map<String, Int>> =
        post("/api/notifications/read-all")

    private suspend inline fun <reified T> getWithParams(
        path: String,
        params: Map<String, String>
    ): ApiResult<T> =
        execute {
            client.get(path) {
                url {
                    params.forEach { (key, value) ->
                        parameters.append(key, value)
                    }
                }
            }
        }

    private suspend inline fun <reified T> post(path: String): ApiResult<T> =
        execute { client.post(path) }

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
