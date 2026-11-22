package org.example.project.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.websocket.webSocket
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.json.Json
import org.example.project.dto.ApiError
import org.example.project.dto.ApiResponse
import org.example.project.dto.ChatMessageDto
import org.example.project.dto.ChatMessagePageDto
import org.example.project.dto.ChatSearchResultDto
import org.example.project.dto.ConversationDialogStateRequest
import org.example.project.dto.ConversationDto
import org.example.project.dto.ConversationKind
import org.example.project.dto.ConversationListFilter
import org.example.project.dto.ConversationListItemDto
import org.example.project.dto.ConversationListPageDto
import org.example.project.dto.ConversationMessageDto
import org.example.project.dto.ConversationMessagePageDto
import org.example.project.dto.CreateConversationRequest
import org.example.project.dto.EditMessageRequest
import org.example.project.dto.ForwardMessageRequest
import org.example.project.dto.MarkChatReadRequest
import org.example.project.dto.ReactionRequest
import org.example.project.dto.ReactionSummaryDto
import org.example.project.dto.RevokeMessageRequest
import org.example.project.dto.SendChatMessageRequest

/**
 * Единый клиент разговоров. Отрицательный conversationId — виртуальный
 * задачный чат (-taskId): запросы уходят в существующий Task Chat API,
 * сообщения преобразуются в ConversationMessageDto.
 */
class ConversationApiClient(private val client: HttpClient) : ConversationRepository {

    private val realtimeJson = Json { ignoreUnknownKeys = true }

    override suspend fun list(): ApiResult<List<ConversationDto>> =
        execute { client.get("/api/conversations") }

    override suspend fun listWorkspace(
        query: String?,
        kind: ConversationKind?,
        filter: ConversationListFilter,
        cursor: String?,
        limit: Int
    ): ApiResult<ConversationListPageDto> = execute {
        client.get("/api/chat/workspace") {
            parameter("limit", limit)
            parameter("filter", filter.name)
            query?.takeIf { it.isNotBlank() }?.let { parameter("query", it) }
            kind?.let { parameter("kind", it.name) }
            cursor?.let { parameter("cursor", it) }
        }
    }

    suspend fun changeDialogState(
        kind: ConversationKind,
        id: Long,
        request: ConversationDialogStateRequest
    ): ApiResult<ConversationListItemDto> = execute {
        client.post("/api/chat/workspace/${kind.name}/$id/state") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
    }

    override suspend fun get(id: Long): ApiResult<ConversationDto> =
        execute { client.get("/api/conversations/$id") }

    override suspend fun create(request: CreateConversationRequest): ApiResult<ConversationDto> =
        execute {
            client.post("/api/conversations") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun getOrCreateProjectChat(projectId: Long): ApiResult<ConversationDto> =
        execute { client.post("/api/projects/$projectId/chat") }

    override suspend fun listMessages(
        conversationId: Long,
        after: Long?,
        before: Long?,
        limit: Int
    ): ApiResult<ConversationMessagePageDto> =
        if (conversationId < 0) {
            val taskId = -conversationId
            when (
                val result = execute<ChatMessagePageDto> {
                    client.get("/api/tasks/$taskId/chat/messages") {
                        parameter("limit", limit)
                        after?.let { parameter("after", it) }
                        before?.let { parameter("before", it) }
                    }
                }
            ) {
                is ApiResult.Success -> ApiResult.Success(
                    ConversationMessagePageDto(
                        messages = result.data.messages.map { it.toConversationMessage() },
                        nextAfter = result.data.nextAfter,
                        nextBefore = result.data.nextBefore,
                        hasMore = result.data.hasMore
                    )
                )
                is ApiResult.Error -> ApiResult.Error(result.error, result.statusCode)
            }
        } else {
            execute {
                client.get("/api/conversations/$conversationId/messages") {
                    parameter("limit", limit)
                    after?.let { parameter("after", it) }
                    before?.let { parameter("before", it) }
                }
            }
        }

    override suspend fun sendMessage(
        conversationId: Long,
        request: SendChatMessageRequest,
        file: AttachmentFileData?
    ): ApiResult<ConversationMessageDto> = sendMessage(conversationId, request, listOfNotNull(file))

    override suspend fun sendMessage(
        conversationId: Long,
        request: SendChatMessageRequest,
        files: List<AttachmentFileData>
    ): ApiResult<ConversationMessageDto> {
        if (conversationId < 0) {
            val result = execute<ChatMessageDto> { sendMessageHttp(conversationId, request, files) }
            return when (result) {
                is ApiResult.Success -> ApiResult.Success(result.data.toConversationMessage())
                is ApiResult.Error -> ApiResult.Error(result.error, result.statusCode)
            }
        }
        return execute { sendMessageHttp(conversationId, request, files) }
    }

    private suspend fun sendMessageHttp(
        conversationId: Long,
        request: SendChatMessageRequest,
        files: List<AttachmentFileData>
    ): io.ktor.client.statement.HttpResponse {
        val url = conversationMessagePath(conversationId)
        return if (files.isEmpty()) {
            client.post(url) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        } else {
            client.submitFormWithBinaryData(
                url = url,
                formData = messageFormData(
                    request.content,
                    request.idempotencyKey,
                    files,
                    request.replyToMessageId
                )
            )
        }
    }

    suspend fun toggleReaction(
        conversationId: Long,
        messageId: Long,
        emoji: String,
        kind: ConversationKind? = null
    ): ApiResult<List<ReactionSummaryDto>> = execute {
        if (kind == ConversationKind.TASK || conversationId < 0) {
            val taskId = if (conversationId < 0) -conversationId else conversationId
            client.put("/api/tasks/$taskId/chat/messages/$messageId/reactions/${emoji.encodeURLPathPart()}") {
                contentType(ContentType.Application.Json)
                setBody(ReactionRequest(emoji))
            }
        } else {
            client.post("/api/conversations/$conversationId/messages/$messageId/reactions") {
                contentType(ContentType.Application.Json)
                setBody(ReactionRequest(emoji))
            }
        }
    }

    suspend fun editMessage(
        conversationId: Long,
        messageId: Long,
        request: EditMessageRequest
    ): ApiResult<ConversationMessageDto> = if (conversationId < 0) {
        mapTaskMessage(execute<ChatMessageDto> {
            client.post("/api/tasks/${-conversationId}/chat/messages/$messageId/edit") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        })
    } else {
        execute {
            client.post("/api/conversations/$conversationId/messages/$messageId/edit") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }
    }

    suspend fun revokeMessage(
        conversationId: Long,
        messageId: Long,
        version: Long? = null
    ): ApiResult<ConversationMessageDto> = if (conversationId < 0) {
        mapTaskMessage(execute<ChatMessageDto> {
            client.post("/api/tasks/${-conversationId}/chat/messages/$messageId/revoke") {
                contentType(ContentType.Application.Json)
                setBody(RevokeMessageRequest(version))
            }
        })
    } else {
        execute { client.post("/api/conversations/$conversationId/messages/$messageId/revoke") }
    }

    suspend fun forwardMessage(
        conversationId: Long,
        messageId: Long,
        request: ForwardMessageRequest
    ): ApiResult<ConversationMessageDto> = if (conversationId < 0) {
        mapTaskMessage(execute<ChatMessageDto> {
            client.post("/api/tasks/${-conversationId}/chat/messages/$messageId/forward") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        })
    } else {
        execute {
            client.post("/api/conversations/$conversationId/messages/$messageId/forward") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }
    }

    override suspend fun markRead(conversationId: Long, lastReadMessageId: Long): ApiResult<Long> =
        if (conversationId < 0) {
            when (
                val result = execute<Map<String, Long>> {
                    client.post("/api/tasks/${-conversationId}/chat/read") {
                        contentType(ContentType.Application.Json)
                        setBody(MarkChatReadRequest(lastReadMessageId))
                    }
                }
            ) {
                is ApiResult.Success -> result.data["lastReadMessageId"]?.let { ApiResult.Success(it) }
                    ?: ApiResult.Error(ApiError("EMPTY_RESPONSE", "Missing read cursor"))
                is ApiResult.Error -> ApiResult.Error(result.error, result.statusCode)
            }
        } else {
            execute {
                client.post("/api/conversations/$conversationId/read") {
                    contentType(ContentType.Application.Json)
                    setBody(MarkChatReadRequest(lastReadMessageId))
                }
            }
        }

    override suspend fun searchMessages(
        conversationId: Long,
        query: String,
        after: Long?,
        limit: Int
    ): ApiResult<ConversationMessagePageDto> = if (conversationId < 0) {
        val taskId = if (conversationId < 0) -conversationId else conversationId
        when (
            val result = execute<List<ChatSearchResultDto>> {
                client.get("/api/tasks/$taskId/chat/search") {
                    parameter("query", query)
                    parameter("limit", limit)
                }
            }
        ) {
            is ApiResult.Success -> ApiResult.Success(
                ConversationMessagePageDto(
                    messages = result.data.map { it.toConversationMessage() },
                    hasMore = false
                )
            )
            is ApiResult.Error -> ApiResult.Error(result.error, result.statusCode)
        }
    } else {
        execute {
            client.get("/api/conversations/$conversationId/messages/search") {
                parameter("q", query)
                parameter("limit", limit)
                after?.let { parameter("after", it) }
            }
        }
    }

    private fun mapTaskMessage(result: ApiResult<ChatMessageDto>): ApiResult<ConversationMessageDto> =
        when (result) {
            is ApiResult.Success -> ApiResult.Success(result.data.toConversationMessage())
            is ApiResult.Error -> ApiResult.Error(result.error, result.statusCode)
        }

    override fun observeMessages(conversationId: Long, after: Long?): Flow<ConversationMessageDto> =
        observeEvents(conversationId, after).mapNotNull { event ->
            (event as? ConversationRealtimeEvent.Message)
                ?.takeIf { it.type == ConversationMessageEventType.MESSAGE }
                ?.message
        }

    override fun observeEvents(
        conversationId: Long,
        after: Long?,
        afterEvent: Long?
    ): Flow<ConversationRealtimeEvent> = flow {
        val path = if (conversationId < 0) {
            "/api/tasks/${-conversationId}/chat/ws"
        } else {
            "/api/conversations/$conversationId/ws"
        }
        client.webSocket(
            urlString = path,
            request = {
                realtimeCursorParameters(after, afterEvent).forEach { (name, value) -> parameter(name, value) }
            }
        ) {
            for (frame in incoming) {
                when (frame) {
                    is Frame.Text -> {
                        val text = frame.data.decodeToString()
                        val event = if (conversationId < 0) {
                            parseTaskChatRealtimeEvent(realtimeJson, text, -conversationId)
                                ?.toConversationEvent(-conversationId)
                        } else {
                            parseConversationRealtimeEvent(realtimeJson, text, conversationId)
                        }
                        event?.let { emit(it) }
                    }
                    is Frame.Close -> {
                        val reason = frame.readReason()
                        when {
                            isConversationOverflowClose(reason) -> throw ConversationOverflowException()
                            reason?.code == CloseReason.Codes.VIOLATED_POLICY.code ->
                                throw ConversationAccessRevokedException()
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    private suspend inline fun <reified T> execute(
        block: () -> io.ktor.client.statement.HttpResponse
    ): ApiResult<T> =
        try {
            val response = block()
            parseResponse(response.status.value, response.body())
        } catch (e: CancellationException) {
            throw e
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
            body.success && data != null && statusCode in 200..299 -> ApiResult.Success(data)
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

private fun TaskChatRealtimeEvent.toConversationEvent(taskId: Long): ConversationRealtimeEvent? = when (this) {
    is TaskChatRealtimeEvent.Message -> ConversationRealtimeEvent.Message(
        message.toConversationMessage(),
        ConversationMessageEventType.valueOf(type.name),
        eventId
    )
    is TaskChatRealtimeEvent.Reaction -> ConversationRealtimeEvent.Reaction(messageId, reactions, eventId)
    is TaskChatRealtimeEvent.Read -> ConversationRealtimeEvent.Read(userId, lastReadMessageId, eventId)
    is TaskChatRealtimeEvent.Pin -> ConversationRealtimeEvent.Pin(isPinned, eventId)
    is TaskChatRealtimeEvent.Typing -> ConversationRealtimeEvent.Typing(userId, fullName, eventId)
}
