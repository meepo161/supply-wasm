package org.example.project.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.example.project.dto.ApiError
import org.example.project.dto.ConversationAiSummaryDto
import org.example.project.dto.ConversationDto
import org.example.project.dto.ConversationKind
import org.example.project.dto.ConversationListFilter
import org.example.project.dto.ConversationListPageDto
import org.example.project.dto.ConversationMessageDto
import org.example.project.dto.ConversationMessagePageDto
import org.example.project.dto.CreateConversationRequest
import org.example.project.dto.CreatePollRequest
import org.example.project.dto.PinnedMessageDto
import org.example.project.dto.ScheduledMessageRequest
import org.example.project.dto.SendChatMessageRequest
import org.example.project.dto.UpdateConversationMembersRequest
import org.example.project.dto.UpdateConversationRequest
import org.example.project.dto.UserPresenceDto
import org.example.project.dto.UserPresenceStatus
import org.example.project.dto.VotePollRequest

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

    suspend fun notifyTyping(conversationId: Long) {}

    suspend fun getOrCreateSavedMessages(): ApiResult<ConversationDto> =
        ApiResult.Error(ApiError("UNSUPPORTED", "Saved messages not supported"))

    suspend fun updateConversation(conversationId: Long, request: UpdateConversationRequest): ApiResult<ConversationDto> =
        ApiResult.Error(ApiError("UNSUPPORTED", "Conversation update not supported"))

    suspend fun updateMembers(conversationId: Long, request: UpdateConversationMembersRequest): ApiResult<ConversationDto> =
        ApiResult.Error(ApiError("UNSUPPORTED", "Members update not supported"))

    suspend fun leaveConversation(conversationId: Long): ApiResult<Unit> =
        ApiResult.Error(ApiError("UNSUPPORTED", "Leave conversation not supported"))

    suspend fun pinMessage(conversationId: Long, messageId: Long): ApiResult<List<PinnedMessageDto>> =
        ApiResult.Error(ApiError("UNSUPPORTED", "Pinning not supported"))

    suspend fun unpinMessage(conversationId: Long, messageId: Long): ApiResult<List<PinnedMessageDto>> =
        ApiResult.Error(ApiError("UNSUPPORTED", "Unpinning not supported"))

    suspend fun createPoll(conversationId: Long, request: CreatePollRequest): ApiResult<ConversationMessageDto> =
        ApiResult.Error(ApiError("UNSUPPORTED", "Polls not supported"))

    suspend fun votePoll(conversationId: Long, pollId: Long, request: VotePollRequest): ApiResult<ConversationMessageDto> =
        ApiResult.Error(ApiError("UNSUPPORTED", "Poll voting not supported"))

    suspend fun listThread(conversationId: Long, rootMessageId: Long): ApiResult<List<ConversationMessageDto>> =
        ApiResult.Error(ApiError("UNSUPPORTED", "Threads not supported"))

    suspend fun sendThreadReply(
        conversationId: Long,
        rootMessageId: Long,
        request: SendChatMessageRequest
    ): ApiResult<ConversationMessageDto> =
        ApiResult.Error(ApiError("UNSUPPORTED", "Thread replies not supported"))

    suspend fun scheduleOrSendSilent(
        conversationId: Long,
        request: ScheduledMessageRequest
    ): ApiResult<ConversationMessageDto> =
        ApiResult.Error(ApiError("UNSUPPORTED", "Scheduled messages not supported"))

    suspend fun listScheduled(conversationId: Long): ApiResult<List<ConversationMessageDto>> =
        ApiResult.Error(ApiError("UNSUPPORTED", "Scheduled messages list not supported"))

    suspend fun summarizeConversation(conversationId: Long): ApiResult<ConversationAiSummaryDto> =
        ApiResult.Error(ApiError("UNSUPPORTED", "AI summary not supported"))

    suspend fun listPresence(): ApiResult<List<UserPresenceDto>> =
        ApiResult.Error(ApiError("UNSUPPORTED", "Presence not supported"))

    suspend fun updatePresence(status: UserPresenceStatus): ApiResult<UserPresenceDto> =
        ApiResult.Error(ApiError("UNSUPPORTED", "Presence update not supported"))
}
