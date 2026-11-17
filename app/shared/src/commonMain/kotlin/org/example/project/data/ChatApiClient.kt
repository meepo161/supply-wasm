package org.example.project.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.delete
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.readReason
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.example.project.dto.ApiError
import org.example.project.dto.ApiResponse
import org.example.project.dto.ChatMessageDto
import org.example.project.dto.ChatMessagePageDto
import org.example.project.dto.ChatSearchResultDto
import org.example.project.dto.MarkChatReadRequest
import org.example.project.dto.ReactionRequest
import org.example.project.dto.SendChatMessageRequest
import org.example.project.dto.TaskChatListFilter
import org.example.project.dto.TaskChatListItemDto

class ChatApiClient(private val client: HttpClient) : ChatRepository {

    private val realtimeJson = Json { ignoreUnknownKeys = true }

    override fun observeMessages(taskId: Long, after: Long?): Flow<ChatMessageDto> =
        observeEvents(taskId, after, emptyFlow())
            .mapNotNull { (it as? TaskChatRealtimeEvent.Message)?.message }

    override fun observeEvents(taskId: Long, after: Long?, outgoing: Flow<String>): Flow<TaskChatRealtimeEvent> = flow {
        client.webSocket(
            urlString = "/api/tasks/$taskId/chat/ws",
            request = { after?.let { parameter("after", it) } }
        ) {
            val sender = launch {
                outgoing.collect { send(Frame.Text(it)) }
            }
            try {
                for (frame in incoming) {
                    when (frame) {
                        is Frame.Text ->
                            parseTaskChatRealtimeEvent(realtimeJson, frame.data.decodeToString(), taskId)
                                ?.let { emit(it) }
                        is Frame.Close -> {
                            if (frame.readReason()?.code == CloseReason.Codes.VIOLATED_POLICY.code) {
                                throw ChatAccessRevokedException()
                            }
                        }
                        else -> Unit
                    }
                }
            } finally {
                sender.cancel()
            }
        }
    }

    override suspend fun listChatTasks(
        query: String?,
        filter: TaskChatListFilter,
        limit: Int
    ): ApiResult<List<TaskChatListItemDto>> =
        execute {
            client.get("/api/tasks/chat/list") {
                parameter("limit", limit)
                parameter("filter", filter.name)
                query?.takeIf { it.isNotBlank() }?.let { parameter("query", it) }
            }
        }

    override suspend fun markChatRead(taskId: Long, lastReadMessageId: Long): ApiResult<Boolean> =
        executeEmpty {
            client.post("/api/tasks/$taskId/chat/read") {
                contentType(ContentType.Application.Json)
                setBody(MarkChatReadRequest(lastReadMessageId))
            }
        }

    override suspend fun setChatReaction(
        taskId: Long,
        messageId: Long,
        emoji: String,
        active: Boolean
    ): ApiResult<Boolean> {
        val path = "/api/tasks/$taskId/chat/messages/$messageId/reactions/${emoji.encodeURLPathPart()}"
        return executeEmpty {
            if (active) {
                client.put(path) {
                    contentType(ContentType.Application.Json)
                    setBody(ReactionRequest(emoji))
                }
            } else {
                client.delete(path)
            }
        }
    }

    override suspend fun setChatPinned(taskId: Long, pinned: Boolean): ApiResult<Boolean> =
        executeEmpty {
            if (pinned) client.put("/api/tasks/$taskId/chat/pin") else client.delete("/api/tasks/$taskId/chat/pin")
        }

    override suspend fun searchChatMessages(
        taskId: Long,
        query: String,
        limit: Int
    ): ApiResult<List<ChatSearchResultDto>> =
        execute {
            client.get("/api/tasks/$taskId/chat/search") {
                parameter("query", query)
                parameter("limit", limit)
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
        request: SendChatMessageRequest,
        file: AttachmentFileData?
    ): ApiResult<ChatMessageDto> =
        execute {
            if (file == null) {
                client.post("/api/tasks/$taskId/chat/messages") {
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }
            } else {
                client.submitFormWithBinaryData(
                    url = "/api/tasks/$taskId/chat/messages",
                    formData = messageFormData(
                        request.content,
                        request.idempotencyKey,
                        file,
                        request.replyToMessageId
                    )
                )
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

    private suspend inline fun executeEmpty(
        block: () -> io.ktor.client.statement.HttpResponse
    ): ApiResult<Boolean> =
        try {
            val response = block()
            if (response.status.value in 200..299) {
                ApiResult.Success(true)
            } else {
                val body = runCatching { response.body<ApiResponse<JsonElement>>() }.getOrNull()
                ApiResult.Error(
                    body?.error ?: ApiError("UNKNOWN_ERROR", "Unexpected server response"),
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
