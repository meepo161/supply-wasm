package org.example.project.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.readReason
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import org.example.project.dto.ApiError
import org.example.project.dto.ApiResponse
import org.example.project.dto.ChatMessageDto
import org.example.project.dto.ChatMessagePageDto
import org.example.project.dto.SendChatMessageRequest

class ChatApiClient(private val client: HttpClient) : ChatRepository {

    private val realtimeJson = Json { ignoreUnknownKeys = true }

    override fun observeMessages(taskId: Long, after: Long?): Flow<ChatMessageDto> = flow {
        client.webSocket(
            urlString = "/api/tasks/$taskId/chat/ws",
            request = { after?.let { parameter("after", it) } }
        ) {
            for (frame in incoming) {
                when (frame) {
                    is Frame.Text ->
                        parseChatRealtimeMessage(realtimeJson, frame.data.decodeToString(), taskId)?.let { emit(it) }
                    is Frame.Close -> {
                        if (frame.readReason()?.code == CloseReason.Codes.VIOLATED_POLICY.code) {
                            throw ChatAccessRevokedException()
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    override suspend fun listMessages(
        taskId: Long,
        after: Long?,
        before: Long?,
        limit: Int
    ): ApiResult<ChatMessagePageDto> =
        execute {
            client.get("/api/tasks/$taskId/chat/messages") {
                parameter("limit", limit)
                after?.let { parameter("after", it) }
                before?.let { parameter("before", it) }
            }
        }

    override suspend fun sendMessage(
        taskId: Long,
        request: SendChatMessageRequest
    ): ApiResult<ChatMessageDto> =
        execute {
            client.post("/api/tasks/$taskId/chat/messages") {
                contentType(ContentType.Application.Json)
                setBody(request)
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
