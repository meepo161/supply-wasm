package org.example.project.data

import kotlinx.coroutines.flow.Flow
import org.example.project.dto.NotificationDto
import org.example.project.dto.NotificationPageDto

interface NotificationRepository {
    suspend fun listNotifications(limit: Int = 50, after: Long? = null, before: Long? = null): ApiResult<NotificationPageDto>
    suspend fun markRead(id: Long): ApiResult<NotificationDto>
    fun observeNotifications(after: Long? = null): Flow<NotificationDto>
}
