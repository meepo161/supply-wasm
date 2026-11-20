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

enum class TaskChatMessageEventType { MESSAGE, EDITED, REVOKED }

sealed interface TaskChatRealtimeEvent {
    val eventId: Long?

    data class Message(
        val message: ChatMessageDto,
        val type: TaskChatMessageEventType = TaskChatMessageEventType.MESSAGE,
        override val eventId: Long? = null
    ) : TaskChatRealtimeEvent

    data class Reaction(
        val messageId: Long,
        val reactions: List<ReactionSummaryDto>,
        override val eventId: Long? = null
    ) : TaskChatRealtimeEvent

    data class Read(
        val userId: Long,
        val lastReadMessageId: Long,
        override val eventId: Long? = null
    ) : TaskChatRealtimeEvent

    data class Pin(val isPinned: Boolean, override val eventId: Long? = null) : TaskChatRealtimeEvent

    data class Typing(
        val userId: Long,
        val fullName: String,
        override val eventId: Long? = null
    ) : TaskChatRealtimeEvent
}

fun chatTypingFrame(): String = """{"action":"TYPING"}"""

fun parseTaskChatRealtimeEvent(
    json: Json,
    text: String,
    expectedTaskId: Long
): TaskChatRealtimeEvent? {
    val envelope = (runCatching { json.parseToJsonElement(text) }.getOrNull()) as? JsonObject ?: return null
    val category = envelope.string("category") ?: return null
    val eventId = envelope.long("eventId")
    envelope.long("taskId")?.let { if (it != expectedTaskId) return null }
    return when (category) {
        CHAT_REALTIME_CATEGORY -> {
            val messageElement = envelope["message"] ?: return null
            val message = runCatching {
                json.decodeFromJsonElement(ChatMessageDto.serializer(), messageElement)
            }.getOrNull() ?: return null
            if (message.taskId != expectedTaskId) return null
            val type = runCatching {
                TaskChatMessageEventType.valueOf(envelope.string("type") ?: "MESSAGE")
            }.getOrNull() ?: return null
            TaskChatRealtimeEvent.Message(message, type, eventId)
        }
        "TASK_CHAT_REACTION" -> {
            val messageId = envelope.long("messageId") ?: return null
            val reactionsElement = envelope["reactions"] ?: return null
            val reactions = runCatching {
                json.decodeFromJsonElement(ListSerializer(ReactionSummaryDto.serializer()), reactionsElement)
            }.getOrNull() ?: return null
            TaskChatRealtimeEvent.Reaction(messageId, reactions, eventId)
        }
        "TASK_CHAT_READ" -> TaskChatRealtimeEvent.Read(
            envelope.long("userId") ?: return null,
            envelope.long("lastReadMessageId") ?: return null,
            eventId
        )
        "TASK_CHAT_PIN" -> TaskChatRealtimeEvent.Pin(
            envelope["isPinned"]?.let { runCatching { it.jsonPrimitive.boolean }.getOrNull() } ?: return null,
            eventId
        )
        "TASK_CHAT_TYPING" -> TaskChatRealtimeEvent.Typing(
            envelope.long("userId") ?: return null,
            envelope.string("fullName") ?: return null,
            eventId
        )
        else -> null
    }
}

class ChatAccessRevokedException : Exception("Task chat access revoked")

fun isChatAccessTerminated(statusCode: Int?): Boolean =
    statusCode == HttpStatusCode.Unauthorized.value ||
        statusCode == HttpStatusCode.Forbidden.value ||
        statusCode == HttpStatusCode.NotFound.value

fun parseChatRealtimeMessage(json: Json, text: String, expectedTaskId: Long): ChatMessageDto? =
    (parseTaskChatRealtimeEvent(json, text, expectedTaskId) as? TaskChatRealtimeEvent.Message)
        ?.takeIf { it.type == TaskChatMessageEventType.MESSAGE }
        ?.message

fun List<ChatMessageDto>.mergeTaskChatHistory(incoming: List<ChatMessageDto>): List<ChatMessageDto> {
    val merged = associateByTo(linkedMapOf()) { it.id }
    incoming.forEach { candidate ->
        val current = merged[candidate.id]
        merged[candidate.id] = when {
            current == null -> candidate
            candidate.version > current.version -> candidate
            candidate.version < current.version -> current
            candidate.revokedAt != null -> candidate
            candidate.editedAt != null && current.editedAt == null -> candidate
            else -> current
        }
    }
    return merged.values.sortedBy { it.id }
}

fun List<ChatMessageDto>.appendDistinct(incoming: List<ChatMessageDto>): List<ChatMessageDto> =
    if (incoming.isEmpty()) this else mergeTaskChatHistory(incoming)

private fun JsonObject.string(name: String): String? =
    this[name]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }

private fun JsonObject.long(name: String): Long? =
    this[name]?.let { runCatching { it.jsonPrimitive.long }.getOrNull() }


fun List<ChatMessageDto>.mergeTaskChatMessage(event: TaskChatRealtimeEvent.Message): List<ChatMessageDto> {
    val existing = find { it.id == event.message.id }
    if (event.type == TaskChatMessageEventType.MESSAGE) {
        return if (existing == null) (this + event.message).sortedBy { it.id } else this
    }
    val incoming = if (event.type == TaskChatMessageEventType.REVOKED) {
        event.message.copy(content = "")
    } else {
        event.message
    }
    return if (existing == null) {
        (this + incoming).sortedBy { it.id }
    } else {
        map { if (it.id == incoming.id) incoming else it }.sortedBy { it.id }
    }
}

class TaskChatEventCursor(initialEventId: Long = 0) {
    var lastEventId: Long = initialEventId
        private set
    var lastMessageId: Long? = null
        private set

    fun accept(event: TaskChatRealtimeEvent): Boolean {
        val eventId = event.eventId
        if (eventId != null && eventId <= lastEventId) return false
        record(event)
        return true
    }

    fun record(event: TaskChatRealtimeEvent) {
        event.eventId?.let { lastEventId = maxOf(lastEventId, it) }
        if (event is TaskChatRealtimeEvent.Message) {
            lastMessageId = maxOf(lastMessageId ?: 0, event.message.id)
        }
    }

    fun parameters(): Pair<Long?, Long> = lastMessageId to lastEventId
}
