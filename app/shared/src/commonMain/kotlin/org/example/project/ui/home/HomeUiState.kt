package org.example.project.ui.home

import org.example.project.data.AttachmentFileData
import org.example.project.dto.ApiError
import org.example.project.dto.AssistantConversationSummaryDto
import org.example.project.dto.AssistantMessageDto
import org.example.project.dto.DemandHeaderDto
import org.example.project.dto.NotificationDto
import org.example.project.dto.TaskDto
import org.example.project.dto.TaskStatus

data class HomeActionQueueItem(
    val title: String,
    val subtitle: String,
    val count: Int,
    val destination: String
)

data class HomeUiState(
    val allTasks: List<TaskDto> = emptyList(),
    val notifications: List<NotificationDto> = emptyList(),
    val actionQueue: List<HomeActionQueueItem> = emptyList(),
    val activeDemands: List<DemandHeaderDto> = emptyList(),
    val isLoadingTasks: Boolean = false,
    val isLoadingNotifications: Boolean = false,
    val operationError: ApiError? = null,
    val currentDate: String = currentIsoDate(),
    val assistantAvailable: Boolean = false,
    val assistantModel: String = "qwen3.5:9b",
    val assistantStatusReason: String? = null,
    val assistantConversations: List<AssistantConversationSummaryDto> = emptyList(),
    val assistantConversationId: Long? = null,
    val assistantMessages: List<AssistantMessageDto> = emptyList(),
    val assistantPendingFiles: List<AttachmentFileData> = emptyList(),
    val assistantInput: String = "",
    val assistantLoading: Boolean = false,
    val assistantError: String? = null
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
