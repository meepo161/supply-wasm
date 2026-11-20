package org.example.project.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.example.project.data.ApiResult
import org.example.project.data.ConversationRepository
import org.example.project.data.NotificationRepository
import org.example.project.data.TaskRepository
import org.example.project.dto.ApiError
import org.example.project.dto.ConversationKind
import org.example.project.dto.NotificationDto
import org.example.project.dto.TaskDto
import org.example.project.dto.UserDto

class HomeViewModel(
    private val taskRepository: TaskRepository,
    private val notificationRepository: NotificationRepository,
    private val conversationRepository: ConversationRepository,
    val currentUser: UserDto
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private var lastNotifiedUnreadCount: Int = -1

    init {
        loadTasks()
        loadNotifications()
    }

    fun dismissError() {
        _uiState.value = _uiState.value.copy(operationError = null)
    }

    fun refresh() {
        _uiState.value = _uiState.value.copy(currentDate = currentIsoDate())
        loadTasks()
        loadNotifications()
    }

    fun loadTasks() {
        _uiState.value = _uiState.value.copy(isLoadingTasks = true, operationError = null)
        viewModelScope.launch {
            when (val result = taskRepository.list()) {
                is ApiResult.Success -> _uiState.value = _uiState.value.copy(
                    allTasks = result.data,
                    isLoadingTasks = false,
                    operationError = null
                )
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    isLoadingTasks = false,
                    operationError = result.error
                )
            }
        }
    }

    fun loadNotifications() {
        _uiState.value = _uiState.value.copy(isLoadingNotifications = true, operationError = null)
        viewModelScope.launch {
            when (val result = notificationRepository.listNotifications(limit = 50)) {
                is ApiResult.Success -> onNotificationsUpdated(result.data.notifications)
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    isLoadingNotifications = false,
                    operationError = result.error
                )
            }
        }
    }

    fun onNotificationsUpdated(list: List<NotificationDto>) {
        _uiState.value = _uiState.value.copy(
            notifications = list,
            isLoadingNotifications = false,
            operationError = null
        )
        maybeShowBrowserNotification(list)
    }

    private fun maybeShowBrowserNotification(list: List<NotificationDto>) {
        val unreadCount = list.count { !it.isRead }
        if (lastNotifiedUnreadCount != -1 && unreadCount > lastNotifiedUnreadCount) {
            if (BrowserNotifications.isGranted()) {
                BrowserNotifications.show("Новое событие", "ERP")
            }
        }
        lastNotifiedUnreadCount = unreadCount
    }

    fun requestBrowserNotificationPermission(onResult: (Boolean) -> Unit) {
        BrowserNotifications.requestPermission(onResult)
    }

    fun openTask(taskId: Long, onAccessGranted: (Long) -> Unit) {
        _uiState.value = _uiState.value.copy(operationError = null)
        viewModelScope.launch {
            when (val result = taskRepository.get(taskId)) {
                is ApiResult.Success -> {
                    onAccessGranted(result.data.id)
                }
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(operationError = result.error)
            }
        }
    }

    fun openNotificationEntity(
        notification: NotificationDto,
        onOpenTask: (Long) -> Unit,
        onOpenConversation: (Long) -> Unit,
        onOpenProjectChat: (Long) -> Unit
    ) {
        if (notification.entityType.equals("CONVERSATION", ignoreCase = true)) {
            _uiState.value = _uiState.value.copy(operationError = null)
            viewModelScope.launch {
                when (val result = conversationRepository.get(notification.entityId)) {
                    is ApiResult.Success -> {
                        val conversation = result.data
                        when (conversation.kind) {
                            ConversationKind.PROJECT -> {
                                val projectId = conversation.projectId
                                if (projectId != null) {
                                    onOpenProjectChat(projectId)
                                } else {
                                    _uiState.value = _uiState.value.copy(
                                        operationError = ApiError(
                                            "UNKNOWN_ERROR",
                                            "У проектного чата нет связанного проекта"
                                        )
                                    )
                                }
                            }
                            else -> onOpenConversation(conversation.id)
                        }
                    }
                    is ApiResult.Error -> _uiState.value = _uiState.value.copy(operationError = result.error)
                }
            }
            return
        }
        val taskId = notification.taskId ?: notification.entityId.takeIf {
            notification.entityType.equals("TASK", ignoreCase = true)
        }
        taskId?.let { openTask(it, onOpenTask) }
    }

    fun myAssignedTasks(): List<TaskDto> = _uiState.value.allTasks.filter { it.assigneeId == currentUser.id }
}
