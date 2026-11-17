package org.example.project.data

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.example.project.dto.ConversationMessageDto

const val CONVERSATION_REALTIME_CATEGORY = "CONVERSATION_MESSAGE"

class ConversationAccessRevokedException : Exception("Conversation access revoked")

fun isConversationAccessTerminated(statusCode: Int?): Boolean =
    statusCode == HttpStatusCode.Unauthorized.value ||
        statusCode == HttpStatusCode.Forbidden.value ||
        statusCode == HttpStatusCode.NotFound.value

fun parseConversationRealtimeMessage(
    json: Json,
    text: String,
    expectedConversationId: Long
): ConversationMessageDto? {
    val envelope = (runCatching { json.parseToJsonElement(text) }.getOrNull()) as? JsonObject ?: return null
    val category = envelope["category"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
    if (category != CONVERSATION_REALTIME_CATEGORY) return null
    val messageElement = envelope["message"] ?: return null
    val message = runCatching {
        json.decodeFromJsonElement(ConversationMessageDto.serializer(), messageElement)
    }.getOrNull() ?: return null
    return message.takeIf { it.conversationId == expectedConversationId }
}

fun List<ConversationMessageDto>.appendDistinctMessages(
    incoming: List<ConversationMessageDto>
): List<ConversationMessageDto> {
    if (incoming.isEmpty()) return this
    return (this + incoming).distinctBy { it.id }.sortedBy { it.id }
}
