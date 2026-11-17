package org.example.project.dto

import kotlinx.serialization.Serializable

@Serializable
enum class ChatMessageType {
    USER,
    SYSTEM
}

@Serializable
data class ChatAuthorDto(
    val userId: Long,
    val fullName: String
)

@Serializable
data class ReplyPreviewDto(
    val id: Long,
    val author: ChatAuthorDto? = null,
    val contentPreview: String
)

@Serializable
data class ReactionSummaryDto(
    val emoji: String,
    val count: Int,
    val reactedByMe: Boolean
)

@Serializable
data class ChatMessageDto(
    val id: Long,
    val taskId: Long,
    val author: ChatAuthorDto?,
    val type: ChatMessageType,
    val content: String,
    val createdAt: String,
    val attachments: List<AttachmentDto> = emptyList(),
    val replyTo: ReplyPreviewDto? = null,
    val reactions: List<ReactionSummaryDto> = emptyList(),
    val mentionedUserIds: List<Long> = emptyList(),
    val readByCount: Int = 0
)

@Serializable
data class ChatMessagePageDto(
    val messages: List<ChatMessageDto>,
    val nextAfter: Long? = null,
    val nextBefore: Long? = null,
    val hasMore: Boolean = false
)

@Serializable
data class SendChatMessageRequest(
    val content: String,
    val idempotencyKey: String,
    val replyToMessageId: Long? = null
)

@Serializable
data class AddSystemEventRequest(
    val content: String
)
