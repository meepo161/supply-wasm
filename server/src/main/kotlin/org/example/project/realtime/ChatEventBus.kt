package org.example.project.realtime

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.example.project.dto.ChatMessageDto

class ChatEventBus {

    private val events = MutableSharedFlow<ChatMessageDto>(extraBufferCapacity = 64)

    val messages: SharedFlow<ChatMessageDto> = events

    fun publish(message: ChatMessageDto) {
        events.tryEmit(message)
    }
}
