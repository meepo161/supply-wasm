package org.example.project.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.readReason
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import org.example.project.dto.ApiResponse
import org.example.project.dto.ErpEventDto
import org.example.project.dto.PresenceSnapshotDto

class ErpEventsClient(private val client: HttpClient) {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetchOnlineUserIds(): List<Long> = runCatching {
        client.get("/api/presence/online").body<ApiResponse<PresenceSnapshotDto>>().data?.onlineUserIds ?: emptyList()
    }.getOrDefault(emptyList())

    fun observeEvents(): Flow<ErpEventDto> = flow {
        var backoffMs = 1_500L
        while (true) {
            var policyClosed = false
            try {
                client.webSocket(urlString = "/api/erp-events/ws") {
                    backoffMs = 1_500L
                    for (frame in incoming) {
                        when (frame) {
                            is Frame.Text -> {
                                val text = frame.data.decodeToString()
                                runCatching { json.decodeFromString(ErpEventDto.serializer(), text) }
                                    .getOrNull()
                                    ?.let { emit(it) }
                            }
                            is Frame.Close -> {
                                if (frame.readReason()?.code == CloseReason.Codes.VIOLATED_POLICY.code) {
                                    policyClosed = true
                                }
                            }
                            else -> Unit
                        }
                    }
                }
            } catch (_: Exception) {
                // Reconnect with bounded backoff when server restarts or network drops.
            }
            if (policyClosed) break
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(15_000L)
        }
    }
}
