package org.example.project.dto

import kotlinx.serialization.Serializable

@Serializable
data class NotificationDto(
    val id: Long,
    val type: String,
    val entityType: String,
    val entityId: Long,
    val title: String,
    val isRead: Boolean,
    val createdAt: String,
    val taskId: Long? = null
)

@Serializable
data class NotificationPageDto(
    val notifications: List<NotificationDto>,
    val nextAfter: Long? = null,
    val nextBefore: Long? = null,
    val hasMore: Boolean = false
)
