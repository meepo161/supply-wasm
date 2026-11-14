package org.example.project.data

import org.example.project.dto.NotificationDto
import org.example.project.dto.NotificationPageDto

interface NotificationRepository {
    suspend fun listNotifications(limit: Int = 50, after: Long? = null, before: Long? = null): ApiResult<NotificationPageDto>
    suspend fun markRead(id: Long): ApiResult<NotificationDto>
}
