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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.example.project.service.SessionService
import java.util.*

fun Route.erpEventWebSocketRoutes(
    sessionService: SessionService,
    erpEventBus: ErpEventBus,
    cookieName: String,
    allowedOrigins: List<String>
) {
    val json = Json { encodeDefaults = true }

    route("/api/erp-events") {
        install(ErpWsHandshakeGuard) {
            isAllowed = { call ->
                isErpHandshakeAllowed(call, sessionService, cookieName, allowedOrigins)
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
            val subscribed = CompletableDeferred<Unit>()
            val liveJob = launch {
                erpEventBus.events
                    .onSubscription { subscribed.complete(Unit) }
                    .collect { event ->
                        if (sessionService.validateSession(token) == null) {
                            close(refused)
                            throw CancellationException("erp events session revoked")
                        }
                        sendLock.withLock {
                            outgoing.send(Frame.Text(json.encodeToString(event)))
                        }
                    }
            }

            try {
                subscribed.await()
                incoming.consumeEach { }
            } finally {
                liveJob.cancel()
            }
        }
    }
}

private class ErpWsHandshakeGuardConfig {
    lateinit var isAllowed: (ApplicationCall) -> Boolean
}

private val ErpWsHandshakeGuard = createRouteScopedPlugin(
    "ErpWsHandshakeGuard",
    ::ErpWsHandshakeGuardConfig
) {
    val isAllowed = pluginConfig.isAllowed
    onCall { call ->
        if (!isAllowed(call)) {
            call.response.status(io.ktor.http.HttpStatusCode.Forbidden)
            return@onCall
        }
    }
}

private fun isErpHandshakeAllowed(
    call: ApplicationCall,
    sessionService: SessionService,
    cookieName: String,
    allowedOrigins: List<String>
): Boolean {
    val origin = call.request.headers[HttpHeaders.Origin]?.trim()
    if (!origin.isNullOrEmpty() && origin !in allowedOrigins) {
        return false
    }
    val token = call.request.cookies[cookieName]
        ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        ?: return false
    return sessionService.validateSession(token) != null
}
