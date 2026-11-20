package org.example.project.data

import io.ktor.http.HttpStatusCode
import io.ktor.websocket.CloseReason
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.example.project.dto.ConversationMessageDto
import org.example.project.dto.ReactionSummaryDto

const val CONVERSATION_REALTIME_CATEGORY = "CONVERSATION_MESSAGE"

class ConversationAccessRevokedException : Exception("Conversation access revoked")

fun isConversationAccessTerminated(statusCode: Int?): Boolean =
    statusCode == HttpStatusCode.Unauthorized.value ||
        statusCode == HttpStatusCode.Forbidden.value ||
        statusCode == HttpStatusCode.NotFound.value

enum class ConversationMessageEventType { MESSAGE, EDITED, REVOKED }

sealed interface ConversationRealtimeEvent {
    val eventId: Long?

    data class Message(
        val message: ConversationMessageDto,
        val type: ConversationMessageEventType = ConversationMessageEventType.MESSAGE,
        override val eventId: Long? = null
    ) : ConversationRealtimeEvent

    data class Reaction(
        val messageId: Long,
        val reactions: List<ReactionSummaryDto>,
        override val eventId: Long? = null
    ) : ConversationRealtimeEvent

    data class Read(
        val userId: Long,
        val lastReadMessageId: Long,
        override val eventId: Long? = null
    ) : ConversationRealtimeEvent

    data class Pin(val isPinned: Boolean, override val eventId: Long? = null) : ConversationRealtimeEvent

    data class Typing(
        val userId: Long,
        val fullName: String,
        override val eventId: Long? = null
    ) : ConversationRealtimeEvent
}

fun parseConversationRealtimeEvent(
    json: Json,
    text: String,
    expectedConversationId: Long
): ConversationRealtimeEvent? {
    val envelope = (runCatching { json.parseToJsonElement(text) }.getOrNull()) as? JsonObject ?: return null
    val category = envelope.string("category") ?: return null
    val eventId = envelope.long("eventId")
    envelope.long("conversationId")?.let { if (it != expectedConversationId) return null }
    return when (category) {
        CONVERSATION_REALTIME_CATEGORY -> {
            val messageElement = envelope["message"] ?: return null
            val message = runCatching {
                json.decodeFromJsonElement(ConversationMessageDto.serializer(), messageElement)
            }.getOrNull() ?: return null
            if (message.conversationId != expectedConversationId) return null
            val type = runCatching {
                ConversationMessageEventType.valueOf(envelope.string("type") ?: "MESSAGE")
            }.getOrNull() ?: return null
            ConversationRealtimeEvent.Message(message, type, eventId)
        }
        "CONVERSATION_REACTION" -> {
            val messageId = envelope.long("messageId") ?: return null
            val reactionsElement = envelope["reactions"] ?: return null
            val reactions = runCatching {
                json.decodeFromJsonElement(ListSerializer(ReactionSummaryDto.serializer()), reactionsElement)
            }.getOrNull() ?: return null
            ConversationRealtimeEvent.Reaction(messageId, reactions, eventId)
        }
        "CONVERSATION_READ" -> ConversationRealtimeEvent.Read(
            envelope.long("userId") ?: return null,
            envelope.long("lastReadMessageId") ?: return null,
            eventId
        )
        "CONVERSATION_PIN" -> ConversationRealtimeEvent.Pin(
            envelope["isPinned"]?.let { runCatching { it.jsonPrimitive.boolean }.getOrNull() } ?: return null,
            eventId
        )
        "CONVERSATION_TYPING" -> ConversationRealtimeEvent.Typing(
            envelope.long("userId") ?: return null,
            envelope.string("fullName") ?: return null,
            eventId
        )
        else -> null
    }
}

fun parseConversationRealtimeMessage(
    json: Json,
    text: String,
    expectedConversationId: Long
): ConversationMessageDto? =
    (parseConversationRealtimeEvent(json, text, expectedConversationId) as? ConversationRealtimeEvent.Message)
        ?.takeIf { it.type == ConversationMessageEventType.MESSAGE }
        ?.message

class ConversationRealtimeMerger(
    initialMessages: List<ConversationMessageDto> = emptyList(),
    initialEventId: Long = 0
) {
    var messages: List<ConversationMessageDto> = initialMessages.appendDistinctMessages(emptyList())
        private set
    var lastEventId: Long = initialEventId
        private set
    var readCursors: Map<Long, Long> = emptyMap()
        private set
    var isPinned: Boolean = false
        private set

    fun replaceHistory(history: List<ConversationMessageDto>) {
        messages = history.appendDistinctMessages(emptyList())
    }

    fun apply(event: ConversationRealtimeEvent): Boolean {
        val eventId = event.eventId
        if (eventId != null && eventId <= lastEventId) return false
        if (eventId != null) lastEventId = eventId
        return when (event) {
            is ConversationRealtimeEvent.Message -> applyMessage(event)
            is ConversationRealtimeEvent.Reaction -> {
                val old = messages.find { it.id == event.messageId } ?: return false
                messages = messages.map { if (it.id == old.id) it.copy(reactions = event.reactions) else it }
                true
            }
            is ConversationRealtimeEvent.Read -> {
                val previous = readCursors[event.userId] ?: 0
                readCursors = readCursors + (event.userId to maxOf(previous, event.lastReadMessageId))
                event.lastReadMessageId > previous
            }
            is ConversationRealtimeEvent.Pin -> {
                val changed = isPinned != event.isPinned
                isPinned = event.isPinned
                changed
            }
            is ConversationRealtimeEvent.Typing -> true
        }
    }

    private fun applyMessage(event: ConversationRealtimeEvent.Message): Boolean {
        val incoming = if (event.type == ConversationMessageEventType.REVOKED) {
            event.message.copy(content = "")
        } else {
            event.message
        }
        val existing = messages.find { it.id == incoming.id }
        if (event.type == ConversationMessageEventType.MESSAGE && existing == incoming) return false
        messages = if (existing == null) {
            messages.appendDistinctMessages(listOf(incoming))
        } else {
            messages.map { if (it.id == incoming.id) incoming else it }.sortedBy { it.id }
        }
        return existing != incoming
    }
}

fun realtimeCursorParameters(after: Long?, afterEvent: Long?): Map<String, String> =
    buildMap {
        after?.let { put("after", it.toString()) }
        afterEvent?.let { put("afterEvent", it.toString()) }
    }

private fun JsonObject.string(name: String): String? =
    this[name]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }

private fun JsonObject.long(name: String): Long? =
    this[name]?.let { runCatching { it.jsonPrimitive.long }.getOrNull() }

fun List<ConversationMessageDto>.appendDistinctMessages(
    incoming: List<ConversationMessageDto>
): List<ConversationMessageDto> {
    if (incoming.isEmpty()) return distinctBy { it.id }.sortedBy { it.id }
    return (this + incoming).distinctBy { it.id }.sortedBy { it.id }
}


enum class ConversationDisconnect { CLOSED, OVERFLOW, NETWORK, ACCESS_REVOKED }

class ConversationReconnectCursor(initialEventId: Long? = null) {
    var lastMessageId: Long? = null
        private set
    var lastEventId: Long? = initialEventId
        private set

    fun record(event: ConversationRealtimeEvent) {
        event.eventId?.let { lastEventId = maxOf(lastEventId ?: 0, it) }
        if (event is ConversationRealtimeEvent.Message) recordHistory(listOf(event.message))
    }

    fun recordHistory(messages: List<ConversationMessageDto>) {
        messages.maxOfOrNull { it.id }?.let { lastMessageId = maxOf(lastMessageId ?: 0, it) }
    }

    fun parameters(): Map<String, String> = realtimeCursorParameters(lastMessageId, lastEventId)

    fun shouldReconnect(reason: ConversationDisconnect): Boolean =
        reason != ConversationDisconnect.ACCESS_REVOKED
}


class ConversationOverflowException : Exception("Conversation event overflow; reconnect")

fun isConversationOverflowClose(reason: CloseReason?): Boolean =
    reason?.code == CloseReason.Codes.INTERNAL_ERROR.code &&
        reason.message.contains("overflow", ignoreCase = true)
