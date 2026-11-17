package org.example.project.dto

import kotlinx.serialization.Serializable

@Serializable
enum class ConversationKind {
    DIRECT,
    GROUP,
    PROJECT,
    TASK
}

@Serializable
data class ConversationMemberDto(
    val userId: Long,
    val fullName: String
)

@Serializable
data class ConversationDto(
    val id: Long,
    val kind: ConversationKind,
    val title: String? = null,
    val projectId: Long? = null,
    val taskId: Long? = null,
    val canWrite: Boolean = true,
    val members: List<ConversationMemberDto> = emptyList()
)

@Serializable
data class ConversationMessageDto(
    val id: Long,
    val conversationId: Long,
    val author: ChatAuthorDto? = null,
    val content: String,
    val createdAt: String,
    val attachments: List<AttachmentDto> = emptyList()
)

@Serializable
data class ConversationMessagePageDto(
    val messages: List<ConversationMessageDto>,
    val nextAfter: Long? = null,
    val nextBefore: Long? = null,
    val hasMore: Boolean = false
)

@Serializable
data class CreateConversationRequest(
    val kind: ConversationKind,
    val title: String? = null,
    val memberIds: List<Long> = emptyList()
)
