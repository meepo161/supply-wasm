package org.example.project.realtime

import io.ktor.http.HttpHeaders
import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.builtins.ListSerializer
import org.example.project.dto.ChatMessageDto
import org.example.project.dto.ReactionSummaryDto
import org.example.project.exception.ForbiddenException
import org.example.project.repository.ChatRepository
import org.example.project.service.ChatService
import org.example.project.service.SessionService
import java.util.*

private const val HISTORY_PAGE_SIZE = 200
private const val EVENT_CATEGORY = "TASK_CHAT_MESSAGE"
private const val EVENT_TYPING = "TASK_CHAT_TYPING"
private const val TYPING_THROTTLE_MS = 3_000L

fun Route.chatWebSocketRoutes(
    chatService: ChatService,
    chatRepository: ChatRepository,
    sessionService: SessionService,
    chatEventBus: ChatEventBus,
    cookieName: String,
    allowedOrigins: List<String>
) {
    route("/api/tasks/{taskId}/chat") {
        install(ChatWsHandshakeGuard) {
            isAllowed = { call ->
                isHandshakeAllowed(call, sessionService, chatRepository, cookieName, allowedOrigins)
            }
        }

        webSocket("/ws") {
            val refused = CloseReason(CloseReason.Codes.VIOLATED_POLICY.code, "")
            val token = call.request.cookies[cookieName]
                ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            val user = token?.let { sessionService.validateSession(it) }
            val taskId = call.parameters["taskId"]?.toLongOrNull()
            if (token == null || user == null || taskId == null ||
                chatRepository.findTaskById(taskId) == null ||
                !chatRepository.canRead(taskId, user.userId, user.hasPermission("tasks", "read_any"))
            ) {
                close(refused)
                return@webSocket
            }

            val sendLock = Mutex()
            var lastDeliveredId = call.request.queryParameters["after"]?.toLongOrNull() ?: 0L

            fun revalidate(): Boolean {
                val freshUser = sessionService.validateSession(token) ?: return false
                return chatRepository.canRead(taskId, freshUser.userId, freshUser.hasPermission("tasks", "read_any"))
            }

            suspend fun deliver(message: ChatMessageDto) {
                sendLock.withLock {
                    if (message.id > lastDeliveredId) {
                        outgoing.send(frameFor(message))
                        lastDeliveredId = message.id
                    }
                }
            }

            val subscribed = CompletableDeferred<Unit>()
            val liveJob = launch {
                chatEventBus.messages
                    .onSubscription { subscribed.complete(Unit) }
                    .collect { message ->
                        if (message.taskId != taskId || message.id <= lastDeliveredId) return@collect
                        if (!revalidate()) {
                            close(refused)
                            throw CancellationException("task chat session or access revoked")
                        }
                        deliver(message)
                    }
            }

            val liveEventsJob = launch {
                chatEventBus.workspaceEvents.collect { event ->
                    if (event.taskId != taskId) return@collect
                    if (!revalidate()) {
                        close(refused)
                        throw CancellationException("task chat session or access revoked")
                    }
                    sendLock.withLock { outgoing.send(frameFor(event)) }
                }
            }

            var lastTypingAtMs = 0L

            try {
                subscribed.await()
                while (true) {
                    if (!revalidate()) {
                        close(refused)
                        return@webSocket
                    }
                    val page = chatService.listMessages(taskId, lastDeliveredId, null, HISTORY_PAGE_SIZE, user)
                    page.messages.forEach { deliver(it) }
                    if (!page.hasMore) break
                }
                incoming.consumeEach { frame ->
                    if (frame is Frame.Text) {
                        val action = runCatching {
                            Json.parseToJsonElement(frame.data.decodeToString())
                                .jsonObject["action"]?.jsonPrimitive?.content
                        }.getOrNull()
                        if (action == "TYPING") {
                            val now = System.currentTimeMillis()
                            if (now - lastTypingAtMs >= TYPING_THROTTLE_MS) {
                                lastTypingAtMs = now
                                if (!revalidate()) {
                                    close(refused)
                                    throw CancellationException("task chat session or access revoked")
                                }
                                chatEventBus.publishEvent(
                                    ChatEventBus.WorkspaceEvent(
                                        category = EVENT_TYPING,
                                        taskId = taskId,
                                        userId = user.userId,
                                        fullName = user.fullName
                                    )
                                )
                            }
                        }
                    }
                }
            } catch (e: ForbiddenException) {
                runCatching { close(refused) }
            } finally {
                liveJob.cancel()
                liveEventsJob.cancel()
            }
        }
    }
}

private class ChatWsHandshakeGuardConfig {
    var isAllowed: (ApplicationCall) -> Boolean = { false }
}

private val ChatWsHandshakeGuard = createRouteScopedPlugin(
    "ChatWsHandshakeGuard",
    ::ChatWsHandshakeGuardConfig
) {
    onCall { call ->
        if (!pluginConfig.isAllowed(call)) {
            throw ForbiddenException("Access to task chat denied")
        }
    }
}

private fun isHandshakeAllowed(
    call: ApplicationCall,
    sessionService: SessionService,
    chatRepository: ChatRepository,
    cookieName: String,
    allowedOrigins: List<String>
): Boolean {
    val origin = call.request.headers[HttpHeaders.Origin]
    if (origin != null && allowedOrigins.none { it.equals(origin, ignoreCase = true) }) return false
    val token = call.request.cookies[cookieName]
        ?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return false
    val user = sessionService.validateSession(token) ?: return false
    val taskId = call.parameters["taskId"]?.toLongOrNull() ?: return false
    if (chatRepository.findTaskById(taskId) == null) return false
    return chatRepository.canRead(taskId, user.userId, user.hasPermission("tasks", "read_any"))
}

private fun frameFor(message: ChatMessageDto): Frame.Text {
    val envelope = buildJsonObject {
        put("category", EVENT_CATEGORY)
        put("id", message.id)
        put("message", Json.encodeToJsonElement(ChatMessageDto.serializer(), message))
    }
    return Frame.Text(envelope.toString())
}

private fun frameFor(event: ChatEventBus.WorkspaceEvent): Frame.Text {
    val envelope = buildJsonObject {
        put("category", event.category)
        put("taskId", event.taskId)
        event.messageId?.let { put("messageId", it) }
        event.userId?.let { put("userId", it) }
        event.fullName?.let { put("fullName", it) }
        event.lastReadMessageId?.let { put("lastReadMessageId", it) }
        event.isPinned?.let { put("isPinned", it) }
        event.reactions?.let {
            put("reactions", Json.encodeToJsonElement(ListSerializer(ReactionSummaryDto.serializer()), it))
        }
    }
    return Frame.Text(envelope.toString())
}
