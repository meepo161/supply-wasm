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
data class ChatMessageDto(
    val id: Long,
    val taskId: Long,
    val author: ChatAuthorDto?,
    val type: ChatMessageType,
    val content: String,
    val createdAt: String
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
    val idempotencyKey: String
)

@Serializable
data class AddSystemEventRequest(
    val content: String
)
