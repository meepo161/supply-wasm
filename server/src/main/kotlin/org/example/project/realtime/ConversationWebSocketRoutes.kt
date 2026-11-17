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
import kotlinx.serialization.json.put
import org.example.project.dto.ConversationMessageDto
import org.example.project.exception.ForbiddenException
import org.example.project.repository.ConversationRepository
import org.example.project.service.ConversationService
import org.example.project.service.SessionService
import java.util.*

private const val HISTORY_PAGE_SIZE = 200
private const val EVENT_CATEGORY = "CONVERSATION_MESSAGE"

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

            fun revalidate(): Boolean {
                val freshUser = sessionService.validateSession(token) ?: return false
                return conversationService.canAccess(conversation, freshUser.userId)
            }

            suspend fun deliver(message: ConversationMessageDto) {
                sendLock.withLock {
                    if (message.id > lastDeliveredId) {
                        outgoing.send(frameFor(message))
                        lastDeliveredId = message.id
                    }
                }
            }

            val subscribed = CompletableDeferred<Unit>()
            val liveJob = launch {
                conversationEventBus.messages
                    .onSubscription { subscribed.complete(Unit) }
                    .collect { message ->
                        if (message.conversationId != conversation.id || message.id <= lastDeliveredId) return@collect
                        if (!revalidate()) {
                            close(refused)
                            throw CancellationException("conversation session or access revoked")
                        }
                        deliver(message)
                    }
            }

            try {
                subscribed.await()
                while (true) {
                    if (!revalidate()) {
                        close(refused)
                        return@webSocket
                    }
                    val page = conversationService.listMessages(conversation.id, lastDeliveredId, null, HISTORY_PAGE_SIZE, user)
                    page.messages.forEach { deliver(it) }
                    if (!page.hasMore) break
                }
                incoming.consumeEach { }
            } catch (e: ForbiddenException) {
                runCatching { close(refused) }
            } finally {
                liveJob.cancel()
            }
        }
    }
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

private fun frameFor(message: ConversationMessageDto): Frame.Text {
    val envelope = buildJsonObject {
        put("category", EVENT_CATEGORY)
        put("id", message.id)
        put("message", Json.encodeToJsonElement(ConversationMessageDto.serializer(), message))
    }
    return Frame.Text(envelope.toString())
}
