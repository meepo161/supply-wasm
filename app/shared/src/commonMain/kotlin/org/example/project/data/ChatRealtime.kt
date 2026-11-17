package org.example.project.data

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.example.project.dto.ChatMessageDto
import org.example.project.dto.ReactionSummaryDto

const val CHAT_REALTIME_CATEGORY = "TASK_CHAT_MESSAGE"

/** Типизированные события WS Task Chat (D-51). */
sealed interface TaskChatRealtimeEvent {
    data class Message(val message: ChatMessageDto) : TaskChatRealtimeEvent
    data class Reaction(val messageId: Long, val reactions: List<ReactionSummaryDto>) : TaskChatRealtimeEvent
    data class Read(val userId: Long, val lastReadMessageId: Long) : TaskChatRealtimeEvent
    data class Pin(val isPinned: Boolean) : TaskChatRealtimeEvent
    data class Typing(val userId: Long, val fullName: String) : TaskChatRealtimeEvent
}

fun chatTypingFrame(): String = """{"action":"TYPING"}"""

fun parseTaskChatRealtimeEvent(
    json: Json,
    text: String,
    expectedTaskId: Long
): TaskChatRealtimeEvent? {
    val envelope = (runCatching { json.parseToJsonElement(text) }.getOrNull()) as? JsonObject ?: return null
    val category = envelope["category"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
        ?: return null
    return when (category) {
        CHAT_REALTIME_CATEGORY -> {
            val messageElement = envelope["message"] ?: return null
            val message = runCatching {
                json.decodeFromJsonElement(ChatMessageDto.serializer(), messageElement)
            }.getOrNull() ?: return null
            message.takeIf { it.taskId == expectedTaskId }
                ?.let { TaskChatRealtimeEvent.Message(it) }
        }
        "TASK_CHAT_REACTION" -> {
            val messageId = envelope["messageId"]?.let { runCatching { it.jsonPrimitive.long }.getOrNull() }
                ?: return null
            val reactionsElement = envelope["reactions"] ?: return null
            val reactions = runCatching {
                json.decodeFromJsonElement(
                    ListSerializer(ReactionSummaryDto.serializer()),
                    reactionsElement
                )
            }.getOrNull() ?: return null
            TaskChatRealtimeEvent.Reaction(messageId, reactions)
        }
        "TASK_CHAT_READ" -> {
            val userId = envelope["userId"]?.let { runCatching { it.jsonPrimitive.long }.getOrNull() }
                ?: return null
            val lastRead = envelope["lastReadMessageId"]?.let { runCatching { it.jsonPrimitive.long }.getOrNull() }
                ?: return null
            TaskChatRealtimeEvent.Read(userId, lastRead)
        }
        "TASK_CHAT_PIN" -> {
            val isPinned = envelope["isPinned"]?.let { runCatching { it.jsonPrimitive.boolean }.getOrNull() }
                ?: return null
            TaskChatRealtimeEvent.Pin(isPinned)
        }
        "TASK_CHAT_TYPING" -> {
            val userId = envelope["userId"]?.let { runCatching { it.jsonPrimitive.long }.getOrNull() }
                ?: return null
            val fullName = envelope["fullName"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
                ?: return null
            TaskChatRealtimeEvent.Typing(userId, fullName)
        }
        else -> null
    }
}

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
