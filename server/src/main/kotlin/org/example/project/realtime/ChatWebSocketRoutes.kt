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
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
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
            val requestedEventCursor = call.request.queryParameters["afterEvent"]?.toLongOrNull()
            var lastDeliveredEventId = requestedEventCursor ?: 0L
            var replayComplete = false
            val pendingEvents = mutableListOf<ChatEventBus.Event>()

            fun freshUser() = sessionService.validateSession(token)

            fun revalidate(): Boolean {
                val current = freshUser() ?: return false
                return chatRepository.canRead(
                    taskId, current.userId, current.hasPermission("tasks", "read_any")
                )
            }

            suspend fun deliverEventLocked(event: ChatEventBus.Event) {
                if (event.eventId != null && event.eventId <= lastDeliveredEventId) return
                if (!revalidate()) {
                    close(refused)
                    throw CancellationException("task chat session or access revoked")
                }
                when (event.type) {
                    "MESSAGE", "EDITED", "REVOKED" -> {
                        val messageId = event.messageId ?: return
                        val message = chatService.messageForEvent(taskId, messageId, user.userId) ?: return
                        if (event.type != "MESSAGE" || message.id > lastDeliveredId) {
                            outgoing.send(frameFor(message, event.type, event.eventId))
                            if (message.id > lastDeliveredId) lastDeliveredId = message.id
                        }
                    }
                    "REACTION" -> {
                        val reactions = event.messageId?.let {
                            chatService.reactionsForEvent(it, user.userId)
                        } ?: emptyList()
                        outgoing.send(frameFor(event, reactions))
                    }
                    "READ" -> {
                        val current = event.copy(
                            lastReadMessageId = event.actorUserId?.let {
                                chatRepository.readCursor(taskId, it)
                            } ?: event.lastReadMessageId
                        )
                        outgoing.send(frameFor(current))
                    }
                    "PIN", "TYPING" -> outgoing.send(frameFor(event))
                }
                if (event.eventId != null) lastDeliveredEventId = event.eventId
            }

            val subscribed = CompletableDeferred<Unit>()
            val liveJob = launch {
                chatEventBus.events
                    .onSubscription { subscribed.complete(Unit) }
                    .collect { event ->
                        if (event.taskId != taskId) return@collect
                        sendLock.withLock {
                            if (replayComplete) {
                                deliverEventLocked(event)
                            } else {
                                pendingEvents += event
                            }
                        }
                    }
            }

            val overflowJob = launch {
                chatEventBus.overflow.collect { overflow ->
                    if (overflow.taskId == taskId) {
                        close(CloseReason(CloseReason.Codes.INTERNAL_ERROR.code, "event overflow; reconnect"))
                    }
                }
            }

            var lastTypingAtMs = 0L
            try {
                subscribed.await()
                if (requestedEventCursor == null) {
                    lastDeliveredEventId = chatRepository.latestEventId(taskId)
                    while (true) {
                        if (!revalidate()) {
                            close(refused)
                            return@webSocket
                        }
                        val page = chatService.listMessages(
                            taskId, lastDeliveredId, null, HISTORY_PAGE_SIZE, user
                        )
                        sendLock.withLock {
                            page.messages.forEach { message ->
                                if (message.id > lastDeliveredId) {
                                    outgoing.send(frameFor(message, "MESSAGE", null))
                                    lastDeliveredId = message.id
                                }
                            }
                        }
                        if (!page.hasMore) break
                    }
                } else {
                    while (true) {
                        val rows = chatRepository.listEvents(
                            taskId, lastDeliveredEventId, HISTORY_PAGE_SIZE
                        )
                        sendLock.withLock {
                            rows.forEach { row ->
                                deliverEventLocked(
                                    ChatEventBus.Event(
                                        eventId = row.id,
                                        category = eventCategory(row.category),
                                        type = row.category,
                                        taskId = row.taskId,
                                        messageId = row.messageId,
                                        actorUserId = row.actorUserId,
                                        isPinned = row.isPinned
                                    )
                                )
                            }
                        }
                        if (rows.size < HISTORY_PAGE_SIZE) break
                    }
                }
                sendLock.withLock {
                    replayComplete = true
                    pendingEvents.sortWith(compareBy(nullsLast()) { it.eventId })
                    pendingEvents.forEach { deliverEventLocked(it) }
                    pendingEvents.clear()
                }
                incoming.consumeEach { frame ->
                    if (frame is Frame.Text) {
                        val action = runCatching {
                            Json.parseToJsonElement(frame.data.decodeToString())
                                .jsonObject["action"]?.jsonPrimitive?.content
                        }.getOrNull()
                        val now = System.currentTimeMillis()
                        if (action == "TYPING" && now - lastTypingAtMs >= TYPING_THROTTLE_MS) {
                            lastTypingAtMs = now
                            val current = freshUser()
                            if (current == null || !revalidate()) {
                                close(refused)
                                throw CancellationException("task chat session or access revoked")
                            }
                            chatService.checkTyping(taskId, current)
                            chatEventBus.publishEvent(
                                ChatEventBus.Event(
                                    eventId = null,
                                    category = EVENT_TYPING,
                                    type = "TYPING",
                                    taskId = taskId,
                                    actorUserId = current.userId,
                                    fullName = current.fullName
                                )
                            )
                        }
                    }
                }
            } catch (e: ForbiddenException) {
                runCatching { close(refused) }
            } finally {
                liveJob.cancel()
                overflowJob.cancel()
            }
        }
    }
}

private fun eventCategory(type: String): String = when (type) {
    "MESSAGE", "EDITED", "REVOKED" -> EVENT_CATEGORY
    "REACTION" -> "TASK_CHAT_REACTION"
    "READ" -> "TASK_CHAT_READ"
    "PIN" -> "TASK_CHAT_PIN"
    else -> type
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

private fun frameFor(message: ChatMessageDto, type: String, eventId: Long?): Frame.Text {
    val envelope = buildJsonObject {
        put("category", EVENT_CATEGORY)
        put("type", type)
        put("id", message.id)
        if (eventId == null) put("eventId", JsonNull) else put("eventId", eventId)
        put("message", Json.encodeToJsonElement(ChatMessageDto.serializer(), message))
    }
    return Frame.Text(envelope.toString())
}

private fun frameFor(
    event: ChatEventBus.Event,
    reactions: List<ReactionSummaryDto>? = null
): Frame.Text {
    val envelope = buildJsonObject {
        put("category", event.category)
        put("type", event.type)
        put("taskId", event.taskId)
        if (event.eventId == null) put("eventId", JsonNull) else put("eventId", event.eventId)
        event.messageId?.let { put("messageId", it) }
        event.actorUserId?.let { put("userId", it) }
        event.fullName?.let { put("fullName", it) }
        event.lastReadMessageId?.let { put("lastReadMessageId", it) }
        event.isPinned?.let { put("isPinned", it) }
        reactions?.let {
            put("reactions", Json.encodeToJsonElement(ListSerializer(ReactionSummaryDto.serializer()), it))
        }
    }
    return Frame.Text(envelope.toString())
}
