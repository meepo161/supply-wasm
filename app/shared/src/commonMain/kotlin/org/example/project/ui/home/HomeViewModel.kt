package org.example.project.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import org.example.project.data.ApiResult
import org.example.project.data.AssistantRepository
import org.example.project.data.ConversationRepository
import org.example.project.data.NotificationRepository
import org.example.project.data.TaskRepository
import org.example.project.data.pickAttachmentFile
import org.example.project.dto.ApiError
import org.example.project.dto.AssistantAttachmentDto
import org.example.project.dto.AssistantChatRequestDto
import org.example.project.dto.AssistantConfirmDraftRequestDto
import org.example.project.dto.AssistantMessageDto
import org.example.project.dto.AssistantRole
import org.example.project.dto.AssistantUploadDto
import org.example.project.dto.ConversationKind
import org.example.project.dto.NotificationDto
import org.example.project.dto.TaskDto
import org.example.project.dto.UserDto

class HomeViewModel(
    private val taskRepository: TaskRepository,
    private val notificationRepository: NotificationRepository,
    private val conversationRepository: ConversationRepository,
    val currentUser: UserDto,
    private val assistantRepository: AssistantRepository? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private var lastNotifiedUnreadCount: Int = -1

    init {
        loadTasks()
        loadNotifications()
        loadAssistantStatus()
        loadAssistantConversations(autoSelectLatest = true)
    }

    fun dismissError() {
        _uiState.value = _uiState.value.copy(operationError = null)
    }

    fun refresh() {
        _uiState.value = _uiState.value.copy(currentDate = currentIsoDate())
        loadTasks()
        loadNotifications()
        loadAssistantStatus()
        loadAssistantConversations(autoSelectLatest = false)
    }

    fun loadAssistantConversations(autoSelectLatest: Boolean = false) {
        val repo = assistantRepository ?: return
        viewModelScope.launch {
            val result = repo.listConversations()
            if (result is ApiResult.Success) {
                val list = result.data
                _uiState.value = _uiState.value.copy(assistantConversations = list)
                if (autoSelectLatest && _uiState.value.assistantConversationId == null && list.isNotEmpty()) {
                    selectAssistantConversation(list.first().id)
                }
            }
        }
    }

    fun selectAssistantConversation(id: Long) {
        val repo = assistantRepository ?: return
        viewModelScope.launch {
            when (val result = repo.getConversation(id)) {
                is ApiResult.Success -> _uiState.value = _uiState.value.copy(
                    assistantConversationId = result.data.id,
                    assistantMessages = result.data.messages,
                    assistantError = null
                )
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    assistantError = result.error.message
                )
            }
        }
    }

    fun deleteAssistantConversation(id: Long) {
        val repo = assistantRepository ?: return
        viewModelScope.launch {
            when (val result = repo.deleteConversation(id)) {
                is ApiResult.Success -> {
                    val wasActive = _uiState.value.assistantConversationId == id
                    _uiState.value = _uiState.value.copy(
                        assistantConversations = _uiState.value.assistantConversations.filterNot { it.id == id },
                        assistantConversationId = if (wasActive) null else _uiState.value.assistantConversationId,
                        assistantMessages = if (wasActive) emptyList() else _uiState.value.assistantMessages
                    )
                }
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    assistantError = result.error.message
                )
            }
        }
    }

    fun loadAssistantStatus() {
        val repo = assistantRepository ?: return
        viewModelScope.launch {
            when (val result = repo.status()) {
                is ApiResult.Success -> _uiState.value = _uiState.value.copy(
                    assistantAvailable = result.data.available,
                    assistantModel = result.data.model,
                    assistantStatusReason = result.data.reason
                )
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    assistantAvailable = false,
                    assistantStatusReason = result.error.message
                )
            }
        }
    }

    fun updateAssistantInput(value: String) {
        _uiState.value = _uiState.value.copy(assistantInput = value, assistantError = null)
    }

    fun clearAssistantChat() {
        _uiState.value = _uiState.value.copy(
            assistantConversationId = null,
            assistantMessages = emptyList(),
            assistantPendingFiles = emptyList(),
            assistantError = null
        )
    }

    fun pickAssistantFile() {
        val current = _uiState.value
        val existingBytes = current.assistantPendingFiles.sumOf { it.bytes.size.toLong() }
        pickAttachmentFile(
            onPicked = { file ->
                _uiState.value = _uiState.value.copy(
                    assistantPendingFiles = (_uiState.value.assistantPendingFiles + file).take(5),
                    assistantError = null
                )
            },
            onError = { err ->
                _uiState.value = _uiState.value.copy(assistantError = err)
            },
            existingFileCount = current.assistantPendingFiles.size,
            existingTotalBytes = existingBytes
        )
    }

    fun removeAssistantPendingFile(index: Int) {
        val files = _uiState.value.assistantPendingFiles
        if (index !in files.indices) return
        _uiState.value = _uiState.value.copy(
            assistantPendingFiles = files.filterIndexed { i, _ -> i != index }
        )
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun sendAssistantMessage(overrideText: String? = null) {
        val repo = assistantRepository ?: return
        val current = _uiState.value
        if (current.assistantLoading) return
        val text = (overrideText ?: current.assistantInput).trim()
        val pendingFiles = if (overrideText == null) current.assistantPendingFiles else emptyList()
        if (text.isEmpty() && pendingFiles.isEmpty()) return

        val displayText = text.ifEmpty { "Проанализируй приложенный файл(ы)" }
        val attachmentsMeta = pendingFiles.map {
            AssistantAttachmentDto(
                fileName = it.fileName,
                mimeType = it.mimeType,
                sizeBytes = it.bytes.size.toLong()
            )
        }
        val uploads = pendingFiles.map {
            AssistantUploadDto(
                fileName = it.fileName,
                mimeType = it.mimeType,
                base64Data = Base64.encode(it.bytes)
            )
        }

        val updatedMessages = current.assistantMessages + AssistantMessageDto(
            role = AssistantRole.USER,
            content = displayText,
            attachments = attachmentsMeta
        )
        _uiState.value = current.copy(
            assistantMessages = updatedMessages,
            assistantInput = if (overrideText == null) "" else current.assistantInput,
            assistantPendingFiles = if (overrideText == null) emptyList() else current.assistantPendingFiles,
            assistantLoading = true,
            assistantError = null
        )

        viewModelScope.launch {
            when (
                val result = repo.chat(
                    AssistantChatRequestDto(
                        messages = updatedMessages,
                        attachments = uploads,
                        conversationId = current.assistantConversationId
                    )
                )
            ) {
                is ApiResult.Success -> {
                    val replyMsg = AssistantMessageDto(
                        role = AssistantRole.ASSISTANT,
                        content = result.data.reply,
                        toolUsed = result.data.toolUsed,
                        draft = result.data.draft
                    )
                    _uiState.value = _uiState.value.copy(
                        assistantConversationId = result.data.conversationId ?: _uiState.value.assistantConversationId,
                        assistantMessages = _uiState.value.assistantMessages + replyMsg,
                        assistantModel = result.data.model,
                        assistantAvailable = true,
                        assistantLoading = false,
                        assistantError = null
                    )
                    loadAssistantConversations(autoSelectLatest = false)
                }
                is ApiResult.Error -> {
                    _uiState.value = _uiState.value.copy(
                        assistantLoading = false,
                        assistantError = result.error.message
                    )
                }
            }
        }
    }

    fun confirmAssistantDraft(draftId: String) {
        val repo = assistantRepository ?: return
        val current = _uiState.value
        if (current.assistantLoading) return
        val targetDraft = current.assistantMessages.firstOrNull { it.draft?.draftId == draftId }?.draft ?: return
        if (targetDraft.executed) return

        _uiState.value = current.copy(assistantLoading = true, assistantError = null)
        viewModelScope.launch {
            when (
                val result = repo.confirmDraft(
                    AssistantConfirmDraftRequestDto(
                        draft = targetDraft,
                        conversationId = current.assistantConversationId
                    )
                )
            ) {
                is ApiResult.Success -> {
                    val updatedMessages = _uiState.value.assistantMessages.map { msg ->
                        if (msg.draft?.draftId == draftId) {
                            msg.copy(
                                draft = msg.draft!!.copy(
                                    executed = true,
                                    executionSummary = result.data.summary
                                )
                            )
                        } else msg
                    } + AssistantMessageDto(
                        role = AssistantRole.ASSISTANT,
                        content = "Готово:\n${result.data.summary}"
                    )
                    _uiState.value = _uiState.value.copy(
                        assistantMessages = updatedMessages,
                        assistantLoading = false,
                        assistantError = null
                    )
                    loadTasks()
                }
                is ApiResult.Error -> {
                    _uiState.value = _uiState.value.copy(
                        assistantLoading = false,
                        assistantError = result.error.message
                    )
                }
            }
        }
    }

    fun dismissAssistantDraft(draftId: String) {
        val updatedMessages = _uiState.value.assistantMessages.map { msg ->
            if (msg.draft?.draftId == draftId && !msg.draft!!.executed) {
                msg.copy(draft = null)
            } else msg
        } + AssistantMessageDto(
            role = AssistantRole.ASSISTANT,
            content = "Черновик отменён. Изменения в ERP не вносились."
        )
        _uiState.value = _uiState.value.copy(assistantMessages = updatedMessages)
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
