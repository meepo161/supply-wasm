package org.example.project.realtime

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.example.project.dto.NotificationDto

class NotificationEventBus {

    data class Event(val recipientId: Long, val notification: NotificationDto)

    private val events = MutableSharedFlow<Event>(extraBufferCapacity = 64)

    val notifications: SharedFlow<Event> = events

    fun publish(event: Event) {
        events.tryEmit(event)
    }

    fun publishAll(created: List<Event>) {
        created.forEach { publish(it) }
    }
}
