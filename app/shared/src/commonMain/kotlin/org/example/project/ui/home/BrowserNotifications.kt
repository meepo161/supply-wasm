package org.example.project.ui.home

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

expect object BrowserNotifications {
    fun isSupported(): Boolean
    fun isGranted(): Boolean
    fun requestPermission(onResult: (Boolean) -> Unit)
    fun enablePush(onResult: (Boolean) -> Unit)
    fun consumePendingEventId(): Long?
    fun setPendingOpenListener(onPending: () -> Unit)
    fun show(title: String, body: String)
}

fun isNeutralNotificationPayload(payload: String): Boolean = try {
    val value = Json.parseToJsonElement(payload).jsonObject
    value.keys == setOf("category", "eventId") &&
        value.getValue("category").jsonPrimitive.content == "NOTIFICATION" &&
        (value.getValue("eventId").jsonPrimitive.longOrNull ?: 0L) > 0L
} catch (_: Exception) {
    false
}

fun pendingNotificationEventId(value: String?): Long? {
    val parameters = value?.substringAfter('#', "")?.split('&') ?: return null
    val eventId = parameters.firstNotNullOfOrNull { parameter ->
        val key = parameter.substringBefore('=')
        if (key == "notification" || key == "eventId") parameter.substringAfter('=').toLongOrNull() else null
    }
    return eventId?.takeIf { it > 0L }
}
