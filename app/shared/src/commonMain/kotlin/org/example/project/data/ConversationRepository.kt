package org.example.project.data

import kotlinx.coroutines.flow.Flow
import org.example.project.dto.ConversationDto
import org.example.project.dto.ConversationMessageDto
import org.example.project.dto.ConversationMessagePageDto
import org.example.project.dto.CreateConversationRequest
import org.example.project.dto.SendChatMessageRequest

interface ConversationRepository {
    suspend fun list(): ApiResult<List<ConversationDto>>
    suspend fun get(id: Long): ApiResult<ConversationDto>
    suspend fun create(request: CreateConversationRequest): ApiResult<ConversationDto>

    /** Get-or-create проектного обсуждения; права проверяет сервер. */
    suspend fun getOrCreateProjectChat(projectId: Long): ApiResult<ConversationDto>

    suspend fun listMessages(
        conversationId: Long,
        after: Long? = null,
        before: Long? = null,
        limit: Int = 50
    ): ApiResult<ConversationMessagePageDto>

    suspend fun sendMessage(
        conversationId: Long,
        request: SendChatMessageRequest,
        file: AttachmentFileData? = null
    ): ApiResult<ConversationMessageDto>

    fun observeMessages(conversationId: Long, after: Long? = null): Flow<ConversationMessageDto>
}
