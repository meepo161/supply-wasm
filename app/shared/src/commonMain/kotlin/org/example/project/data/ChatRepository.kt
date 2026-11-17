package org.example.project.data

import kotlinx.coroutines.flow.Flow
import org.example.project.dto.ChatMessageDto
import org.example.project.dto.ChatMessagePageDto
import org.example.project.dto.ChatSearchResultDto
import org.example.project.dto.SendChatMessageRequest
import org.example.project.dto.TaskChatListFilter
import org.example.project.dto.TaskChatListItemDto

interface ChatRepository {
    suspend fun listMessages(
        taskId: Long,
        after: Long? = null,
        before: Long? = null,
        limit: Int = 50
    ): ApiResult<ChatMessagePageDto>

    suspend fun sendMessage(
        taskId: Long,
        request: SendChatMessageRequest,
        file: AttachmentFileData? = null
    ): ApiResult<ChatMessageDto>

    fun observeMessages(taskId: Long, after: Long? = null): Flow<ChatMessageDto>

    /** Типизированный поток событий WS; outgoing несёт клиентские команды (typing). */
    fun observeEvents(taskId: Long, after: Long? = null, outgoing: Flow<String>): Flow<TaskChatRealtimeEvent>

    suspend fun listChatTasks(
        query: String? = null,
        filter: TaskChatListFilter = TaskChatListFilter.ALL,
        limit: Int = 200
    ): ApiResult<List<TaskChatListItemDto>>

    suspend fun markChatRead(taskId: Long, lastReadMessageId: Long): ApiResult<Boolean>

    /** active=true → PUT, иначе DELETE. */
    suspend fun setChatReaction(
        taskId: Long,
        messageId: Long,
        emoji: String,
        active: Boolean
    ): ApiResult<Boolean>

    suspend fun setChatPinned(taskId: Long, pinned: Boolean): ApiResult<Boolean>

    suspend fun searchChatMessages(
        taskId: Long,
        query: String,
        limit: Int = 20
    ): ApiResult<List<ChatSearchResultDto>>
}
