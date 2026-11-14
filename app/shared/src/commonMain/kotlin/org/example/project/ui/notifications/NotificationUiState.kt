package org.example.project.ui.notifications

import org.example.project.dto.ApiError
import org.example.project.dto.NotificationDto

data class NotificationUiState(
    val notifications: List<NotificationDto> = emptyList(),
    val nextAfter: Long? = null,
    val hasMore: Boolean = false,
    val isLoading: Boolean = false,
    val operationError: ApiError? = null
)
