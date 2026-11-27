package org.example.project.dto

import kotlinx.serialization.Serializable

@Serializable
enum class ConversationKind {
    DIRECT,
    GROUP,
    PROJECT,
    TASK,
    CHANNEL
}

@Serializable
enum class UserPresenceStatus {
    ONLINE,
    AWAY,
    DND,
    OFFLINE
}

@Serializable
data class UserPresenceDto(
    val userId: Long,
    val status: UserPresenceStatus = UserPresenceStatus.ONLINE,
    val lastSeenAt: String? = null
)

@Serializable
data class UpdatePresenceRequest(
    val status: UserPresenceStatus
)

@Serializable
data class PollOptionDto(
    val id: Long,
    val label: String,
    val votesCount: Int = 0,
    val votedByMe: Boolean = false
)

@Serializable
data class ConversationPollDto(
    val id: Long,
    val messageId: Long,
    val question: String,
    val isAnonymous: Boolean = false,
    val isMultiple: Boolean = false,
    val closed: Boolean = false,
    val totalVotes: Int = 0,
    val options: List<PollOptionDto> = emptyList()
)

@Serializable
data class CreatePollRequest(
    val question: String,
    val options: List<String>,
    val isAnonymous: Boolean = false,
    val isMultiple: Boolean = false
)

@Serializable
data class VotePollRequest(
    val optionIds: List<Long>
)

@Serializable
data class PinnedMessageDto(
    val messageId: Long,
    val contentPreview: String,
    val authorName: String? = null,
    val pinnedAt: String
)

@Serializable
data class UpdateConversationRequest(
    val title: String? = null,
    val description: String? = null
)

@Serializable
data class UpdateConversationMembersRequest(
    val addMemberIds: List<Long> = emptyList(),
    val removeMemberIds: List<Long> = emptyList()
)

@Serializable
data class ConversationAiSummaryDto(
    val summary: String,
    val actionItems: List<String> = emptyList(),
    val messageCount: Int = 0
)

@Serializable
data class ScheduledMessageRequest(
    val content: String,
    val scheduledAt: String? = null,
    val isSilent: Boolean = false,
    val replyToMessageId: Long? = null,
    val threadRootId: Long? = null,
    val idempotencyKey: String
)

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
    val isSavedMessages: Boolean = false,
    val description: String? = null,
    val members: List<ConversationMemberDto> = emptyList(),
    val pinnedMessages: List<PinnedMessageDto> = emptyList()
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
    val version: Long = 0,
    val threadRootId: Long? = null,
    val threadReplyCount: Int = 0,
    val isSilent: Boolean = false,
    val scheduledAt: String? = null,
    val delivered: Boolean = true,
    val poll: ConversationPollDto? = null,
    val isPinned: Boolean = false
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
    val isSavedMessages: Boolean = false,
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
