package org.example.project.realtime

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.example.project.dto.ConversationMessageDto

class ConversationEventBus {

    data class Event(
        val eventId: Long?,
        val category: String,
        val type: String,
        val conversationId: Long,
        val messageId: Long? = null,
        val actorUserId: Long? = null,
        val fullName: String? = null,
        val lastReadMessageId: Long? = null,
        val message: ConversationMessageDto? = null
    )

    private val eventsFlow = MutableSharedFlow<Event>(extraBufferCapacity = 64)

    val events: SharedFlow<Event> = eventsFlow

    suspend fun publish(message: ConversationMessageDto, eventId: Long, type: String = "MESSAGE") {
        eventsFlow.emit(
            Event(
                eventId = eventId,
                category = if (type == "MESSAGE" || type == "EDITED" || type == "REVOKED") {
                    "CONVERSATION_MESSAGE"
                } else type,
                type = type,
                conversationId = message.conversationId,
                messageId = message.id,
                message = message
            )
        )
    }

    suspend fun publishEvent(event: Event) {
        eventsFlow.emit(event)
    }
}
