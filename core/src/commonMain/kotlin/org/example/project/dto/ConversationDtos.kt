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
    val type: ChatMessageType = ChatMessageType.USER,
    val content: String,
    val createdAt: String,
    val attachments: List<AttachmentDto> = emptyList(),
    val replyTo: ReplyPreviewDto? = null,
    val reactions: List<ReactionSummaryDto> = emptyList(),
    val mentionedUserIds: List<Long> = emptyList(),
    val readByCount: Int = 0,
    val readBy: List<ChatAuthorDto> = emptyList(),
    val editedAt: String? = null,
    val revokedAt: String? = null,
    val source: MessageSourceDto? = null,
    val version: Long = 0
)

@Serializable
data class MessageSourceDto(
    val kind: ConversationKind,
    val messageId: Long,
    val author: ChatAuthorDto? = null,
    val createdAt: String? = null
)

@Serializable
data class EditMessageRequest(
    val content: String,
    val version: Long? = null
)

@Serializable
data class ForwardMessageRequest(
    val targetKind: ConversationKind,
    val targetId: Long,
    val idempotencyKey: String
)

@Serializable
data class ConversationListItemDto(
    val kind: ConversationKind,
    val id: Long,
    val title: String? = null,
    val projectId: Long? = null,
    val taskId: Long? = null,
    val canWrite: Boolean = true,
    val members: List<ConversationMemberDto> = emptyList(),
    val lastMessagePreview: String? = null,
    val lastMessageAuthor: ChatAuthorDto? = null,
    val lastMessageAt: String? = null,
    val unreadCount: Int = 0,
    val mentionCount: Int = 0,
    val isPinned: Boolean = false,
    val isMuted: Boolean = false,
    val isArchived: Boolean = false
)

@Serializable
data class ConversationListPageDto(
    val items: List<ConversationListItemDto>,
    val nextCursor: String? = null,
    val hasMore: Boolean = false
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

@Serializable
data class ConversationDialogStateRequest(
    val pinned: Boolean? = null,
    val muted: Boolean? = null,
    val archived: Boolean? = null
)

@Serializable
enum class ConversationListFilter {
    ALL,
    UNREAD,
    MENTIONS,
    ARCHIVED
}
