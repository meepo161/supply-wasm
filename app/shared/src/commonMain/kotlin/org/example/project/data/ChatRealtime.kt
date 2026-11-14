package org.example.project.data

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.example.project.dto.ChatMessageDto

const val CHAT_REALTIME_CATEGORY = "TASK_CHAT_MESSAGE"

class ChatAccessRevokedException : Exception("Task chat access revoked")

fun isChatAccessTerminated(statusCode: Int?): Boolean =
    statusCode == HttpStatusCode.Unauthorized.value ||
        statusCode == HttpStatusCode.Forbidden.value ||
        statusCode == HttpStatusCode.NotFound.value

fun parseChatRealtimeMessage(json: Json, text: String, expectedTaskId: Long): ChatMessageDto? {
    val envelope = (runCatching { json.parseToJsonElement(text) }.getOrNull()) as? JsonObject ?: return null
    val category = envelope["category"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
    if (category != CHAT_REALTIME_CATEGORY) return null
    val messageElement = envelope["message"] ?: return null
    val message = runCatching {
        json.decodeFromJsonElement(ChatMessageDto.serializer(), messageElement)
    }.getOrNull() ?: return null
    return message.takeIf { it.taskId == expectedTaskId }
}

fun List<ChatMessageDto>.appendDistinct(incoming: List<ChatMessageDto>): List<ChatMessageDto> {
    if (incoming.isEmpty()) return this
    return (this + incoming).distinctBy { it.id }.sortedBy { it.id }
}
