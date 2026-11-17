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
import org.example.project.dto.NotificationDto
import org.example.project.exception.ForbiddenException
import org.example.project.service.NotificationService
import org.example.project.service.SessionService
import java.util.*

private const val HISTORY_PAGE_SIZE = 200
private const val EVENT_CATEGORY = "NOTIFICATION"

fun Route.notificationWebSocketRoutes(
    notificationService: NotificationService,
    sessionService: SessionService,
    notificationEventBus: NotificationEventBus,
    cookieName: String,
    allowedOrigins: List<String>
) {
    route("/api/notifications") {
        install(NotificationWsHandshakeGuard) {
            isAllowed = { call ->
                isHandshakeAllowed(call, sessionService, cookieName, allowedOrigins)
            }
        }

        webSocket("/ws") {
            val refused = CloseReason(CloseReason.Codes.VIOLATED_POLICY.code, "")
            val token = call.request.cookies[cookieName]
                ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            val user = token?.let { sessionService.validateSession(it) }
            if (token == null || user == null) {
                close(refused)
                return@webSocket
            }

            val sendLock = Mutex()
            var lastDeliveredId = call.request.queryParameters["after"]?.toLongOrNull() ?: 0L

            fun revalidate(): Boolean = sessionService.validateSession(token) != null

            suspend fun deliver(notification: NotificationDto) {
                sendLock.withLock {
                    if (notification.id > lastDeliveredId) {
                        outgoing.send(frameFor(notification))
                        lastDeliveredId = notification.id
                    }
                }
            }

            val subscribed = CompletableDeferred<Unit>()
            val liveJob = launch {
                notificationEventBus.notifications
                    .onSubscription { subscribed.complete(Unit) }
                    .collect { event ->
                        if (event.recipientId != user.userId || event.notification.id <= lastDeliveredId) return@collect
                        if (!revalidate()) {
                            close(refused)
                            throw CancellationException("notification session revoked")
                        }
                        deliver(event.notification)
                    }
            }

            try {
                subscribed.await()
                while (true) {
                    if (!revalidate()) {
                        close(refused)
                        return@webSocket
                    }
                    val page = notificationService.list(lastDeliveredId, null, HISTORY_PAGE_SIZE, user)
                    page.notifications.forEach { deliver(it) }
                    if (!page.hasMore) break
                }
                incoming.consumeEach { }
            } finally {
                liveJob.cancel()
            }
        }
    }
}

private class NotificationWsHandshakeGuardConfig {
    var isAllowed: (ApplicationCall) -> Boolean = { false }
}

private val NotificationWsHandshakeGuard = createRouteScopedPlugin(
    "NotificationWsHandshakeGuard",
    ::NotificationWsHandshakeGuardConfig
) {
    onCall { call ->
        if (!pluginConfig.isAllowed(call)) {
            throw ForbiddenException("Access to notification stream denied")
        }
    }
}

private fun isHandshakeAllowed(
    call: ApplicationCall,
    sessionService: SessionService,
    cookieName: String,
    allowedOrigins: List<String>
): Boolean {
    val origin = call.request.headers[HttpHeaders.Origin]
    if (origin != null && allowedOrigins.none { it.equals(origin, ignoreCase = true) }) return false
    val token = call.request.cookies[cookieName]
        ?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return false
    return sessionService.validateSession(token) != null
}

private fun frameFor(notification: NotificationDto): Frame.Text {
    val envelope = buildJsonObject {
        put("category", EVENT_CATEGORY)
        put("id", notification.id)
        put("notification", Json.encodeToJsonElement(NotificationDto.serializer(), notification))
    }
    return Frame.Text(envelope.toString())
}
