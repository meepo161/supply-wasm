package org.example.project.realtime

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.example.project.dto.ChatMessageDto
import org.example.project.dto.ReactionSummaryDto

class ChatEventBus {

    data class WorkspaceEvent(
        val category: String,
        val taskId: Long,
        val messageId: Long? = null,
        val userId: Long? = null,
        val fullName: String? = null,
        val reactions: List<ReactionSummaryDto>? = null,
        val lastReadMessageId: Long? = null,
        val isPinned: Boolean? = null
    )

    private val events = MutableSharedFlow<ChatMessageDto>(extraBufferCapacity = 64)

    val messages: SharedFlow<ChatMessageDto> = events

    private val workspaceEventsFlow = MutableSharedFlow<WorkspaceEvent>(extraBufferCapacity = 64)

    val workspaceEvents: SharedFlow<WorkspaceEvent> = workspaceEventsFlow

    fun publish(message: ChatMessageDto) {
        events.tryEmit(message)
    }

    fun publishEvent(event: WorkspaceEvent) {
        workspaceEventsFlow.tryEmit(event)
    }
}
