package org.example.project.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.example.project.dto.ApiError
import org.example.project.dto.ConversationDto
import org.example.project.dto.ConversationKind
import org.example.project.dto.ConversationListFilter
import org.example.project.dto.ConversationListPageDto
import org.example.project.dto.ConversationMessageDto
import org.example.project.dto.ConversationMessagePageDto
import org.example.project.dto.CreateConversationRequest
import org.example.project.dto.SendChatMessageRequest

interface ConversationRepository {
    suspend fun list(): ApiResult<List<ConversationDto>>
    suspend fun get(id: Long): ApiResult<ConversationDto>
    suspend fun create(request: CreateConversationRequest): ApiResult<ConversationDto>

    suspend fun listWorkspace(
        query: String? = null,
        kind: ConversationKind? = null,
        filter: ConversationListFilter = ConversationListFilter.ALL,
        cursor: String? = null,
        limit: Int = 50
    ): ApiResult<ConversationListPageDto> = ApiResult.Error(
        ApiError("UNSUPPORTED", "Unified conversation list is not supported")
    )

    /** Get-or-create проектного обсуждения; права проверяет сервер. */
    suspend fun getOrCreateProjectChat(projectId: Long): ApiResult<ConversationDto>

    suspend fun listMessages(
        conversationId: Long,
        after: Long? = null,
        before: Long? = null,
        limit: Int = 50
    ): ApiResult<ConversationMessagePageDto>

    suspend fun searchMessages(
        conversationId: Long,
        query: String,
        after: Long? = null,
        limit: Int = 50
    ): ApiResult<ConversationMessagePageDto> = ApiResult.Error(
        ApiError("UNSUPPORTED", "Message search is not supported")
    )

    suspend fun markRead(conversationId: Long, lastReadMessageId: Long): ApiResult<Long> = ApiResult.Error(
        ApiError("UNSUPPORTED", "Read marks are not supported")
    )

    suspend fun sendMessage(
        conversationId: Long,
        request: SendChatMessageRequest,
        file: AttachmentFileData? = null
    ): ApiResult<ConversationMessageDto>

    suspend fun sendMessage(
        conversationId: Long,
        request: SendChatMessageRequest,
        files: List<AttachmentFileData>
    ): ApiResult<ConversationMessageDto> =
        if (files.size <= 1) {
            sendMessage(conversationId, request, files.singleOrNull())
        } else {
            ApiResult.Error(ApiError("UNSUPPORTED", "Multiple message files are not supported"))
        }

    fun observeMessages(conversationId: Long, after: Long? = null): Flow<ConversationMessageDto>

    fun observeEvents(
        conversationId: Long,
        after: Long? = null,
        afterEvent: Long? = null
    ): Flow<ConversationRealtimeEvent> = observeMessages(conversationId, after).map {
        ConversationRealtimeEvent.Message(it)
    }
}
