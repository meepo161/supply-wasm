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
import org.example.project.dto.ChatMessagePageDto
import org.example.project.dto.ConversationDto
import org.example.project.dto.ConversationMessageDto
import org.example.project.dto.ConversationMessagePageDto
import org.example.project.dto.CreateConversationRequest
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
    ): ApiResult<ConversationMessageDto> {
        if (conversationId < 0) {
            val result = execute<org.example.project.dto.ChatMessageDto> {
                sendMessageHttp(conversationId, request, file)
            }
            return when (result) {
                is ApiResult.Success -> ApiResult.Success(result.data.toConversationMessage())
                is ApiResult.Error -> ApiResult.Error(result.error, result.statusCode)
            }
        }
        return execute { sendMessageHttp(conversationId, request, file) }
    }

    private suspend fun sendMessageHttp(
        conversationId: Long,
        request: SendChatMessageRequest,
        file: AttachmentFileData?
    ): io.ktor.client.statement.HttpResponse {
        val url = conversationMessagePath(conversationId)
        return if (file == null) {
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
                    file,
                    request.replyToMessageId
                )
            )
        }
    }

    override fun observeMessages(conversationId: Long, after: Long?): Flow<ConversationMessageDto> = flow {
        if (conversationId < 0) {
            val taskId = -conversationId
            client.webSocket(
                urlString = "/api/tasks/$taskId/chat/ws",
                request = { after?.let { parameter("after", it) } }
            ) {
                for (frame in incoming) {
                    when (frame) {
                        is Frame.Text ->
                            parseChatRealtimeMessage(realtimeJson, frame.data.decodeToString(), taskId)
                                ?.let { emit(it.toConversationMessage()) }
                        is Frame.Close -> {
                            if (frame.readReason()?.code == CloseReason.Codes.VIOLATED_POLICY.code) {
                                throw ConversationAccessRevokedException()
                            }
                        }
                        else -> Unit
                    }
                }
            }
        } else {
            client.webSocket(
                urlString = "/api/conversations/$conversationId/ws",
                request = { after?.let { parameter("after", it) } }
            ) {
                for (frame in incoming) {
                    when (frame) {
                        is Frame.Text ->
                            parseConversationRealtimeMessage(
                                realtimeJson,
                                frame.data.decodeToString(),
                                conversationId
                            )?.let { emit(it) }
                        is Frame.Close -> {
                            if (frame.readReason()?.code == CloseReason.Codes.VIOLATED_POLICY.code) {
                                throw ConversationAccessRevokedException()
                            }
                        }
                        else -> Unit
                    }
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
