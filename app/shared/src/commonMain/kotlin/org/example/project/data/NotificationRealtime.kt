package org.example.project.data

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.example.project.dto.NotificationDto

const val NOTIFICATION_REALTIME_CATEGORY = "NOTIFICATION"

class NotificationStreamEndedException : Exception("Notification stream access revoked")

fun isNotificationStreamTerminated(statusCode: Int?): Boolean =
    statusCode == HttpStatusCode.Unauthorized.value ||
        statusCode == HttpStatusCode.Forbidden.value ||
        statusCode == HttpStatusCode.NotFound.value

fun parseNotificationRealtimeMessage(json: Json, text: String): NotificationDto? {
    val envelope = (runCatching { json.parseToJsonElement(text) }.getOrNull()) as? JsonObject ?: return null
    val category = envelope["category"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
    if (category != NOTIFICATION_REALTIME_CATEGORY) return null
    val notificationElement = envelope["notification"] ?: return null
    return runCatching {
        json.decodeFromJsonElement(NotificationDto.serializer(), notificationElement)
    }.getOrNull()
}

fun List<NotificationDto>.appendDistinctNotifications(incoming: List<NotificationDto>): List<NotificationDto> {
    if (incoming.isEmpty()) return this
    return (this + incoming).distinctBy { it.id }.sortedBy { it.id }
}
