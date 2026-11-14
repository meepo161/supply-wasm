package org.example.project.data

import kotlinx.coroutines.flow.Flow
import org.example.project.dto.ChatMessageDto
import org.example.project.dto.ChatMessagePageDto
import org.example.project.dto.SendChatMessageRequest

interface ChatRepository {
    suspend fun listMessages(
        taskId: Long,
        after: Long? = null,
        before: Long? = null,
        limit: Int = 50
    ): ApiResult<ChatMessagePageDto>

    suspend fun sendMessage(taskId: Long, request: SendChatMessageRequest): ApiResult<ChatMessageDto>

    fun observeMessages(taskId: Long, after: Long? = null): Flow<ChatMessageDto>
}
