package org.example.project.realtime

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.withTimeout
import org.example.project.dto.ChatMessageDto

class ChatEventBus {

    data class Event(
        val eventId: Long?,
        val category: String,
        val type: String,
        val taskId: Long,
        val messageId: Long? = null,
        val actorUserId: Long? = null,
        val fullName: String? = null,
        val lastReadMessageId: Long? = null,
        val isPinned: Boolean? = null
    )

    data class Overflow(val taskId: Long)

    private val eventsFlow = MutableSharedFlow<Event>(extraBufferCapacity = 64)
    private val overflowFlow = MutableSharedFlow<Overflow>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val events: SharedFlow<Event> = eventsFlow
    val overflow: SharedFlow<Overflow> = overflowFlow

    suspend fun publish(message: ChatMessageDto, eventId: Long, type: String = "MESSAGE") {
        publishEvent(
            Event(
                eventId = eventId,
                category = "TASK_CHAT_MESSAGE",
                type = type,
                taskId = message.taskId,
                messageId = message.id
            )
        )
    }

    suspend fun publishEvent(event: Event) {
        try {
            withTimeout(EVENT_EMIT_TIMEOUT_MS) { eventsFlow.emit(event) }
        } catch (_: TimeoutCancellationException) {
            overflowFlow.tryEmit(Overflow(event.taskId))
        }
    }

    internal companion object {
        const val EVENT_EMIT_TIMEOUT_MS = 250L
    }
}
