package org.example.project.ui.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.example.project.data.ApiResult
import org.example.project.data.ConversationRepository
import org.example.project.data.NotificationRepository
import org.example.project.data.NotificationStreamEndedException
import org.example.project.data.TaskRepository
import org.example.project.data.appendDistinctNotifications
import org.example.project.data.isNotificationStreamTerminated
import org.example.project.dto.ApiError
import org.example.project.dto.ConversationKind
import org.example.project.dto.NotificationDto
import org.example.project.dto.UserDto
import org.example.project.ui.home.BrowserNotifications

class NotificationViewModel(
    private val notificationRepository: NotificationRepository,
    private val taskRepository: TaskRepository,
    private val conversationRepository: ConversationRepository,
    val currentUser: UserDto
) : ViewModel() {

    private val _uiState = MutableStateFlow(NotificationUiState())
    val uiState: StateFlow<NotificationUiState> = _uiState.asStateFlow()

    private var realtimeJob: Job? = null
    private var pendingOpenJob: Job? = null

    init {
        loadNotifications()
        startRealtime()
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
                    val nextAfter = _uiState.value.nextAfter ?: page.nextAfter
                    val hasMore = if (_uiState.value.nextAfter == null) {
                        page.hasMore
                    } else {
                        _uiState.value.hasMore
                    }
                    _uiState.value = _uiState.value.copy(
                        notifications = _uiState.value.notifications.appendDistinctNotifications(page.notifications),
                        nextAfter = nextAfter,
                        hasMore = hasMore,
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
                        notifications = _uiState.value.notifications.appendDistinctNotifications(page.notifications),
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

    fun markAllRead() {
        viewModelScope.launch {
            when (val result = notificationRepository.markAllRead()) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        notifications = _uiState.value.notifications.map { it.copy(isRead = true) }
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

    fun openPendingNotification(
        onOpenTask: (Long) -> Unit,
        onOpenConversation: (Long) -> Unit,
        onOpenProjectChat: (Long) -> Unit
    ) {
        if (pendingOpenJob?.isActive == true) return
        val eventId = BrowserNotifications.consumePendingEventId() ?: return
        pendingOpenJob = viewModelScope.launch {
            when (val result = notificationRepository.listNotifications(limit = 1, after = eventId - 1)) {
                is ApiResult.Success -> {
                    result.data.notifications.find { it.id == eventId }?.let { notification ->
                        markRead(notification)
                        openNotification(notification, onOpenTask, onOpenConversation, onOpenProjectChat)
                    }
                }
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(operationError = result.error)
            }
        }
    }

    /**
     * Переход из уведомления: объект запрашивается у сервера повторно,
     * права проверяются на каждый переход. CONVERSATION ведёт в раздел «Чат»
     * для DIRECT/GROUP и в проектную вкладку «Обсуждение» для PROJECT.
     */
    fun openNotification(
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

    private fun startRealtime() {
        realtimeJob?.cancel()
        realtimeJob = viewModelScope.launch {
            var attempt = 0
            while (isActive) {
                try {
                    catchUpNotifications()
                    val lastId = _uiState.value.notifications.lastOrNull()?.id
                    notificationRepository.observeNotifications(lastId).collect { notification ->
                        appendNotifications(listOf(notification))
                    }
                } catch (e: NotificationStreamEndedException) {
                    _uiState.value = _uiState.value.copy(
                        operationError = ApiError(
                            "NOTIFICATION_STREAM_ENDED",
                            "Соединение с центром уведомлений завершено"
                        )
                    )
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // сетевой разрыв: переподключение с backoff, догон истории по курсору
                }
                attempt++
                delay(reconnectDelayMs(attempt))
            }
        }
    }

    private suspend fun catchUpNotifications() {
        var after = _uiState.value.notifications.lastOrNull()?.id
        var iterations = 0
        while (iterations < MAX_NOTIFICATION_PAGES) {
            when (val result = notificationRepository.listNotifications(limit = 200, after = after)) {
                is ApiResult.Success -> {
                    appendNotifications(result.data.notifications)
                    if (!result.data.hasMore || result.data.notifications.isEmpty()) break
                    after = result.data.notifications.last().id
                }
                is ApiResult.Error -> {
                    if (isNotificationStreamTerminated(result.statusCode)) {
                        throw NotificationStreamEndedException()
                    }
                    break
                }
            }
            iterations++
        }
    }

    private fun appendNotifications(incoming: List<NotificationDto>) {
        if (incoming.isEmpty()) return
        _uiState.value = _uiState.value.copy(
            notifications = _uiState.value.notifications.appendDistinctNotifications(incoming)
        )
    }

    private fun reconnectDelayMs(attempt: Int): Long {
        val shift = (attempt - 1).coerceAtMost(5)
        return (RECONNECT_BASE_MS shl shift).coerceAtMost(RECONNECT_MAX_MS)
    }

    override fun onCleared() {
        realtimeJob?.cancel()
        super.onCleared()
    }

    private companion object {
        const val RECONNECT_BASE_MS = 1_000L
        const val RECONNECT_MAX_MS = 30_000L
        const val MAX_NOTIFICATION_PAGES = 10
    }
}
