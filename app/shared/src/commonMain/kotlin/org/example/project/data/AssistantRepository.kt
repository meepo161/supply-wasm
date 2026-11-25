package org.example.project.data

import org.example.project.dto.AssistantChatRequestDto
import org.example.project.dto.AssistantChatResponseDto
import org.example.project.dto.AssistantConfirmDraftRequestDto
import org.example.project.dto.AssistantConfirmDraftResponseDto
import org.example.project.dto.AssistantConversationDetailDto
import org.example.project.dto.AssistantConversationSummaryDto
import org.example.project.dto.AssistantStatusDto

interface AssistantRepository {
    suspend fun status(): ApiResult<AssistantStatusDto>
    suspend fun listConversations(): ApiResult<List<AssistantConversationSummaryDto>>
    suspend fun getConversation(id: Long): ApiResult<AssistantConversationDetailDto>
    suspend fun deleteConversation(id: Long): ApiResult<Unit>
    suspend fun chat(request: AssistantChatRequestDto): ApiResult<AssistantChatResponseDto>
    suspend fun confirmDraft(request: AssistantConfirmDraftRequestDto): ApiResult<AssistantConfirmDraftResponseDto>
}
