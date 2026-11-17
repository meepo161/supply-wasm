package org.example.project.dto

import kotlinx.serialization.Serializable

@Serializable
enum class TaskChatListFilter {
    ALL,
    UNREAD,
    MENTIONS,
    PINNED
}

@Serializable
data class TaskChatListItemDto(
    val taskId: Long,
    val number: String,
    val title: String,
    val status: String,
    val priority: String,
    val assigneeId: Long? = null,
    val lastMessageId: Long? = null,
    val lastMessageSnippet: String? = null,
    val lastMessageAuthor: String? = null,
    val lastMessageAt: String? = null,
    val unreadCount: Int = 0,
    val mentionCount: Int = 0,
    val isPinned: Boolean = false
)

@Serializable
data class MarkChatReadRequest(
    val lastReadMessageId: Long
)

@Serializable
data class ReactionRequest(
    val emoji: String
)

@Serializable
data class ChatSearchResultDto(
    val messageId: Long,
    val taskId: Long,
    val content: String,
    val createdAt: String,
    val author: ChatAuthorDto? = null
)
