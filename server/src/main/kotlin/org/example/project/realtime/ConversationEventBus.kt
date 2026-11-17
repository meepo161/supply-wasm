package org.example.project.realtime

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.example.project.dto.ConversationMessageDto

class ConversationEventBus {

    private val events = MutableSharedFlow<ConversationMessageDto>(extraBufferCapacity = 64)

    val messages: SharedFlow<ConversationMessageDto> = events

    fun publish(message: ConversationMessageDto) {
        events.tryEmit(message)
    }
}
