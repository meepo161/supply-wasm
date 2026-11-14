package org.example.project.ui.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.example.project.data.ApiResult
import org.example.project.data.NotificationRepository
import org.example.project.data.TaskRepository
import org.example.project.dto.NotificationDto
import org.example.project.dto.UserDto

class NotificationViewModel(
    private val notificationRepository: NotificationRepository,
    private val taskRepository: TaskRepository,
    val currentUser: UserDto
) : ViewModel() {

    private val _uiState = MutableStateFlow(NotificationUiState())
    val uiState: StateFlow<NotificationUiState> = _uiState.asStateFlow()

    init {
        loadNotifications()
    }

    fun dismissError() {
        _uiState.value = _uiState.value.copy(operationError = null)
    }

    fun loadNotifications() {
        _uiState.value = _uiState.value.copy(isLoading = true, operationError = null)
        viewModelScope.launch {
            when (val result = notificationRepository.listNotifications(limit = 50)) {
                is ApiResult.Success -> {
                    val page = result.data
                    _uiState.value = _uiState.value.copy(
                        notifications = page.notifications,
                        nextAfter = page.nextAfter,
                        hasMore = page.hasMore,
                        isLoading = false,
                        operationError = null
                    )
                }
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    operationError = result.error
                )
            }
        }
    }

    fun loadMore() {
        val after = _uiState.value.nextAfter ?: return
        _uiState.value = _uiState.value.copy(isLoading = true, operationError = null)
        viewModelScope.launch {
            when (val result = notificationRepository.listNotifications(limit = 50, after = after)) {
                is ApiResult.Success -> {
                    val page = result.data
                    _uiState.value = _uiState.value.copy(
                        notifications = _uiState.value.notifications + page.notifications,
                        nextAfter = page.nextAfter,
                        hasMore = page.hasMore,
                        isLoading = false,
                        operationError = null
                    )
                }
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    operationError = result.error
                )
            }
        }
    }

    fun markRead(notification: NotificationDto) {
        if (notification.isRead) return
        viewModelScope.launch {
            when (val result = notificationRepository.markRead(notification.id)) {
                is ApiResult.Success -> {
                    val updated = result.data
                    _uiState.value = _uiState.value.copy(
                        notifications = _uiState.value.notifications.map {
                            if (it.id == updated.id) updated else it
                        }
                    )
                }
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(operationError = result.error)
            }
        }
    }

    fun openTask(taskId: Long, onAccessGranted: (Long) -> Unit) {
        _uiState.value = _uiState.value.copy(operationError = null)
        viewModelScope.launch {
            when (val result = taskRepository.get(taskId)) {
                is ApiResult.Success -> onAccessGranted(result.data.id)
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(operationError = result.error)
            }
        }
    }
}
