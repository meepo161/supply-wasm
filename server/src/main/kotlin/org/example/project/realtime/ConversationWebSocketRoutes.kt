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
import org.example.project.dto.ConversationMessageDto
import org.example.project.dto.ReactionSummaryDto
import org.example.project.exception.ForbiddenException
import org.example.project.repository.ConversationRepository
import org.example.project.service.ConversationService
import org.example.project.service.SessionService
import java.util.*

private const val HISTORY_PAGE_SIZE = 200
private const val EVENT_CATEGORY = "CONVERSATION_MESSAGE"
private const val EVENT_TYPING = "CONVERSATION_TYPING"
private const val TYPING_THROTTLE_MS = 3_000L

fun Route.conversationWebSocketRoutes(
    conversationService: ConversationService,
    conversationRepository: ConversationRepository,
    sessionService: SessionService,
    conversationEventBus: ConversationEventBus,
    cookieName: String,
    allowedOrigins: List<String>
) {
    route("/api/conversations/{conversationId}") {
        install(ConversationWsHandshakeGuard) {
            isAllowed = { call ->
                isHandshakeAllowed(call, sessionService, conversationRepository, conversationService, cookieName, allowedOrigins)
            }
        }

        webSocket("/ws") {
            val refused = CloseReason(CloseReason.Codes.VIOLATED_POLICY.code, "")
            val token = call.request.cookies[cookieName]
                ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            val user = token?.let { sessionService.validateSession(it) }
            val conversationId = call.parameters["conversationId"]?.toLongOrNull()
            val conversation = conversationId?.let { conversationRepository.findById(it) }
            if (token == null || user == null || conversation == null ||
                !conversationService.canAccess(conversation, user.userId)
            ) {
                close(refused)
                return@webSocket
            }

            val sendLock = Mutex()
            var lastDeliveredId = call.request.queryParameters["after"]?.toLongOrNull() ?: 0L
            val requestedEventCursor = call.request.queryParameters["afterEvent"]?.toLongOrNull()
            var lastDeliveredEventId = requestedEventCursor ?: 0L
            var replayComplete = false
            val pendingEvents = mutableListOf<ConversationEventBus.Event>()

            fun revalidate(): Boolean {
                val freshUser = sessionService.validateSession(token) ?: return false
                return conversationService.canAccess(conversation, freshUser.userId)
            }

            suspend fun deliverEventLocked(event: ConversationEventBus.Event) {
                if (event.eventId != null && event.eventId <= lastDeliveredEventId) return
                if (!revalidate()) {
                    close(refused)
                    throw CancellationException("conversation session or access revoked")
                }
                when (event.type) {
                    "MESSAGE", "EDITED", "REVOKED" -> {
                        val messageId = event.messageId ?: return
                        val message = conversationService.messageForEvent(
                            conversation.id, messageId, user.userId
                        ) ?: return
                        if (event.type != "MESSAGE" || message.id > lastDeliveredId) {
                            outgoing.send(frameFor(message, event.type, event.eventId))
                            if (message.id > lastDeliveredId) lastDeliveredId = message.id
                        }
                    }
                    "REACTION" -> {
                        val reactions = event.messageId?.let {
                            conversationService.reactionsForEvent(it, user.userId)
                        } ?: emptyList()
                        outgoing.send(frameFor(event, reactions))
                    }
                    "READ" -> {
                        val current = event.copy(
                            lastReadMessageId = event.actorUserId?.let {
                                conversationRepository.readCursor(conversation.id, it)
                            } ?: event.lastReadMessageId
                        )
                        outgoing.send(frameFor(current))
                    }
                    "TYPING" -> outgoing.send(frameFor(event))
                }
                if (event.eventId != null) lastDeliveredEventId = event.eventId
            }

            val subscribed = CompletableDeferred<Unit>()
            val liveJob = launch {
                conversationEventBus.events
                    .onSubscription { subscribed.complete(Unit) }
                    .collect { event ->
                        if (event.conversationId != conversation.id) return@collect
                        sendLock.withLock {
                            if (replayComplete) {
                                deliverEventLocked(event)
                            } else {
                                pendingEvents += event
                            }
                        }
                    }
            }

            var lastTypingAtMs = 0L
            try {
                subscribed.await()
                if (requestedEventCursor == null) {
                    lastDeliveredEventId = conversationRepository.latestEventId(conversation.id)
                    while (true) {
                        if (!revalidate()) {
                            close(refused)
                            return@webSocket
                        }
                        val page = conversationService.listMessages(
                            conversation.id, lastDeliveredId, null, HISTORY_PAGE_SIZE, user
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
                }
                if (requestedEventCursor != null) {
                    while (true) {
                        val rows = conversationRepository.listEvents(
                            conversation.id, lastDeliveredEventId, HISTORY_PAGE_SIZE
                        )
                        sendLock.withLock {
                            rows.forEach { row ->
                                deliverEventLocked(
                                    ConversationEventBus.Event(
                                        eventId = row.id,
                                        category = eventCategory(row.category),
                                        type = row.category,
                                        conversationId = row.conversationId,
                                        messageId = row.messageId,
                                        actorUserId = row.actorUserId
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
                            if (!revalidate()) {
                                close(refused)
                                throw CancellationException("conversation session or access revoked")
                            }
                            conversationEventBus.publishEvent(
                                ConversationEventBus.Event(
                                    eventId = null,
                                    category = EVENT_TYPING,
                                    type = "TYPING",
                                    conversationId = conversation.id,
                                    actorUserId = user.userId,
                                    fullName = user.fullName
                                )
                            )
                        }
                    }
                }
            } catch (e: ForbiddenException) {
                runCatching { close(refused) }
            } finally {
                liveJob.cancel()
            }
        }
    }
}

private fun eventCategory(type: String): String = when (type) {
    "MESSAGE", "EDITED", "REVOKED" -> EVENT_CATEGORY
    "REACTION" -> "CONVERSATION_REACTION"
    "READ" -> "CONVERSATION_READ"
    else -> type
}

private class ConversationWsHandshakeGuardConfig {
    var isAllowed: (ApplicationCall) -> Boolean = { false }
}

private val ConversationWsHandshakeGuard = createRouteScopedPlugin(
    "ConversationWsHandshakeGuard",
    ::ConversationWsHandshakeGuardConfig
) {
    onCall { call ->
        if (!pluginConfig.isAllowed(call)) {
            throw ForbiddenException("Access to conversation denied")
        }
    }
}

private fun isHandshakeAllowed(
    call: ApplicationCall,
    sessionService: SessionService,
    conversationRepository: ConversationRepository,
    conversationService: ConversationService,
    cookieName: String,
    allowedOrigins: List<String>
): Boolean {
    val origin = call.request.headers[HttpHeaders.Origin]
    if (origin != null && allowedOrigins.none { it.equals(origin, ignoreCase = true) }) return false
    val token = call.request.cookies[cookieName]
        ?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return false
    val user = sessionService.validateSession(token) ?: return false
    val conversationId = call.parameters["conversationId"]?.toLongOrNull() ?: return false
    val conversation = conversationRepository.findById(conversationId) ?: return false
    return conversationService.canAccess(conversation, user.userId)
}

private fun frameFor(message: ConversationMessageDto, type: String, eventId: Long?): Frame.Text {
    val envelope = buildJsonObject {
        put("category", EVENT_CATEGORY)
        put("type", type)
        put("id", message.id)
        if (eventId == null) put("eventId", JsonNull) else put("eventId", eventId)
        put("message", Json.encodeToJsonElement(ConversationMessageDto.serializer(), message))
    }
    return Frame.Text(envelope.toString())
}

private fun frameFor(
    event: ConversationEventBus.Event,
    reactions: List<ReactionSummaryDto>? = null
): Frame.Text {
    val envelope = buildJsonObject {
        put("category", event.category)
        put("type", event.type)
        put("conversationId", event.conversationId)
        if (event.eventId == null) put("eventId", JsonNull) else put("eventId", event.eventId)
        event.messageId?.let { put("messageId", it) }
        event.actorUserId?.let { put("userId", it) }
        event.fullName?.let { put("fullName", it) }
        event.lastReadMessageId?.let { put("lastReadMessageId", it) }
        reactions?.let {
            put("reactions", Json.encodeToJsonElement(ListSerializer(ReactionSummaryDto.serializer()), it))
        }
    }
    return Frame.Text(envelope.toString())
}
