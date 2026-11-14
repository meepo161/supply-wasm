package org.example.project.ui.home

import org.example.project.dto.ApiError
import org.example.project.dto.NotificationDto
import org.example.project.dto.TaskDto
import org.example.project.dto.TaskStatus

data class HomeUiState(
    val allTasks: List<TaskDto> = emptyList(),
    val notifications: List<NotificationDto> = emptyList(),
    val isLoadingTasks: Boolean = false,
    val isLoadingNotifications: Boolean = false,
    val operationError: ApiError? = null,
    val currentDate: String = currentIsoDate()
)

fun TaskDto.isOpen(): Boolean = status != TaskStatus.CLOSED && status != TaskStatus.CANCELLED

fun TaskDto.isOverdue(today: String): Boolean {
    val due = dueDate.toIsoDate() ?: return false
    return isOpen() && due < today
}

fun TaskDto.isUpcoming(today: String): Boolean {
    val due = dueDate.toIsoDate() ?: return false
    return isOpen() && due >= today
}
